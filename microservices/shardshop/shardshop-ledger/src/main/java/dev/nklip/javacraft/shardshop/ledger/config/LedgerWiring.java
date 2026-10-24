package dev.nklip.javacraft.shardshop.ledger.config;

import dev.nklip.javacraft.shardshop.common.amqp.BrokerSettings;
import dev.nklip.javacraft.shardshop.common.amqp.ConfirmedPublisher;
import dev.nklip.javacraft.shardshop.common.jdbc.Endpoint;
import dev.nklip.javacraft.shardshop.common.jdbc.JdbcUrls;
import dev.nklip.javacraft.shardshop.ledger.command.LedgerService;
import dev.nklip.javacraft.shardshop.ledger.cleanup.LedgerTransportCleanup;
import dev.nklip.javacraft.shardshop.ledger.messaging.LedgerCommandConsumer;
import dev.nklip.javacraft.shardshop.ledger.metrics.LedgerMetrics;
import dev.nklip.javacraft.shardshop.ledger.persistence.JdbcLedgerStore;
import dev.nklip.javacraft.shardshop.ledger.result.LedgerResultRelay;
import io.quarkus.runtime.Startup;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import java.time.Clock;

@Singleton
public class LedgerWiring {
    /** Results route to order's quorum queue order.ledger-results. */
    private static final ConfirmedPublisher.Destination RESULTS = new ConfirmedPublisher.Destination(
            "order.saga-results", "order.ledger-results", "result", "ledger-result-publisher");

    @Produces
    @Singleton
    JdbcLedgerStore store(LedgerConfiguration config) {
        var database = config.database();
        if (config.enabled()) {
            if (database.password().orElse("").isBlank()) {
                throw new IllegalArgumentException("Ledger is enabled but shardshop.ledger.database.password is not set");
            }
            if (config.broker().password().orElse("").isBlank()) {
                throw new IllegalArgumentException("Ledger is enabled but shardshop.ledger.broker.password is not set");
            }
        }
        return new JdbcLedgerStore(new Endpoint(JdbcUrls.secure(database.url(), database.rootCertificate()),
                database.username(), database.password().orElse("")), config.maxPoolSize());
    }

    @Produces
    @Singleton
    LedgerService service(JdbcLedgerStore store, LedgerConfiguration config) {
        return new LedgerService(store, config.allowedCurrencies(), Clock.systemUTC());
    }

    @Produces
    @Singleton
    @Startup
    LedgerCommandConsumer consumer(LedgerService service, LedgerConfiguration config) {
        return new LedgerCommandConsumer(new LedgerCommandConsumer.Settings(config.enabled(), broker(config)),
                service::handle);
    }

    void closeConsumer(@Disposes LedgerCommandConsumer consumer) {
        consumer.close();
    }

    @Produces
    @Singleton
    @Startup
    LedgerResultRelay relay(JdbcLedgerStore store, LedgerConfiguration config) {
        var publisher = config.publisher();
        var connection = broker(config);
        return new LedgerResultRelay(store, () -> new ConfirmedPublisher(connection, RESULTS),
                new LedgerResultRelay.Settings(config.enabled() && publisher.enabled(), publisher.batchSize(),
                        publisher.pollInterval()));
    }

    void closeRelay(@Disposes LedgerResultRelay relay) {
        relay.close();
    }

    @Produces
    @Singleton
    @Startup
    LedgerTransportCleanup cleanup(JdbcLedgerStore store, LedgerConfiguration config) {
        var cleanup = config.cleanup();
        return new LedgerTransportCleanup(store,
                new LedgerTransportCleanup.Settings(config.enabled() && cleanup.enabled(), cleanup.retention(),
                        cleanup.pollInterval(), cleanup.batchSize()), Clock.systemUTC());
    }

    void closeCleanup(@Disposes LedgerTransportCleanup cleanup) {
        cleanup.close();
    }

    @Produces
    @Singleton
    @Startup
    LedgerMetrics metrics(JdbcLedgerStore store, LedgerConfiguration config, MeterRegistry registry) {
        return new LedgerMetrics(store, registry, Clock.systemUTC(), config.enabled());
    }

    void closeMetrics(@Disposes LedgerMetrics metrics) {
        metrics.close();
    }

    void closeStore(@Disposes JdbcLedgerStore store) {
        store.close();
    }

    /** The consumer and the result publisher use the same broker connection settings. */
    private static BrokerSettings broker(LedgerConfiguration config) {
        var broker = config.broker();
        return new BrokerSettings(broker.host(), broker.port(), broker.virtualHost(), broker.username(),
                broker.password().orElse(""), broker.timeout());
    }
}
