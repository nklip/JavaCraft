package dev.nklip.javacraft.shardshop.order.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/** CDC credentials are separate from the application login used for status and offset writes. */
@ConfigMapping(prefix = "shardshop.order.cdc")
public interface CdcConfiguration {
    @WithDefault("true")
    boolean enabled();

    Map<String, Connection> connections();

    Broker broker();

    interface Connection {
        @WithDefault("ordering_cdc")
        String username();

        Optional<String> password();
    }

    interface Broker {
        @WithDefault("shardshop-rabbitmq")
        String host();

        @WithDefault("5672")
        int port();

        @WithDefault("shardshop")
        String virtualHost();

        @WithDefault("shardshop-order")
        String username();

        Optional<String> password();

        @WithDefault("5s")
        Duration timeout();
    }
}
