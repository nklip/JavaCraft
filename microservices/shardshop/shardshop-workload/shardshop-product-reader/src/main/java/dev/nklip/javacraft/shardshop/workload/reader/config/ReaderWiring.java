package dev.nklip.javacraft.shardshop.workload.reader.config;

import dev.nklip.javacraft.shardshop.workload.reader.catalog.ReaderDatasets;
import dev.nklip.javacraft.shardshop.workload.reader.http.ProductRequests;
import dev.nklip.javacraft.shardshop.workload.reader.load.Pause;
import dev.nklip.javacraft.shardshop.workload.reader.load.ProductReader;
import dev.nklip.javacraft.shardshop.workload.reader.load.RatePacer;
import dev.nklip.javacraft.shardshop.workload.reader.metrics.ReaderMetrics;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGenerator;

@Singleton
public class ReaderWiring {
    /** Product returns 503 within its four-second server deadline; a shorter reader deadline would hide it. */
    static final Duration MIN_REQUEST_DEADLINE = Duration.ofSeconds(5);

    @Produces
    @Singleton
    ProductReader reader(ReaderConfiguration config) {
        if (config.requestDeadline().compareTo(MIN_REQUEST_DEADLINE) < 0) {
            throw new IllegalArgumentException("shardshop.reader.request-deadline must be at least 5s");
        }
        // ThreadLocalRandom.current() belongs to the calling thread, thus each worker uses its own generator.
        RandomGenerator random = () -> ThreadLocalRandom.current().nextLong();
        Pause pause = TimeUnit.NANOSECONDS::sleep;
        ProductRequests requests = new ProductRequests(new ProductRequests.Settings(config.productUrl(),
                config.connections(), config.connectTimeout(), config.requestDeadline(),
                config.maxConnectionLifetime(), config.idleTimeout(), config.maxAttempts(), config.retryInterval()));
        return new ProductReader(new ReaderDatasets(config.catalogVersion()), requests,
                new RatePacer(config.requestRate(), System::nanoTime, pause), new ReaderMetrics(System::nanoTime),
                config.workers(), config.reportInterval(), System::nanoTime, random);
    }

    void stop(@Disposes ProductReader reader) {
        reader.stop();
    }
}
