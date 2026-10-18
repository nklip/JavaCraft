package dev.nklip.javacraft.shardshop.order.config;

import dev.nklip.javacraft.shardshop.order.cdc.OrderCdcRelay;
import dev.nklip.javacraft.shardshop.order.cdc.RabbitCommandPublisher;
import dev.nklip.javacraft.shardshop.sharding.ShardTopology;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CdcWiringTest {
    private final CdcConfiguration config = mock(CdcConfiguration.class);
    private final OrderConfiguration order = mock(OrderConfiguration.class);
    private final ShardTopology topology = ShardTopology.fromNamesAndRegions("shard-a,shard-b", "US,EU");
    private final CdcWiring wiring = new CdcWiring();

    @BeforeEach
    void broker() {
        var broker = mock(CdcConfiguration.Broker.class);
        when(config.broker()).thenReturn(broker);
        when(broker.host()).thenReturn("broker");
        when(broker.port()).thenReturn(5672);
        when(broker.virtualHost()).thenReturn("shardshop");
        when(broker.username()).thenReturn("shardshop-order");
        when(broker.password()).thenReturn(Optional.of("broker-test-secret"));
        when(broker.timeout()).thenReturn(Duration.ofSeconds(5));
    }

    @Test
    void eachReaderUsesItsPrimaryTlsEndpointWithSeparateCredentials() {
        when(config.enabled()).thenReturn(true);
        var application = mock(OrderConfiguration.Connection.class);
        when(application.primaryUrl()).thenReturn(Optional.of("jdbc:postgresql://localhost:5544/shard_a"));
        when(application.rootCertificate()).thenReturn(Optional.of("/test/ca.crt"));
        when(application.username()).thenReturn("order_app");
        when(application.password()).thenReturn(Optional.of("app-test-secret"));
        when(order.connections()).thenReturn(Map.of("shard-a", application));
        var connections = Map.of("shard-a", replication("cdc-test-secret"),
                "shard-b", replication("cdc-test-secret-b"));
        when(config.connections()).thenReturn(connections);
        try (var construction = mockConstruction(OrderCdcRelay.class, (relay, context) -> {
            @SuppressWarnings("unchecked")
            var shards = (List<OrderCdcRelay.ShardSettings>) context.arguments().getFirst();
            assertEquals(List.of("shard-a", "shard-b"), shards.stream().map(OrderCdcRelay.ShardSettings::shard).toList());
            var first = shards.getFirst();
            assertEquals("jdbc:postgresql://localhost:5544/shard_a?sslmode=verify-full&sslrootcert=%2Ftest%2Fca.crt",
                    first.application().jdbcUrl());
            assertEquals(first.application().jdbcUrl(), first.replication().jdbcUrl());
            assertEquals("order_app", first.application().username());
            assertEquals("app-test-secret", first.application().password());
            assertEquals("ordering_cdc", first.replication().username());
            assertEquals("cdc-test-secret", first.replication().password());
            assertEquals("ordering_cdc", shards.get(1).replication().username());
            assertEquals("cdc-test-secret-b", shards.get(1).replication().password());
            assertEquals(new RabbitCommandPublisher.Settings("broker", 5672, "shardshop", "shardshop-order",
                    "broker-test-secret", Duration.ofSeconds(5)), context.arguments().get(1));
        })) {
            wiring.relay(config, order, topology);
            assertEquals(1, construction.constructed().size());
        }
    }

    /** A pod without its CDC Secret fails at startup, before it reserves a generator ID, and never retries silently. */
    @Test
    void enabledRelayRequiresAReplicationPasswordForEveryShard() {
        when(config.enabled()).thenReturn(true);
        var missing = Map.of("shard-a", replication("cdc-test-secret"));
        when(config.connections()).thenReturn(missing);
        assertEquals("CDC is enabled but shardshop.order.cdc.connections.shard-b.password is not set",
                assertThrows(IllegalArgumentException.class, () -> wiring.relay(config, order, topology)).getMessage());

        var blank = Map.of("shard-a", replication("cdc-test-secret"), "shard-b", replication("   "));
        when(config.connections()).thenReturn(blank);
        assertEquals("CDC is enabled but shardshop.order.cdc.connections.shard-b.password is not set",
                assertThrows(IllegalArgumentException.class, () -> wiring.relay(config, order, topology)).getMessage());
    }

    @Test
    void enabledRelayRequiresTheBrokerPassword() {
        when(config.enabled()).thenReturn(true);
        var connections = Map.of("shard-a", replication("cdc-test-secret"),
                "shard-b", replication("cdc-test-secret-b"));
        when(config.connections()).thenReturn(connections);
        when(config.broker().password()).thenReturn(Optional.empty());
        assertEquals("CDC is enabled but shardshop.order.cdc.broker.password is not set",
                assertThrows(IllegalArgumentException.class, () -> wiring.relay(config, order, topology)).getMessage());
    }

    @Test
    void disabledRelayHasNoReadersAndNeedsNoCredentials() {
        when(config.broker().password()).thenReturn(Optional.empty());
        try (var construction = mockConstruction(OrderCdcRelay.class, (relay, context) ->
                assertEquals(List.of(), context.arguments().getFirst()))) {
            wiring.relay(config, order, topology);
            assertEquals(1, construction.constructed().size());
        }
    }

    private static CdcConfiguration.Connection replication(String password) {
        var replication = mock(CdcConfiguration.Connection.class);
        when(replication.username()).thenReturn("ordering_cdc");
        when(replication.password()).thenReturn(Optional.of(password));
        return replication;
    }

    @Test
    void unknownCdcShardFailsConfiguration() {
        when(config.connections()).thenReturn(Map.of("typo", mock(CdcConfiguration.Connection.class)));
        assertEquals("CDC connections must name only configured routing shards",
                assertThrows(IllegalArgumentException.class, () -> wiring.relay(config, order, topology)).getMessage());
    }
}
