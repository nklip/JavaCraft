package dev.nklip.javacraft.shardshop.workload.reader.load;

import dev.nklip.javacraft.shardshop.workload.dataset.CatalogDataset;
import dev.nklip.javacraft.shardshop.workload.reader.catalog.ReaderDatasets;
import dev.nklip.javacraft.shardshop.workload.reader.http.ProductRequests;
import dev.nklip.javacraft.shardshop.workload.reader.http.ReadResult.Outcome;
import dev.nklip.javacraft.shardshop.workload.reader.metrics.ReaderMetrics;
import org.apache.hc.core5.pool.PoolStats;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductReaderTest {
    private static final Pause SLEEP = TimeUnit.NANOSECONDS::sleep;
    private static final PoolStats NO_POOL = new PoolStats(0, 0, 0, 0);

    private final List<LogRecord> logs = new CopyOnWriteArrayList<>();
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            logs.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };
    private final Logger log = Logger.getLogger(ProductReader.class.getName());
    private final AtomicInteger failures = new AtomicInteger();
    private final RandomGenerator random = new Random(7)::nextLong;
    private FakeProductServer product;
    private ProductRequests requests;
    private ReaderMetrics metrics;
    private ProductReader reader;

    @BeforeEach
    void startProduct() throws Exception {
        product = new FakeProductServer();
        log.addHandler(capture);
    }

    @AfterEach
    void stopEverything() {
        if (reader != null) {
            reader.stop();
        }
        product.close();
        log.removeHandler(capture);
    }

    @Test
    void resolvesTheIssuedIdsThenReadsAtTheConfiguredRateAndRotatesEachConnection() throws Exception {
        reader = reader(2, 3, 50, Duration.ofMillis(300), Duration.ofSeconds(5));
        reader.start(reader.resolve(), failures::incrementAndGet);
        long started = System.nanoTime();
        assertEquals(150, product.sellerLookups.get());
        assertEquals(300, product.productLookups.get());
        awaitUntil(() -> product.reads.get() >= 60);
        reader.stop();
        double seconds = (System.nanoTime() - started) / 1e9;

        // The first worker can start just before the clock above; later starts keep 20 ms gaps.
        assertTrue(product.reads.get() <= 2 + 50 * seconds, product.reads.get() + " reads in " + seconds + " s");
        ReaderMetrics.Report total = metrics.total(NO_POOL);
        assertFalse(total.failed());
        assertEquals(Set.of("ASIA", "EU", "US"), total.regions().keySet());
        // Two connections with a 300 ms lifetime rotate several times during more than one second of reads.
        assertTrue(product.connections.size() >= 4, product.connections.size() + " connections");
        assertEquals(0, failures.get());
        assertFalse(reader.failed());
        assertEquals(List.of(
                "INFO Resolved catalog-v1 [US 50 sellers, 100 products; EU 50 sellers, 100 products; "
                        + "ASIA 50 sellers, 100 products], key-to-ID SHA-256 " + expectedDigest(),
                "INFO Reading at 50 requests/s with 3 workers and 2 connections"), messages().subList(0, 2));
        assertTrue(messages().getLast().startsWith("INFO Product reads, total "), messages().getLast());
        assertTrue(messages().getLast().endsWith(", max=2}"), messages().getLast());
        assertTrue(messages().stream().anyMatch(message -> message.startsWith("INFO Product reads, interval ")));
    }

    @Test
    void endsAReadAtItsDeadlineClosesThatConnectionAndRecovers() throws Exception {
        product.readDelayMillis = 2_000;
        reader = reader(1, 1, 20, Duration.ofSeconds(30), Duration.ofMillis(300));
        reader.start(reader.resolve(), failures::incrementAndGet);
        awaitUntil(() -> outcomes(Outcome.DEADLINE) >= 2);
        product.readDelayMillis = 0;
        awaitUntil(() -> outcomes(Outcome.OK) >= 2);
        reader.stop();

        ReaderMetrics.Report total = metrics.total(NO_POOL);
        assertTrue(total.latencyMilliseconds().get("max") < 1_000, total.summary());
        // Each read that ends at its deadline closes its connection.
        assertTrue(product.connections.size() >= 3, product.connections.size() + " connections");
        assertTrue(messages().getLast().startsWith("WARN Product reads, total "), messages().getLast());
        assertEquals(0, failures.get());
    }

    @Test
    void logsTheTotalsAfterAReportThatRunsDuringStop() throws Exception {
        CountDownLatch reporting = new CountDownLatch(1);
        Handler slowReport = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getMessage().startsWith("Product reads, interval ")) {
                    reporting.countDown();
                    // Ignore the interrupt of stop(), thus the report thread is still busy when stop() continues.
                    long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300);
                    boolean interrupted = false;
                    while (System.nanoTime() - end < 0) {
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException stopping) {
                            interrupted = true;
                        }
                    }
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        log.removeHandler(capture);
        log.addHandler(slowReport);
        log.addHandler(capture);
        try {
            reader = reader(1, 1, 50, Duration.ofSeconds(30), Duration.ofSeconds(5));
            reader.start(reader.resolve(), failures::incrementAndGet);
            assertTrue(reporting.await(5, TimeUnit.SECONDS));
            reader.stop();
        } finally {
            log.removeHandler(slowReport);
        }
        List<String> messages = messages();
        assertTrue(messages.get(messages.size() - 2).startsWith("INFO Product reads, interval "), messages.toString());
        assertTrue(messages.getLast().startsWith("INFO Product reads, total "), messages.toString());
    }

    @ParameterizedTest
    @CsvSource({"catalog-v1.EU.seller.10, Look up seller", "catalog-v1.ASIA.product.7, Look up product"})
    void reportsAMissingEntityAsNotReadyAndResolvesAgainAfterItAppears(String key, String operation) throws Exception {
        product.missingKeys.add(key);
        reader = reader(2, 2, 50, Duration.ofSeconds(30), Duration.ofSeconds(5));
        CatalogNotReadyException failure = assertThrows(CatalogNotReadyException.class, reader::resolve);
        assertEquals(operation + " " + key + " failed with NOT_FOUND", failure.getMessage());
        assertEquals(List.of(), messages());

        product.missingKeys.remove(key);
        ProductReader.Catalog catalog = reader.resolve();
        assertEquals(1, messages().size());
        assertTrue(messages().getFirst().startsWith("INFO Resolved catalog-v1 "), messages().getFirst());
        assertEquals(0, product.reads.get());
        reader.start(catalog, failures::incrementAndGet);
        awaitUntil(() -> product.reads.get() > 0);
    }

    @Test
    void reportsAConflictingEntityAsAFailureThatAWaitCannotRepair() {
        product.conflictingKeys.add("catalog-v1.US.seller.3");
        reader = reader(1, 1, 50, Duration.ofSeconds(30), Duration.ofSeconds(5));
        ReaderException failure = assertThrows(ReaderException.class, reader::resolve);
        assertFalse(failure instanceof CatalogNotReadyException, failure.getClass().getName());
        assertEquals("Look up seller catalog-v1.US.seller.3 failed with INVALID_RESPONSE", failure.getMessage());
    }

    @ParameterizedTest
    @CsvSource({"503, CATALOG_UNAVAILABLE, UNAVAILABLE, true", "503, READ_REPLICA_UNAVAILABLE, REPLICA_UNAVAILABLE, true",
            "500, INTERNAL_ERROR, HTTP_ERROR, false"})
    void reportsOnlyARejectionThatAWaitCanRepairAsNotReady(int status, String code, Outcome outcome, boolean notReady) {
        product.rejectedKeys.put("catalog-v1.EU.seller.10", new FakeProductServer.Rejection(status, code));
        reader = reader(2, 2, 50, Duration.ofSeconds(30), Duration.ofSeconds(5));
        ReaderException failure = assertThrows(ReaderException.class, reader::resolve);
        assertEquals("Look up seller catalog-v1.EU.seller.10 failed with " + outcome, failure.getMessage());
        assertEquals(notReady, failure instanceof CatalogNotReadyException, failure.getClass().getName());
    }

    @Test
    void reportsALookupAfterItsDeadlineAsNotReady() {
        product.lookupDelayMillis = 2_000;
        reader = reader(1, 1, 50, Duration.ofSeconds(30), Duration.ofMillis(300));
        CatalogNotReadyException failure = assertThrows(CatalogNotReadyException.class, reader::resolve);
        assertEquals("Look up seller catalog-v1.US.seller.1 failed with DEADLINE", failure.getMessage());
    }

    @Test
    void reportsAnAbsentProductServiceAsNotReady() throws Exception {
        URI closed;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closed = URI.create("http://127.0.0.1:" + socket.getLocalPort());
        }
        requests = new ProductRequests(new ProductRequests.Settings(closed, 1, Duration.ofMillis(200),
                Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(10), 2, Duration.ofMillis(10)));
        metrics = new ReaderMetrics(System::nanoTime);
        reader = new ProductReader(new ReaderDatasets(CatalogDataset.VERSION), requests,
                new RatePacer(50, System::nanoTime, SLEEP), metrics, 1, Duration.ofSeconds(1), System::nanoTime, random);
        CatalogNotReadyException failure = assertThrows(CatalogNotReadyException.class, reader::resolve);
        assertEquals("Look up seller catalog-v1.US.seller.1 failed with TRANSPORT", failure.getMessage());
    }

    @Test
    void stopCancelsAWaitingResolution() throws Exception {
        product.lookupDelayMillis = 10_000;
        // A blocked socket read ignores interrupts; the read deadline ends it.
        reader = reader(2, 2, 50, Duration.ofSeconds(30), Duration.ofSeconds(1));
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread starter = Thread.ofPlatform().start(() -> {
            try {
                reader.resolve();
            } catch (Throwable failure) {
                outcome.set(failure);
            }
        });
        awaitUntil(() -> product.sellerLookups.get() == 2);
        reader.stop();
        starter.join(5_000);
        assertFalse(starter.isAlive());
        assertEquals("Catalog resolution was stopped",
                assertInstanceOf(InterruptedException.class, outcome.get()).getMessage());
        assertEquals(2, product.sellerLookups.get());
    }

    @Test
    void aStopRightAfterResolutionStartsNoLoad() throws Exception {
        reader = reader(2, 2, 50, Duration.ofSeconds(30), Duration.ofSeconds(5));
        Handler stopOnResolved = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getMessage().startsWith("Resolved ")) {
                    reader.stop();
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        log.addHandler(stopOnResolved);
        try {
            reader.start(reader.resolve(), failures::incrementAndGet);
        } finally {
            log.removeHandler(stopOnResolved);
        }
        assertEquals(0, product.reads.get());
        assertEquals(1, messages().size());
    }

    @Test
    void aFailedWorkerMarksTheReaderFailedAndRequestsExitOnce() throws Exception {
        reader = reader(2, 2, 50, Duration.ofMillis(200), Duration.ofSeconds(5));
        reader.start(reader.resolve(), failures::incrementAndGet);
        requests.close();
        awaitUntil(reader::failed);
        reader.stop();
        assertEquals(1, failures.get());
        // After a Quarkus test application in this JVM stops, Quarkus logging adds a suffix to ERROR messages.
        assertTrue(logs.stream().anyMatch(record -> record.getLevel().intValue() >= Level.SEVERE.intValue()
                && record.getMessage().startsWith("A product reader worker failed")), messages().toString());
    }

    @Test
    void doesNotHideAnUnexpectedLookupFailure() {
        reader = reader(1, 1, 50, Duration.ofSeconds(30), Duration.ofSeconds(5));
        requests.close();
        IllegalStateException failure = assertThrows(IllegalStateException.class, reader::resolve);
        assertEquals("A catalog lookup failed unexpectedly", failure.getMessage());
        assertInstanceOf(RuntimeException.class, failure.getCause());
    }

    @Test
    void startsOnceStopsOnceAndRestoresAnInterruptOfTheStoppingThread() throws Exception {
        reader = reader(1, 1, 50, Duration.ofSeconds(30), Duration.ofSeconds(5));
        ProductReader.Catalog catalog = reader.resolve();
        reader.start(catalog, failures::incrementAndGet);
        assertThrows(IllegalStateException.class, () -> reader.start(catalog, failures::incrementAndGet));
        assertThrows(IllegalStateException.class, reader::resolve);
        Thread.currentThread().interrupt();
        reader.stop();
        assertTrue(Thread.interrupted());
        int logged = logs.size();
        reader.stop();
        assertEquals(logged, logs.size());

        ProductReader unused = reader(1, 1, 50, Duration.ofSeconds(30), Duration.ofSeconds(5));
        assertThrows(IllegalArgumentException.class, () -> unused.start(catalog, failures::incrementAndGet));
        assertThrows(NullPointerException.class, () -> unused.start(null, failures::incrementAndGet));
        assertThrows(NullPointerException.class, () -> unused.start(catalog, null));
        unused.stop();
        assertEquals("Catalog resolution was stopped",
                assertThrows(InterruptedException.class, unused::resolve).getMessage());
    }

    @Test
    void rejectsInvalidWorkerConfiguration() {
        ProductReader valid = reader(2, 2, 50, Duration.ofSeconds(30), Duration.ofSeconds(5));
        valid.stop();
        ReaderDatasets datasets = new ReaderDatasets(CatalogDataset.VERSION);
        RatePacer pacer = new RatePacer(50, System::nanoTime, SLEEP);
        try (ProductRequests twoConnections = new ProductRequests(new ProductRequests.Settings(product.url(), 2,
                Duration.ofMillis(200), Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(10), 3,
                Duration.ofMillis(10)))) {
            assertThrows(IllegalArgumentException.class, () -> new ProductReader(datasets, twoConnections, pacer,
                    metrics, 1, Duration.ofSeconds(1), System::nanoTime, random));
            assertThrows(IllegalArgumentException.class, () -> new ProductReader(datasets, twoConnections, pacer,
                    metrics, 257, Duration.ofSeconds(1), System::nanoTime, random));
            assertThrows(IllegalArgumentException.class, () -> new ProductReader(datasets, twoConnections, pacer,
                    metrics, 2, Duration.ofMillis(999), System::nanoTime, random));
            assertThrows(NullPointerException.class, () -> new ProductReader(datasets, twoConnections, pacer,
                    metrics, 2, Duration.ofSeconds(1), null, random));
        }
    }

    private ProductReader reader(int connections, int workers, int rate, Duration lifetime, Duration deadline) {
        requests = new ProductRequests(new ProductRequests.Settings(product.url(), connections,
                Duration.ofMillis(200), deadline, lifetime, Duration.ofSeconds(10), 3, Duration.ofMillis(10)));
        metrics = new ReaderMetrics(System::nanoTime);
        return new ProductReader(new ReaderDatasets(CatalogDataset.VERSION), requests,
                new RatePacer(rate, System::nanoTime, SLEEP), metrics, workers, Duration.ofSeconds(1),
                System::nanoTime, random);
    }

    private long outcomes(Outcome outcome) {
        return metrics.total(NO_POOL).outcomes().getOrDefault(outcome, 0L);
    }

    private List<String> messages() {
        return logs.stream().map(record -> (record.getLevel().intValue() >= Level.WARNING.intValue() ? "WARN " : "INFO ")
                + record.getMessage()).collect(Collectors.toList());
    }

    private String expectedDigest() throws Exception {
        CatalogDataset catalog = new CatalogDataset(CatalogDataset.VERSION);
        StringBuilder mapping = new StringBuilder();
        catalog.sellers().forEach(seller -> mapping.append(seller.key()).append('=')
                .append(product.issued(seller.key())).append('\n'));
        catalog.products().forEach(item -> mapping.append(item.key()).append('=')
                .append(product.issued(item.key())).append('\n'));
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(mapping.toString().getBytes(StandardCharsets.UTF_8));
        return String.format("%064x", new BigInteger(1, digest));
    }

    private static void awaitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() - deadline < 0, "Condition not reached within 10 seconds");
            Thread.sleep(10);
        }
    }
}
