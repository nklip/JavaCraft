package dev.nklip.javacraft.shardshop.sharding.config;

import io.smallrye.config.ConfigMapping;

import java.util.Optional;

@ConfigMapping(prefix = "shardshop.routing")
public interface RoutingConfiguration {

    // Optional snapshot location consumed by quarkus.config.locations.
    Optional<String> config();

    String shards();

    String regions();
}
