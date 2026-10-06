package dev.nklip.javacraft.shardshop.order;

import dev.nklip.javacraft.shardshop.sharding.ShardRouter;
import dev.nklip.javacraft.shardshop.sharding.ShardTopology;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Singleton
class ShardRoutingConfiguration {

    @Produces
    @Singleton
    @Startup
    ShardTopology shardTopology(@ConfigProperty(name = "shardshop.routing.shards") String shards,
                                @ConfigProperty(name = "shardshop.routing.regions") String regions) {
        return ShardTopology.fromNamesAndRegions(shards, regions);
    }

    @Produces
    @Singleton
    @Startup
    ShardRouter shardRouter(ShardTopology topology) {
        return new ShardRouter(topology);
    }
}
