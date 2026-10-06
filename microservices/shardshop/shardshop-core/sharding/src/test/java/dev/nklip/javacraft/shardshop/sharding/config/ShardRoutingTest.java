package dev.nklip.javacraft.shardshop.sharding.config;

import dev.nklip.javacraft.shardshop.sharding.Shard;
import dev.nklip.javacraft.shardshop.sharding.ShardRouter;
import dev.nklip.javacraft.shardshop.sharding.ShardTopology;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class ShardRoutingTest {

    @Inject
    RoutingConfiguration configuration;

    @Inject
    ShardTopology topology;

    @Inject
    ShardRouter router;

    @Test
    void injectsTheConfiguredImmutableTopologyAndRouter() {
        assertEquals(Optional.empty(), configuration.config());
        assertEquals("shard-b,shard-a", configuration.shards());
        assertEquals("EU,US", configuration.regions());
        assertEquals(List.of(new Shard("shard-b", "EU"), new Shard("shard-a", "US")), topology.shards());
        assertEquals(new Shard("shard-a", "US"), router.route(1L));
    }
}
