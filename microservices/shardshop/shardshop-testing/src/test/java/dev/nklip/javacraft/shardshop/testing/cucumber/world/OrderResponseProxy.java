package dev.nklip.javacraft.shardshop.testing.cucumber.world;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.Json;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * For each write phase, commits its first request at the real order service but returns 503 instead of the response.
 * The producer must retry the exact request and recover the service's original ID or pending order.
 */
public final class OrderResponseProxy implements AutoCloseable {
    private enum Phase { BUYER, ALLOCATION, PUT }

    private record Attempt(String key, byte[] request, int status, byte[] response) {
    }

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final HttpServer server;
    private final URI backend;
    private final Map<Phase, List<Attempt>> attempts = new EnumMap<>(Phase.class);
    private String firstAllocationCoordinate;
    private byte[] firstAllocationPayload;
    private int firstAllocationCalls;
    private String firstPutPath;
    private byte[] firstPutPayload;
    private int firstPutCalls;
    private boolean replayPayloadMismatch;
    private volatile Exception failure;

    public record ReplayCalls(int allocation, int put) {
    }

    public OrderResponseProxy(int backendPort) throws IOException {
        backend = URI.create("http://127.0.0.1:" + backendPort);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::forward);
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void forward(HttpExchange exchange) throws IOException {
        try (exchange) {
            try {
                byte[] request = exchange.getRequestBody().readAllBytes();
                URI target = backend.resolve(exchange.getRequestURI().toString());
                HttpRequest.Builder builder = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(5))
                        .method(exchange.getRequestMethod(), HttpRequest.BodyPublishers.ofByteArray(request));
                for (String header : List.of("Accept", "Content-Type", "Idempotency-Key")) {
                    String value = exchange.getRequestHeaders().getFirst(header);
                    if (value != null) {
                        builder.header(header, value);
                    }
                }
                HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
                Phase phase = phase(exchange);
                boolean drop = false;
                if (phase != null) {
                    synchronized (attempts) {
                        trackReplay(phase, exchange.getRequestURI().getPath(), request);
                        List<Attempt> observed = attempts.computeIfAbsent(phase, ignored -> new ArrayList<>());
                        if (observed.size() < 2) {
                            drop = observed.isEmpty();
                            observed.add(new Attempt(exchange.getRequestHeaders().getFirst("Idempotency-Key"), request,
                                    response.statusCode(), response.body()));
                        }
                    }
                }
                byte[] body = drop ? "{\"code\":\"ORDER_STORE_UNAVAILABLE\"}".getBytes(StandardCharsets.UTF_8)
                        : response.body();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(drop ? 503 : response.statusCode(), body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                failure = interrupted;
                sendFailure(exchange);
            } catch (IOException problem) {
                failure = problem;
                sendFailure(exchange);
            }
        }
    }

    private static Phase phase(HttpExchange exchange) {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        if (method.equals("POST") && path.equals("/api/v1/buyers")) {
            return Phase.BUYER;
        }
        if (method.equals("POST") && path.endsWith("/order-allocations")) {
            return Phase.ALLOCATION;
        }
        if (method.equals("PUT") && path.contains("/orders/")) {
            return Phase.PUT;
        }
        return null;
    }

    /** Called under the attempts lock. Only the first allocation coordinate and PUT path are retained. */
    private void trackReplay(Phase phase, String path, byte[] request) {
        if (phase == Phase.ALLOCATION) {
            var body = Json.parse(request);
            String coordinate = path + "|" + body.path("runName").asText() + "|"
                    + body.path("requestOrdinal").asText();
            if (firstAllocationCoordinate == null) {
                firstAllocationCoordinate = coordinate;
                firstAllocationPayload = request.clone();
            }
            if (firstAllocationCoordinate.equals(coordinate)) {
                firstAllocationCalls++;
                replayPayloadMismatch |= !Arrays.equals(firstAllocationPayload, request);
            }
        } else if (phase == Phase.PUT) {
            if (firstPutPath == null) {
                firstPutPath = path;
                firstPutPayload = request.clone();
            }
            if (firstPutPath.equals(path)) {
                firstPutCalls++;
                replayPayloadMismatch |= !Arrays.equals(firstPutPayload, request);
            }
        }
    }

    public ReplayCalls replayCalls(String runName, String buyerId, long ordinal, String orderId) {
        String allocation = "/api/v1/buyers/" + buyerId + "/order-allocations|" + runName + "|" + ordinal;
        String put = "/api/v1/buyers/" + buyerId + "/orders/" + orderId;
        synchronized (attempts) {
            assertEquals(allocation, firstAllocationCoordinate, "Tracked allocation coordinate");
            assertEquals(put, firstPutPath, "Tracked PUT path");
            assertFalse(replayPayloadMismatch, "Restart or retry changed an allocation or PUT payload");
            return new ReplayCalls(firstAllocationCalls, firstPutCalls);
        }
    }

    private static void sendFailure(HttpExchange exchange) {
        try {
            byte[] body = "{\"code\":\"ORDER_STORE_UNAVAILABLE\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, body.length);
            exchange.getResponseBody().write(body);
        } catch (IOException ignored) {
            // The client may have already disconnected. The recorded failure is asserted by the scenario.
        }
    }

    public void assertCommittedResponseRecovery() {
        assertNull(failure, "Reverse proxy failed");
        assertRetry(Phase.BUYER, 201, 200, "buyerId");
        assertRetry(Phase.ALLOCATION, 201, 200, "orderId");
        assertRetry(Phase.PUT, 202, 202, "orderId");
    }

    private void assertRetry(Phase phase, int firstStatus, int secondStatus, String idField) {
        List<Attempt> observed;
        synchronized (attempts) {
            observed = List.copyOf(attempts.getOrDefault(phase, List.of()));
        }
        assertEquals(2, observed.size(), phase + " must be retried after its committed response is hidden");
        Attempt first = observed.get(0);
        Attempt second = observed.get(1);
        assertEquals(firstStatus, first.status(), phase + " first provider status");
        assertEquals(secondStatus, second.status(), phase + " retry provider status");
        assertEquals(first.key(), second.key(), phase + " retry key");
        assertArrayEquals(first.request(), second.request(), phase + " retry payload");
        assertEquals(Json.parse(first.response()).path(idField).asText(),
                Json.parse(second.response()).path(idField).asText(), phase + " must recover the same issued ID");
        assertEquals(Json.parse(first.response()), Json.parse(second.response()),
                phase + " provider response changed across retry");
    }

    @Override
    public void close() {
        server.stop(0);
        client.shutdownNow();
    }
}
