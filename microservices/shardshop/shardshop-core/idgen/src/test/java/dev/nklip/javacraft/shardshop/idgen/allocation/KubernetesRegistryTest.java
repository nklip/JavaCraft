package dev.nklip.javacraft.shardshop.idgen.allocation;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.nklip.javacraft.shardshop.idgen.JdkRootCertificate;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KubernetesRegistryTest {
    private static final String UID = "12345678-1234-5678-9abc-123456789abc";
    private static final String PATH = "/api/v1/namespaces/shardshop/configmaps/shardshop-snowflake-generators";
    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private static final RegistryState INITIAL = new RegistryState(UID, "15", 0);
    private static final String READ_FAILURE = "Generator registry could not be read; check registry existence, state,"
            + " Kubernetes API access, the service account token and GET permission.";
    private static final String WRITE_FAILURE = "Generator registry could not be updated; check registry existence,"
            + " the service account token and PATCH permission.";
    private static final String CLIENT_FAILURE = "Kubernetes API access is not configured correctly; check"
            + " shardshop.id.registry.url, namespace and ca-file.";

    private final ExecutorService serverThreads = Executors.newCachedThreadPool();
    private final Deque<Answer> answers = new ConcurrentLinkedDeque<>();
    private final List<Request> received = new CopyOnWriteArrayList<>();
    private final CountDownLatch stopServer = new CountDownLatch(1);
    private final List<KubernetesRegistry> registries = new ArrayList<>();
    private HttpServer server;
    private Path tokenFile;

    @TempDir
    Path directory;

    @BeforeEach
    void startServer() throws IOException {
        tokenFile = Files.writeString(directory.resolve("token"), "first.token-value\n");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            received.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Accept"),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    exchange.getRequestHeaders().getFirst("User-Agent"),
                    new String(body, StandardCharsets.UTF_8)));
            Answer answer = answers.poll();
            try {
                (answer == null ? respond(500, "{}") : answer).answer(exchange);
            } catch (InterruptedException stopping) {
                Thread.currentThread().interrupt();
            } catch (IOException closedByClient) {
                // The client cancelled the exchange at its timeout.
            }
        });
        server.setExecutor(serverThreads);
        server.start();
    }

    @AfterEach
    void stopServer() {
        registries.forEach(KubernetesRegistry::close);
        stopServer.countDown();
        server.stop(0);
        serverThreads.shutdownNow();
    }

    @Test
    void readsTheNamedRegistryWithTheCurrentTokenOfEachRequest() throws Exception {
        answers.add(respond(200, configMap(UID, "15", "0")));
        answers.add(respond(200, configMap(UID, "16", "1")));
        KubernetesRegistry registry = registry();

        assertEquals(INITIAL, registry.read(TIMEOUT));
        Files.writeString(tokenFile, "second.token-value");
        assertEquals(new RegistryState(UID, "16", 1), registry.read(TIMEOUT));

        assertEquals(List.of(
                new Request("GET", PATH, "Bearer first.token-value", "application/json", null,
                        "shardshop-generator-allocator", ""),
                new Request("GET", PATH, "Bearer second.token-value", "application/json", null,
                        "shardshop-generator-allocator", "")), received);
    }

    @Test
    void anApiServerUrlMayEndWithASlash() throws Exception {
        answers.add(respond(200, configMap(UID, "15", "0")));
        KubernetesRegistry registry = new KubernetesRegistry(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), "shardshop", tokenFile,
                DefaultClientTlsStrategy.createDefault());
        registries.add(registry);

        assertEquals(INITIAL, registry.read(TIMEOUT));
        assertEquals(PATH, received.getFirst().uri());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "999", "1023"})
    void acceptsCanonicalHighWaterMarks(String highWaterMark) throws Exception {
        answers.add(respond(200, configMap(UID, "0015", highWaterMark)));

        assertEquals(new RegistryState(UID, "0015", Integer.parseInt(highWaterMark)), registry().read(TIMEOUT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "[]", "{}", "\"text\"",
            "{\"metadata\":{\"resourceVersion\":\"15\"},\"data\":{\"highWaterMark\":\"0\"}}",
            "{\"metadata\":{\"uid\":\"" + UID + "\"},\"data\":{\"highWaterMark\":\"0\"}}",
            "{\"metadata\":{\"uid\":\"" + UID + "\",\"resourceVersion\":\"15\"}}",
            "{\"metadata\":{\"uid\":\"" + UID + "\",\"resourceVersion\":\"15\"},\"data\":{\"highWaterMark\":0}}",
            "{\"metadata\":{\"uid\":\"12345678-1234-5678-9ABC-123456789ABC\",\"resourceVersion\":\"15\"},"
                    + "\"data\":{\"highWaterMark\":\"0\"}}",
            "{\"metadata\":{\"uid\":\"" + UID + "\",\"resourceVersion\":\"1\\\"5\"},\"data\":{\"highWaterMark\":\"0\"}}",
            "{\"metadata\":{\"uid\":\"" + UID + "\",\"resourceVersion\":15},\"data\":{\"highWaterMark\":\"0\"}}",
            "{\"metadata\":{\"uid\":\"" + UID + "\",\"resourceVersion\":\"15\"},\"data\":{\"highWaterMark\":\"00\"}}",
            "{\"metadata\":{\"uid\":\"" + UID + "\",\"resourceVersion\":\"15\"},\"data\":{\"highWaterMark\":\"-1\"}}",
            "{\"metadata\":{\"uid\":\"" + UID + "\",\"resourceVersion\":\"15\"},\"data\":{\"highWaterMark\":\"1024\"}}",
            "{\"metadata\":{\"uid\":\"" + UID + "\",\"resourceVersion\":\"15\"},\"data\":{\"highWaterMark\":\"10000\"}}",
            "{\"metadata\":{\"uid\":\"" + UID + "\",\"resourceVersion\":\"15\"},\"data\":{\"highWaterMark\":\"0\\n\"}}",
            "{\"metadata\":{\"uid\":\"" + UID + "\",\"resourceVersion\":\"15\"},\"data\":{\"highWaterMark\":\"0\"}} {}",
            "{\"metadata\":{\"uid\":\"" + UID + "\",\"resourceVersion\":\"15\"},\"data\":{\"highWaterMark\":\"0\","
                    + "\"highWaterMark\":\"1\"}}"})
    void rejectsInvalidRegistryStateWithAFixedMessage(String body) {
        answers.add(respond(200, body));

        AllocationFailure failure = assertThrows(AllocationFailure.class, () -> registry().read(TIMEOUT));
        assertEquals(READ_FAILURE, failure.getMessage());
        assertNull(failure.getCause());
    }

    @Test
    void acceptsABodyUpToTheLimitAndRejectsALargerBody() throws Exception {
        String body = configMap(UID, "15", "0");
        answers.add(respond(200, body + " ".repeat(KubernetesRegistry.MAX_BODY_BYTES - body.length())));
        answers.add(respond(200, body + " ".repeat(KubernetesRegistry.MAX_BODY_BYTES - body.length() + 1)));
        KubernetesRegistry registry = registry();

        assertEquals(INITIAL, registry.read(TIMEOUT));
        assertEquals(READ_FAILURE, assertThrows(AllocationFailure.class, () -> registry.read(TIMEOUT)).getMessage());
    }

    @ParameterizedTest
    @ValueSource(ints = {201, 401, 403, 404, 409, 500, 503})
    void otherStatusesFailTheReadWithoutTheResponseText(int status) {
        answers.add(respond(status, "{\"message\":\"sensitive server text\"}"));

        AllocationFailure failure = assertThrows(AllocationFailure.class, () -> registry().read(TIMEOUT));
        assertEquals(READ_FAILURE, failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(1, received.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "\n", "two words", "bad\"token", "tökén"})
    void aMissingOrInvalidTokenSendsNoRead(String token) throws Exception {
        Files.writeString(tokenFile, token, StandardCharsets.UTF_8);

        assertEquals(READ_FAILURE, assertThrows(AllocationFailure.class, () -> registry().read(TIMEOUT)).getMessage());
        assertEquals(List.of(), received);
    }

    @Test
    void aMissingTokenFileSendsNoRead() throws Exception {
        Files.delete(tokenFile);

        assertEquals(READ_FAILURE, assertThrows(AllocationFailure.class, () -> registry().read(TIMEOUT)).getMessage());
        assertEquals(List.of(), received);
    }

    @Test
    void aReadEndsAtItsTimeoutWhenTheServerDoesNotAnswer() {
        answers.add(exchange -> stopServer.await(30, TimeUnit.SECONDS));
        KubernetesRegistry registry = registry();

        long started = System.nanoTime();
        assertEquals(READ_FAILURE, assertThrows(AllocationFailure.class,
                () -> registry.read(Duration.ofMillis(300))).getMessage());
        assertTrue(System.nanoTime() - started < Duration.ofSeconds(3).toNanos());
    }

    @Test
    void aRefusedConnectionFailsTheRead() throws Exception {
        KubernetesRegistry registry = new KubernetesRegistry(URI.create("http://127.0.0.1:" + closedPort()),
                "shardshop", tokenFile, DefaultClientTlsStrategy.createDefault());
        registries.add(registry);

        assertEquals(READ_FAILURE, assertThrows(AllocationFailure.class, () -> registry.read(TIMEOUT)).getMessage());
    }

    @Test
    void reservesWithOneAtomicJsonPatchOfAllThreePreconditions() throws Exception {
        answers.add(respond(200, configMap(UID, "16", "1")));

        assertTrue(registry().reserve(INITIAL, 1, TIMEOUT));
        assertEquals(List.of(new Request("PATCH", PATH + "?fieldManager=shardshop-generator-allocator",
                "Bearer first.token-value", "application/json", "application/json-patch+json",
                "shardshop-generator-allocator",
                "[{\"op\":\"test\",\"path\":\"/metadata/uid\",\"value\":\"" + UID + "\"},"
                        + "{\"op\":\"test\",\"path\":\"/metadata/resourceVersion\",\"value\":\"15\"},"
                        + "{\"op\":\"test\",\"path\":\"/data/highWaterMark\",\"value\":\"0\"},"
                        + "{\"op\":\"replace\",\"path\":\"/data/highWaterMark\",\"value\":\"1\"}]")), received);
    }

    @Test
    void reservesBeyondABurnedSlot() throws Exception {
        answers.add(respond(200, configMap(UID, "16", "3")));

        assertTrue(registry().reserve(INITIAL, 3, TIMEOUT));
        assertTrue(received.getFirst().body().endsWith(
                "{\"op\":\"replace\",\"path\":\"/data/highWaterMark\",\"value\":\"3\"}]"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"metadata\":{\"uid\":\"" + UID + "\",\"resourceVersion\":\"16\"},\"data\":{\"highWaterMark\":\"2\"}}",
            "{\"metadata\":{\"uid\":\"" + UID + "\",\"resourceVersion\":\"15\"},\"data\":{\"highWaterMark\":\"1\"}}",
            "{\"metadata\":{\"uid\":\"11111111-1111-1111-1111-111111111111\",\"resourceVersion\":\"16\"},"
                    + "\"data\":{\"highWaterMark\":\"1\"}}",
            "{\"metadata\":{}}", "not json"})
    void aResponseThatDoesNotConfirmTheReservationIsNotAReservation(String body) throws Exception {
        answers.add(respond(200, body));

        assertFalse(registry().reserve(INITIAL, 1, TIMEOUT));
    }

    @ParameterizedTest
    @ValueSource(ints = {201, 409, 422, 429, 500, 503})
    void conflictsDenialsAndUnknownOutcomesAreNotReservations(int status) throws Exception {
        answers.add(respond(status, "{\"message\":\"sensitive server text\"}"));

        assertFalse(registry().reserve(INITIAL, 1, TIMEOUT));
        assertEquals(1, received.size());
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 404})
    void aRefusedPatchFailsWithAFixedMessage(int status) {
        answers.add(respond(status, "{\"message\":\"sensitive server text\"}"));

        AllocationFailure failure = assertThrows(AllocationFailure.class, () -> registry().reserve(INITIAL, 1, TIMEOUT));
        assertEquals(WRITE_FAILURE, failure.getMessage());
        assertNull(failure.getCause());
    }

    @Test
    void aLostResponseIsAnUnknownOutcome() throws Exception {
        answers.add(HttpExchange::close);

        assertFalse(registry().reserve(INITIAL, 1, TIMEOUT));
        assertEquals("PATCH", received.getFirst().method());
    }

    @Test
    void aPatchEndsAtItsTimeoutWhenTheServerDoesNotAnswer() throws Exception {
        answers.add(exchange -> stopServer.await(30, TimeUnit.SECONDS));
        KubernetesRegistry registry = registry();

        long started = System.nanoTime();
        assertFalse(registry.reserve(INITIAL, 1, Duration.ofMillis(300)));
        assertTrue(System.nanoTime() - started < Duration.ofSeconds(3).toNanos());
    }

    @Test
    void aMissingTokenSendsNoPatch() throws Exception {
        Files.delete(tokenFile);

        assertEquals(WRITE_FAILURE, assertThrows(AllocationFailure.class,
                () -> registry().reserve(INITIAL, 1, TIMEOUT)).getMessage());
        assertEquals(List.of(), received);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1024})
    void aReservationMustAdvanceWithinTheLiveRange(int next) {
        KubernetesRegistry registry = registry();
        RegistryState state = new RegistryState(UID, "15", next == 0 ? 0 : 1023);

        assertThrows(IllegalArgumentException.class, () -> registry.reserve(state, next, TIMEOUT));
        assertThrows(NullPointerException.class, () -> registry.reserve(null, 1, TIMEOUT));
        assertEquals(List.of(), received);
    }

    @Test
    void requiresAPositiveTimeout() {
        KubernetesRegistry registry = registry();

        assertThrows(IllegalArgumentException.class, () -> registry.read(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> registry.reserve(INITIAL, 1, Duration.ofMillis(-1)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://127.0.0.1:1/api", "http://user@127.0.0.1:1", "http://127.0.0.1:1?query",
            "http://127.0.0.1:1#fragment", "http:relative"})
    void theApiServerUrlHasOnlyAHostAndAPort(String apiServer) {
        assertEquals(CLIENT_FAILURE, assertThrows(AllocationFailure.class, () -> new KubernetesRegistry(
                URI.create(apiServer), "shardshop", tokenFile, DefaultClientTlsStrategy.createDefault())).getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Shardshop", "-shardshop", "shardshop-", "shard_shop", "shard/shop"})
    void theNamespaceIsAKubernetesName(String namespace) {
        assertEquals(CLIENT_FAILURE, assertThrows(AllocationFailure.class, () -> new KubernetesRegistry(
                URI.create("http://127.0.0.1:1"), namespace, tokenFile, DefaultClientTlsStrategy.createDefault()))
                .getMessage());
        assertThrows(AllocationFailure.class, () -> new KubernetesRegistry(URI.create("http://127.0.0.1:1"), null,
                tokenFile, DefaultClientTlsStrategy.createDefault()));
    }

    @Test
    void connectTrustsTheCaFileAndUsesHttps() throws Exception {
        Path caFile = Files.writeString(directory.resolve("ca.crt"), JdkRootCertificate.pem());
        try (KubernetesRegistry registry = KubernetesRegistry.connect("https://127.0.0.1:" + closedPort(),
                "shardshop", tokenFile.toString(), caFile.toString())) {
            assertEquals(READ_FAILURE, assertThrows(AllocationFailure.class, () -> registry.read(TIMEOUT)).getMessage());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://127.0.0.1:6443", "127.0.0.1:6443", "https://127.0.0.1:6443/api",
            "https://exa mple:6443"})
    void connectNeedsAnHttpsUrlWithOnlyAHostAndAPort(String apiServer) throws Exception {
        Path caFile = Files.writeString(directory.resolve("ca.crt"), JdkRootCertificate.pem());

        assertEquals(CLIENT_FAILURE, assertThrows(AllocationFailure.class, () -> KubernetesRegistry.connect(apiServer,
                "shardshop", tokenFile.toString(), caFile.toString())).getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing.crt", "empty.crt", "invalid.crt"})
    void connectNeedsACaCertificate(String file) throws Exception {
        Files.writeString(directory.resolve("empty.crt"), "");
        Files.writeString(directory.resolve("invalid.crt"),
                "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n");

        assertEquals(CLIENT_FAILURE, assertThrows(AllocationFailure.class, () -> KubernetesRegistry.connect(
                "https://127.0.0.1:6443", "shardshop", tokenFile.toString(), directory.resolve(file).toString()))
                .getMessage());
    }

    private KubernetesRegistry registry() {
        try {
            KubernetesRegistry registry = new KubernetesRegistry(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "shardshop", tokenFile,
                    DefaultClientTlsStrategy.createDefault());
            registries.add(registry);
            return registry;
        } catch (AllocationFailure failure) {
            throw new AssertionError(failure);
        }
    }

    private static String configMap(String uid, String resourceVersion, String highWaterMark) {
        return "{\"kind\":\"ConfigMap\",\"metadata\":{\"name\":\"shardshop-snowflake-generators\",\"uid\":\"" + uid
                + "\",\"resourceVersion\":\"" + resourceVersion + "\"},\"data\":{\"highWaterMark\":\""
                + highWaterMark + "\"}}";
    }

    private static Answer respond(int status, String body) {
        return exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        };
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @FunctionalInterface
    private interface Answer {
        void answer(HttpExchange exchange) throws IOException, InterruptedException;
    }

    private record Request(String method, String uri, String authorization, String accept, String contentType,
                           String userAgent, String body) {
    }
}
