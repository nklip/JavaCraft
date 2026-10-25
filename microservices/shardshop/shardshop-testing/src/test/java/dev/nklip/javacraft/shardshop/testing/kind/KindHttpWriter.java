package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.core.JsonParser;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.Json;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static dev.nklip.javacraft.shardshop.testing.kind.KindDeployment.require;

/** Bounded scoped traffic through the Service proxy, which can select new pods during a rollout. */
final class KindHttpWriter implements AutoCloseable {
    private static final int DIAGNOSTIC_BODY_LIMIT = 4096;
    private static final ObjectMapper DIAGNOSTIC_JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).build();
    private static final Set<String> APPLICATION_CODES = Set.of("ORDER_STORE_UNAVAILABLE",
            "ID_GENERATION_UNAVAILABLE", "CATALOG_UNAVAILABLE");
    private static final Set<String> KUBERNETES_REASONS = Set.of("ServiceUnavailable", "Timeout", "ServerTimeout",
            "InternalError", "NotFound", "Forbidden", "Unauthorized", "BadRequest", "TooManyRequests");
    private final Process proxy;
    private final HttpClient client;
    private final URI root;
    private final String run;
    private final JsonNode request;
    private final List<String> accepted = new CopyOnWriteArrayList<>();
    private final CountDownLatch stopping = new CountDownLatch(1);
    private final AtomicReference<Exception> failure = new AtomicReference<>();
    private final Thread writer;

    KindHttpWriter(KindLab lab, String buyer, String run, JsonNode request) throws IOException, InterruptedException {
        KindSaga.id(buyer);
        this.run = run;
        this.request = request.deepCopy();
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        proxy = lab.processBuilder("kubectl", "--context", "kind-" + lab.clusterName(), "proxy",
                "--address=127.0.0.1", "--port=" + port).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            lab.await("local Kubernetes Service proxy", Duration.ofSeconds(20), () -> {
                require(proxy.isAlive(), "Service proxy exited");
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
                    return true;
                }
            });
        } catch (IOException | InterruptedException error) {
            proxy.destroyForcibly();
            throw error;
        }
        root = URI.create("http://127.0.0.1:" + port
                + "/api/v1/namespaces/shardshop/services/http:shardshop-order:8080/proxy/api/v1/buyers/" + buyer + "/");
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build();
        writer = Thread.ofVirtual().name("kind-rollout-writer").start(this::write);
    }

    private void write() {
        try {
            // At most one order per second for five minutes; finite stock use even if rollout never finishes.
            for (int ordinal = 0; ordinal < 300 && stopping.getCount() != 0; ordinal++) {
                JsonNode allocation = call("POST", "order-allocations",
                        Json.newObject().put("runName", run).put("requestOrdinal", Integer.toString(ordinal)), 201, ordinal);
                String id = KindSaga.id(allocation.path("orderId").asText());
                call("PUT", "orders/" + id, request, 202, ordinal);
                accepted.add(id);
                if (stopping.await(1, TimeUnit.SECONDS)) return;
            }
            if (stopping.getCount() != 0) throw new IOException("Rollout writer exhausted its five-minute budget");
        } catch (Exception error) {
            failure.set(error);
        }
    }

    private JsonNode call(String method, String path, JsonNode body, int expected, int ordinal) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(root.resolve(path)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofByteArray(Json.bytes(body))).build();
        long started = System.nanoTime();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        String diagnostic = response.statusCode() == expected ? "" : "; observedAt=" + Instant.now() + "; ordinal=" + ordinal
                + "; elapsedMs=" + elapsedMillis + "; "
                + failureDetails(response.headers().firstValue("Content-Type").orElse(""), response.body());
        require(response.statusCode() == expected, "Rollout writer " + method + " expected " + expected
                + "; received " + response.statusCode() + diagnostic);
        return KindLab.parse(response.body());
    }

    /** Only fixed diagnostic labels escape this method; upstream messages and payloads remain private. */
    static String failureDetails(String contentType, String body) {
        String normalized = "other";
        if (contentType == null || contentType.isBlank()) normalized = "missing";
        else if (contentType.length() <= 256) {
            String mediaType = contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
            if (Set.of("application/json", "application/problem+json", "text/plain", "text/html",
                    "application/octet-stream").contains(mediaType)) normalized = mediaType;
        }
        String classification = "origin=unknown; detail=redacted";
        if ((normalized.equals("application/json") || normalized.equals("application/problem+json"))
                && body != null && body.length() <= DIAGNOSTIC_BODY_LIMIT) {
            try {
                JsonNode error = DIAGNOSTIC_JSON.readTree(body);
                if (error != null && error.isObject()) {
                    String code = error.path("code").asText();
                    String reason = error.path("reason").asText();
                    if (error.path("kind").asText().equals("Status") && KUBERNETES_REASONS.contains(reason))
                        classification = "origin=kubernetes; kind=Status; reason=" + reason;
                    else if (APPLICATION_CODES.contains(code)) classification = "origin=application; code=" + code;
                }
            } catch (IOException ignored) {
                // Parser errors can quote private input. Never retain or propagate them.
            }
        }
        return "contentType=" + normalized + "; " + classification;
    }

    int count() throws IOException {
        check();
        return accepted.size();
    }

    List<String> accepted() throws IOException {
        check();
        return List.copyOf(accepted);
    }

    private void check() throws IOException {
        if (failure.get() != null) throw new IOException("Continuous HTTP writes failed during rollout", failure.get());
    }

    @Override public void close() throws IOException, InterruptedException {
        stopping.countDown();
        try {
            writer.join(15_000);
            require(!writer.isAlive(), "Rollout writer did not stop within its HTTP deadline");
            check();
        } finally {
            writer.interrupt();
            client.close();
            proxy.destroy();
            if (!proxy.waitFor(2, TimeUnit.SECONDS)) proxy.destroyForcibly();
        }
    }
}
