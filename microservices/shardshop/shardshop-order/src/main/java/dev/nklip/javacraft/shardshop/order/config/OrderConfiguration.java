package dev.nklip.javacraft.shardshop.order.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

@ConfigMapping(prefix = "shardshop.order")
public interface OrderConfiguration {
    @WithDefault("4")
    int maxPoolSize();

    @WithDefault("1024")
    int maxIdCandidates();

    @WithDefault("4")
    int catalogConcurrency();

    /** Two to three seconds: allows bounded acquisition and fits the HTTP request ceiling. */
    @WithDefault("3s")
    Duration catalogDeadline();

    Stock stock();

    interface Stock {
        @WithDefault("true")
        boolean enabled();

        @WithDefault("250ms")
        Duration pollInterval();

        @WithDefault("30s")
        Duration lease();

        @WithDefault("8")
        int batchSize();
    }

    Map<String, Connection> connections();

    interface Connection {
        Optional<String> primaryUrl();

        Optional<String> rootCertificate();

        @WithDefault("order_app")
        String username();

        Optional<String> password();
    }
}
