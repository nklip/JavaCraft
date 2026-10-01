package dev.nklip.javacraft.shardshop.product;

import dev.nklip.javacraft.shardshop.sharding.ShardRouter;
import dev.nklip.javacraft.shardshop.sharding.ShardTopology;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
class ShardRoutingConfiguration {

    @Bean
    ShardTopology shardTopology(Environment environment) {
        return ShardTopology.fromNamesAndRegions(environment.getRequiredProperty("shardshop.routing.shards"),
                environment.getRequiredProperty("shardshop.routing.regions"));
    }

    @Bean
    ShardRouter shardRouter(ShardTopology topology) {
        return new ShardRouter(topology);
    }
}
