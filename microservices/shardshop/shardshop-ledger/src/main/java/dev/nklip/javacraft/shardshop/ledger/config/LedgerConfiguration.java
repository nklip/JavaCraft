package dev.nklip.javacraft.shardshop.ledger.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

@ConfigMapping(prefix = "shardshop.ledger")
public interface LedgerConfiguration {
    @WithDefault("true") boolean enabled();
    @WithDefault("4") int maxPoolSize();
    @WithDefault("CNY,EUR,GBP,JPY,KRW,USD,VND") Set<String> allowedCurrencies();
    Database database();
    Broker broker();
    Publisher publisher();
    Cleanup cleanup();

    interface Cleanup {
        @WithDefault("true") boolean enabled();
        @WithDefault("24h") Duration retention();
        @WithDefault("30s") Duration pollInterval();
        @WithDefault("1000") int batchSize();
    }

    interface Publisher {
        @WithDefault("true") boolean enabled();
        @WithDefault("100") int batchSize();
        @WithDefault("500ms") Duration pollInterval();
    }

    interface Database {
        /** The read-write Service of the CNPG cluster ledger-db. */
        @WithDefault("jdbc:postgresql://ledger-db-rw:5432/ledger") String url();
        @WithDefault("ledger_app") String username();
        Optional<String> password();
        @WithDefault("/etc/shardshop/ledger/ca.crt") String rootCertificate();
    }

    interface Broker {
        @WithDefault("shardshop-rabbitmq") String host();
        @WithDefault("5672") int port();
        @WithDefault("shardshop") String virtualHost();
        @WithDefault("shardshop-ledger") String username();
        Optional<String> password();
        @WithDefault("5s") Duration timeout();
    }
}
