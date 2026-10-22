package dev.nklip.javacraft.shardshop.workload.reader.load;

import dev.nklip.javacraft.shardshop.workload.control.WorkloadControl;
import dev.nklip.javacraft.shardshop.workload.dataset.CatalogDataset;
import dev.nklip.javacraft.shardshop.workload.reader.catalog.ReaderDatasets;
import dev.nklip.javacraft.shardshop.workload.reader.config.CatalogWait;
import dev.nklip.javacraft.shardshop.workload.reader.http.ProductRequests;
import dev.nklip.javacraft.shardshop.workload.reader.metrics.ReaderMetrics;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The start condition of the reader through the SmallRye Fault Tolerance interceptor of {@link CatalogWait}, with a
 * 2-second maximum wait and a backoff from 50 to 200 ms. The test is in this package to use {@link FakeProductServer}.
 */
@QuarkusTest
@TestProfile(CatalogWaitTest.ShortWait.class)
class CatalogWaitTest {
    private static final String MISSING = "catalog-v2.ASIA.product.7";

    @Inject
    CatalogWait catalogWait;

    private FakeProductServer product;
    private ProductReader reader;

    @BeforeEach
    void startProduct() throws Exception {
        product = new FakeProductServer();
        ProductRequests requests = new ProductRequests(new ProductRequests.Settings(product.url(), 2,
                Duration.ofMillis(200), Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(10), 3,
                Duration.ofMillis(10)));
        reader = new ProductReader(new ReaderDatasets(CatalogDataset.VERSION), requests,
                new WorkloadControl(new WorkloadControl.Settings("product-reader", "product read starts/s",
                        new BigDecimal("50"), new BigDecimal("10000"), BigDecimal.TEN, 2, List.of(ProductReader.READS)),
                        WorkloadControl.Phase.WAITING), new ReaderMetrics(System::nanoTime),
                2, Duration.ofSeconds(1), System::nanoTime, new Random(7)::nextLong);
    }

    @AfterEach
    void stopEverything() {
        reader.stop();
        product.close();
    }

    @Test
    void sendsNoLoadWhileAnEntityIsMissingAndStartsAfterItAppears() throws Exception {
        product.missingKeys.add(MISSING);
        CompletableFuture<ProductReader.Catalog> waiting = CompletableFuture.supplyAsync(() -> {
            try {
                return catalogWait.resolve(reader);
            } catch (Exception failure) {
                throw new CompletionException(failure);
            }
        });
        // Each resolution looks up all 151 sellers before the missing product stops it.
        awaitUntil(() -> product.sellerLookups.get() >= 2 * 151);
        assertFalse(waiting.isDone());
        assertEquals(0, product.reads.get());

        product.missingKeys.remove(MISSING);
        ProductReader.Catalog catalog = waiting.get(5, TimeUnit.SECONDS);
        assertEquals(0, product.reads.get());
        reader.start(catalog, () -> { });
        awaitUntil(() -> product.reads.get() > 0);
    }

    @Test
    void stopsWithTheLastFailureAfterTheMaximumWait() {
        product.missingKeys.add(MISSING);
        long started = System.nanoTime();
        CatalogNotReadyException failure = assertThrows(CatalogNotReadyException.class,
                () -> catalogWait.resolve(reader));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertEquals("Look up product " + MISSING + " failed with NOT_FOUND", failure.getMessage());
        assertTrue(elapsedMillis >= 1_500 && elapsedMillis < 10_000, elapsedMillis + " ms");
        assertTrue(product.sellerLookups.get() >= 3 * 151, product.sellerLookups.get() + " seller lookups");
        assertEquals(0, product.reads.get());
    }

    @Test
    void stopsAtOnceWhenAWaitCannotRepairTheCatalog() {
        product.conflictingKeys.add("catalog-v2.US.seller.3");
        ReaderException failure = assertThrows(ReaderException.class, () -> catalogWait.resolve(reader));
        assertFalse(failure instanceof CatalogNotReadyException, failure.getClass().getName());
        // One resolution only: it stops at the first failure.
        assertTrue(product.sellerLookups.get() <= 151, product.sellerLookups.get() + " seller lookups");
    }

    public static class ShortWait implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            String method = CatalogWait.class.getName() + "/resolve/";
            return Map.of(method + "Retry/maxDuration", "2000", method + "Retry/delay", "50",
                    method + "Retry/jitter", "0", method + "ExponentialBackoff/maxDelay", "200");
        }
    }

    private static void awaitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() - deadline < 0, "Condition not reached within 10 seconds");
            Thread.sleep(10);
        }
    }
}
