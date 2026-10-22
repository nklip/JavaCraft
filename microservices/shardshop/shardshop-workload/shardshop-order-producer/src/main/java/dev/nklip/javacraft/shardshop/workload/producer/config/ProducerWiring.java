package dev.nklip.javacraft.shardshop.workload.producer.config;

import dev.nklip.javacraft.shardshop.common.JsonHttpClient;
import dev.nklip.javacraft.shardshop.workload.control.WorkloadControl;
import dev.nklip.javacraft.shardshop.workload.producer.ProducerDatasets;
import dev.nklip.javacraft.shardshop.workload.producer.http.OrderRequests;
import dev.nklip.javacraft.shardshop.workload.producer.load.OrderProducer;
import dev.nklip.javacraft.shardshop.workload.producer.load.OrderWorkload;
import dev.nklip.javacraft.shardshop.workload.producer.load.RetryPolicy;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

@Singleton
public class ProducerWiring {
    /** The producer that this wiring created; null when its configuration failed. */
    private volatile OrderProducer created;

    @Produces
    @Singleton
    HttpClient http(ProducerConfiguration config) {
        OrderProducer.Settings.positiveBounded(config.connectTimeout(), Duration.ofSeconds(30));
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(config.connectTimeout()).build();
    }

    @Produces
    @Singleton
    WorkloadControl control(ProducerConfiguration config) {
        try {
            return new WorkloadControl(OrderProducer.controlSettings(config.orderRate()), WorkloadControl.Phase.WAITING);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("shardshop.producer.order-rate must be 0..100 with at most three decimals");
        }
    }

    @Produces
    @Singleton
    OrderProducer producer(ProducerConfiguration config, HttpClient http, WorkloadControl control) {
        var settings = new OrderProducer.Settings(config.runName(), config.rejectedOrderPercent(),
                config.orderDeadline(), config.startupDeadline());
        OrderProducer.Settings.positiveBounded(config.requestTimeout(), Duration.ofMinutes(1));
        OrderProducer.Settings.positiveBounded(config.pollInterval(), Duration.ofSeconds(30));
        JsonHttpClient product = new JsonHttpClient(http, config.productUrl(), config.requestTimeout());
        JsonHttpClient order = new JsonHttpClient(http, config.orderUrl(), config.requestTimeout());
        DoubleSupplier random = () -> ThreadLocalRandom.current().nextDouble();
        var retry = new RetryPolicy(config.maxAttempts(), config.retryBackoff(), config.maxRetryBackoff(),
                Thread::sleep, random, control.traffic());
        var workload = new OrderWorkload(new ProducerDatasets(product, config.catalogVersion(), order,
                config.buyerVersion()), new OrderRequests(order), retry, Thread::sleep, random, config.pollInterval());
        created = new OrderProducer(workload, settings, control);
        return created;
    }

    /**
     * Stops HTTP work before the container closes the HTTP client. This observer creates no bean, thus a failed
     * configuration is not created again during shutdown and logs its error once.
     */
    void stopBeforeDisposal(@Observes ShutdownEvent event) {
        OrderProducer producer = created;
        if (producer != null) {
            producer.stop();
        }
    }

    void stop(@Disposes OrderProducer producer) {
        producer.stop();
    }

    void close(@Disposes HttpClient http) {
        http.shutdownNow();
    }
}
