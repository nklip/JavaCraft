package dev.nklip.javacraft.shardshop.workload.reader.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.nklip.javacraft.shardshop.common.JsonFields;
import dev.nklip.javacraft.shardshop.workload.reader.http.ReadResult.Outcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductRequestsTest {
    private static final String OK = "{\"productId\":\"42\"}";
    private static final Read<String> READ =
            new Read<>("/api/v1/sellers/1/products/2", node -> JsonFields.id(node, "productId"));

    private final ExecutorService serverThreads = Executors.newCachedThreadPool();
    private final Set<InetSocketAddress> connections = ConcurrentHashMap.newKeySet();
    private final AtomicInteger received = new AtomicInteger();
    private final Deque<Answer> answers = new ConcurrentLinkedDeque<>();
    private final List<ProductRequests> clients = new ArrayList<>();
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            connections.add(exchange.getRemoteAddress());
            received.incrementAndGet();
            Answer answer = answers.poll();
            try {
                (answer == null ? respond(200, OK) : answer).answer(exchange);
            } catch (InterruptedException closing) {
                Thread.currentThread().interrupt();
            } catch (IOException closedByClient) {
                // The client cancelled this exchange at its deadline.
            }
        });
        server.setExecutor(serverThreads);
        server.start();
    }

    @AfterEach
    void stopServer() {
        clients.forEach(ProductRequests::close);
        server.stop(0);
        serverThreads.shutdownNow();
    }

    @Test
    void readsJsonAndReusesOneConnectionForSequentialReads() throws Exception {
        AtomicReference<String> accept = new AtomicReference<>();
        answers.add(exchange -> {
            accept.set(exchange.getRequestHeaders().getFirst("Accept") + " " + exchange.getRequestMethod());
            respond(200, OK).answer(exchange);
        });
        ProductRequests requests = client(16, Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(10), 3);
        for (int read = 0; read < 3; read++) {
            assertEquals(ReadResult.ok("42"), requests.get(READ));
        }
        assertEquals("application/json GET", accept.get());
        assertEquals(3, received.get());
        assertEquals(1, connections.size());
        assertEquals(16, requests.maxConnections());
    }

    @ParameterizedTest
    @CsvSource({"404, PRODUCT_NOT_FOUND, NOT_FOUND", "503, READ_REPLICA_UNAVAILABLE, REPLICA_UNAVAILABLE",
            "503, CATALOG_UNAVAILABLE, UNAVAILABLE", "503, , UNAVAILABLE", "500, , HTTP_ERROR", "400, INVALID_REQUEST, HTTP_ERROR",
            "204, , HTTP_ERROR"})
    void reportsAnHttpStatusOnceWithoutAnotherAttempt(int status, String code, Outcome outcome) throws Exception {
        answers.add(respond(status, code == null ? "" : "{\"code\":\"" + code + "\",\"message\":\"text\"}"));
        ProductRequests requests = client(1, Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(10), 3);
        assertEquals(ReadResult.failed(outcome), requests.get(READ));
        assertEquals(1, received.get());
    }

    @Test
    void reportsAnInvalidOrOversizedBodyOnce() throws Exception {
        answers.add(respond(200, "{"));
        answers.add(respond(200, "{\"productId\":\"01\"}"));
        answers.add(respond(200, new byte[]{(byte) 0xC3, (byte) 0x28}));
        answers.add(respond(200, ("\"" + "x".repeat(ProductRequests.MAX_BODY_BYTES) + "\"").getBytes(StandardCharsets.UTF_8)));
        ProductRequests requests = client(1, Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(10), 3);
        for (int read = 0; read < 4; read++) {
            assertEquals(ReadResult.failed(Outcome.INVALID_RESPONSE), requests.get(READ));
        }
        assertEquals(4, received.get());
    }

    @Test
    void sendsAgainAfterFailuresWithoutResponseAtTheFixedInterval() throws Exception {
        answers.add(HttpExchange::close);
        answers.add(HttpExchange::close);
        ProductRequests requests = client(1, Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(10), 3);
        long started = System.nanoTime();
        assertEquals(ReadResult.ok("42"), requests.get(READ));
        assertTrue(System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(200), "Two 100 ms intervals expected");
        assertEquals(3, received.get());
        assertEquals(3, connections.size());
    }

    @Test
    void reportsTransportAfterTheLastAttemptOrARefusedConnection() throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            answers.add(HttpExchange::close);
        }
        ProductRequests requests = client(1, Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(10), 3);
        assertEquals(ReadResult.failed(Outcome.TRANSPORT), requests.get(READ));
        assertEquals(3, received.get());
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        ProductRequests refused = track(new ProductRequests(new ProductRequests.Settings(
                URI.create("http://127.0.0.1:" + closedPort), 1, Duration.ofSeconds(2), Duration.ofSeconds(5),
                Duration.ofSeconds(30), Duration.ofSeconds(10), 3, Duration.ofMillis(10))));
        assertEquals(ReadResult.failed(Outcome.TRANSPORT), refused.get(READ));
    }

    @Test
    void endsAReadAtTheDeadlineAndUsesANewConnectionAfterwards() throws Exception {
        answers.add(slow(2_000));
        ProductRequests requests = client(1, Duration.ofMillis(300), Duration.ofSeconds(30), Duration.ofSeconds(10), 3);
        long started = System.nanoTime();
        assertEquals(ReadResult.failed(Outcome.DEADLINE), requests.get(READ));
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(1), "The read passed its deadline");
        assertEquals(ReadResult.ok("42"), requests.get(READ));
        assertEquals(2, connections.size());
    }

    @Test
    void limitsConcurrentReadsToThePoolSize() throws Exception {
        for (int read = 0; read < 6; read++) {
            answers.add(slow(150));
        }
        ProductRequests requests = client(2, Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(10), 3);
        ExecutorService readers = Executors.newFixedThreadPool(6);
        try {
            List<Future<ReadResult<String>>> results = new ArrayList<>();
            for (int read = 0; read < 6; read++) {
                results.add(readers.submit(() -> requests.get(READ)));
            }
            for (Future<ReadResult<String>> result : results) {
                assertEquals(ReadResult.ok("42"), result.get(5, TimeUnit.SECONDS));
            }
        } finally {
            readers.shutdownNow();
        }
        assertEquals(2, connections.size());
        assertEquals(0, requests.poolStats().getLeased());
        assertEquals(2, requests.poolStats().getMax());
    }

    @Test
    void retiresAConnectionAfterItsLifetimeAndClosesAnIdleConnection() throws Exception {
        ProductRequests aging = client(1, Duration.ofSeconds(5), Duration.ofMillis(300), Duration.ofSeconds(10), 3);
        aging.get(READ);
        Thread.sleep(400);
        aging.get(READ);
        assertEquals(2, connections.size());

        ProductRequests idle = client(1, Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMillis(200), 3);
        idle.get(READ);
        assertEquals(1, idle.poolStats().getAvailable());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (idle.poolStats().getAvailable() > 0) {
            assertTrue(System.nanoTime() - deadline < 0, "The idle connection was not closed");
            Thread.sleep(20);
        }
        idle.get(READ);
        assertEquals(4, connections.size());
    }

    @Test
    void stopsWithInterruptedExceptionInsteadOfAnOutcome() throws Exception {
        ProductRequests requests = client(1, Duration.ofMillis(500), Duration.ofSeconds(30), Duration.ofSeconds(10), 3);
        Thread.currentThread().interrupt();
        assertThrows(InterruptedException.class, () -> requests.get(READ));
        assertFalse(Thread.currentThread().isInterrupted());
        assertEquals(0, received.get());

        answers.add(slow(2_000));
        AtomicReference<Object> outcome = new AtomicReference<>();
        Thread reader = Thread.ofPlatform().start(() -> {
            try {
                outcome.set(requests.get(READ));
            } catch (InterruptedException stopped) {
                outcome.set(stopped);
            }
        });
        while (received.get() == 0) {
            Thread.sleep(10);
        }
        reader.interrupt();
        reader.join(5_000);
        assertInstanceOf(InterruptedException.class, outcome.get());
    }

    @Test
    void rejectsInvalidSettings() {
        URI url = URI.create("http://product:8080");
        Duration second = Duration.ofSeconds(1);
        for (String invalid : new String[]{"file:///product", "http:/missing-host", "http://product?query",
                "http://product#fragment", "http://user@product"}) {
            assertThrows(IllegalArgumentException.class, () -> new ProductRequests.Settings(URI.create(invalid), 1,
                    second, second, second, second, 1, Duration.ZERO));
        }
        assertThrows(IllegalArgumentException.class, () -> new ProductRequests.Settings(url, 0, second, second, second, second, 1, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new ProductRequests.Settings(url, 65, second, second, second, second, 1, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new ProductRequests.Settings(url, 1, Duration.ZERO, second, second, second, 1, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new ProductRequests.Settings(url, 1, Duration.ofSeconds(2), second, second, second, 1, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new ProductRequests.Settings(url, 1, second, Duration.ZERO, second, second, 1, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new ProductRequests.Settings(url, 1, second, second, Duration.ZERO, second, 1, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new ProductRequests.Settings(url, 1, second, second, second, Duration.ZERO, 1, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new ProductRequests.Settings(url, 1, second, second, second, second, 0, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new ProductRequests.Settings(url, 1, second, second, second, second, 6, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new ProductRequests.Settings(url, 1, second, second, second, second, 1, Duration.ofMillis(-1)));
        assertThrows(IllegalArgumentException.class, () -> new ProductRequests.Settings(url, 1, second, second, second, second, 1, second));
        assertThrows(NullPointerException.class, () -> new ProductRequests.Settings(null, 1, second, second, second, second, 1, Duration.ZERO));
        assertEquals(URI.create("https://product"), new ProductRequests.Settings(URI.create("https://product"), 64,
                second, second, second, second, 5, Duration.ZERO).productUrl());
    }

    private ProductRequests client(int connections, Duration deadline, Duration lifetime, Duration idle, int attempts) {
        return track(new ProductRequests(new ProductRequests.Settings(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), connections,
                Duration.ofMillis(Math.min(200, deadline.toMillis())), deadline, lifetime, idle, attempts,
                Duration.ofMillis(100))));
    }

    private ProductRequests track(ProductRequests requests) {
        clients.add(requests);
        return requests;
    }

    private static Answer respond(int status, String body) {
        return respond(status, body.getBytes(StandardCharsets.UTF_8));
    }

    private static Answer respond(int status, byte[] body) {
        return exchange -> {
            try (OutputStream output = exchange.getResponseBody()) {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
                output.write(body);
            }
        };
    }

    private static Answer slow(long millis) {
        return exchange -> {
            Thread.sleep(millis);
            respond(200, OK).answer(exchange);
        };
    }

    @FunctionalInterface
    private interface Answer {
        void answer(HttpExchange exchange) throws IOException, InterruptedException;
    }
}
