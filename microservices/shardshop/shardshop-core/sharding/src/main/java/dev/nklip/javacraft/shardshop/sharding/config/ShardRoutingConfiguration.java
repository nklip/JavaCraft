package dev.nklip.javacraft.shardshop.sharding.config;

import dev.nklip.javacraft.shardshop.sharding.ShardRouter;
import dev.nklip.javacraft.shardshop.sharding.ShardTopology;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

@Singleton
class ShardRoutingConfiguration {

    @Produces
    @Singleton
    @Startup
    ShardTopology shardTopology(RoutingConfiguration configuration) {
        return ShardTopology.fromNamesAndRegions(configuration.shards(), configuration.regions());
    }

    @Produces
    @Singleton
    @Startup
    ShardRouter shardRouter(ShardTopology topology) {
        return new ShardRouter(topology);
    }
}
