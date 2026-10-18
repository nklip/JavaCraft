package dev.nklip.javacraft.shardshop.ledger.config;

import dev.nklip.javacraft.shardshop.common.jdbc.Endpoint;
import dev.nklip.javacraft.shardshop.common.jdbc.JdbcUrls;
import dev.nklip.javacraft.shardshop.ledger.command.LedgerService;
import dev.nklip.javacraft.shardshop.ledger.messaging.LedgerCommandConsumer;
import dev.nklip.javacraft.shardshop.ledger.persistence.JdbcLedgerStore;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import java.time.Clock;

@Singleton
public class LedgerWiring {
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
        var broker = config.broker();
        return new LedgerCommandConsumer(new LedgerCommandConsumer.Settings(config.enabled(), broker.host(),
                broker.port(), broker.virtualHost(), broker.username(), broker.password().orElse(""),
                broker.timeout()), service::handle);
    }

    void closeConsumer(@Disposes LedgerCommandConsumer consumer) {
        consumer.close();
    }

    void closeStore(@Disposes JdbcLedgerStore store) {
        store.close();
    }
}
