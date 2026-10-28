package dev.nklip.javacraft.shardshop.workload.reader.config;

import dev.nklip.javacraft.shardshop.workload.control.WorkloadControl;
import dev.nklip.javacraft.shardshop.workload.reader.catalog.ReaderDatasets;
import dev.nklip.javacraft.shardshop.workload.reader.http.ProductRequests;
import dev.nklip.javacraft.shardshop.workload.reader.load.ProductReader;
import dev.nklip.javacraft.shardshop.workload.reader.metrics.ReaderMetrics;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

@Singleton
public class ReaderWiring {
    /** Product returns 503 within its four-second server deadline; a shorter reader deadline would hide it. */
    static final Duration MIN_REQUEST_DEADLINE = Duration.ofSeconds(5);
    static final BigDecimal MAX_REQUEST_RATE = new BigDecimal("10000");

    @Produces
    @Singleton
    WorkloadControl control(ReaderConfiguration config) {
        try {
            return new WorkloadControl(new WorkloadControl.Settings("product-reader", "product read starts/s",
                    config.requestRate(), MAX_REQUEST_RATE, BigDecimal.TEN, config.workers(),
                    List.of(ProductReader.READS)), WorkloadControl.Phase.WAITING);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("shardshop.reader.request-rate must be 0..10000 with at most three decimals");
        }
    }

    @Produces
    @Singleton
    ProductReader reader(ReaderConfiguration config, WorkloadControl control) {
        if (config.requestDeadline().compareTo(MIN_REQUEST_DEADLINE) < 0) {
            throw new IllegalArgumentException("shardshop.reader.request-deadline must be at least 5s");
        }
        // ThreadLocalRandom.current() belongs to the calling thread, thus each worker uses its own generator.
        RandomGenerator random = () -> ThreadLocalRandom.current().nextLong();
        // A repeated attempt follows a failed attempt without an HTTP response.
        Runnable retried = () -> {
            control.traffic().failed(ProductReader.READS);
            control.traffic().sent(ProductReader.READS, true);
        };
        ProductRequests requests = new ProductRequests(new ProductRequests.Settings(config.productUrl(),
                config.connections(), config.connectTimeout(), config.requestDeadline(),
                config.maxConnectionLifetime(), config.idleTimeout(), config.maxAttempts(), config.retryInterval()),
                retried);
        return new ProductReader(new ReaderDatasets(config.catalogVersion()), requests, control,
                new ReaderMetrics(System::nanoTime, config.diagnostics().enabled()), config.workers(), config.reportInterval(), System::nanoTime, random);
    }

    void stop(@Disposes ProductReader reader) {
        reader.stop();
    }
}
