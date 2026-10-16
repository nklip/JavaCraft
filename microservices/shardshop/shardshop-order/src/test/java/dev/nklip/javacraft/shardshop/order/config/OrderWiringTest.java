package dev.nklip.javacraft.shardshop.order.config;

import dev.nklip.javacraft.shardshop.common.jdbc.Endpoint;
import dev.nklip.javacraft.shardshop.idgen.IdGenerator;
import dev.nklip.javacraft.shardshop.order.persistence.JdbcOrderStore;
import dev.nklip.javacraft.shardshop.sharding.ShardRouter;
import dev.nklip.javacraft.shardshop.sharding.ShardTopology;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderWiringTest {
    private final OrderConfiguration config = mock(OrderConfiguration.class);
    private final ShardTopology topology = ShardTopology.fromNamesAndRegions("a,b", "US,EU");
    private final OrderWiring wiring = new OrderWiring();

    @Test
    void defaultsUseTopologyServiceNamesAndBoundedPools() {
        when(config.maxPoolSize()).thenReturn(4);
        try (var construction = mockConstruction(JdbcOrderStore.class, (store, context) -> {
            assertEquals(Map.of("a", endpoint("a-rw"), "b", endpoint("b-rw")), context.arguments().get(0));
            assertEquals(4, context.arguments().get(1));
        })) {
            JdbcOrderStore store = wiring.store(config, topology);
            assertSame(construction.constructed().getFirst(), store);
            wiring.close(store);
            verify(store).close();
        }
    }

    @Test
    void endpointOverridesSupportSeparateCredentialsForEveryShard() {
        var first = mock(OrderConfiguration.Connection.class);
        when(first.username()).thenReturn("custom");
        when(first.password()).thenReturn(Optional.of("test-secret"));
        when(first.primaryUrl()).thenReturn(Optional.of("jdbc:postgresql://localhost/primary"));
        var second = mock(OrderConfiguration.Connection.class);
        when(second.username()).thenReturn("order_app");
        when(config.connections()).thenReturn(Map.of("a", first, "b", second));
        try (var construction = mockConstruction(JdbcOrderStore.class, (store, context) -> {
            assertEquals(Map.of("a", new Endpoint(secured("jdbc:postgresql://localhost/primary", "a"), "custom", "test-secret"),
                    "b", endpoint("b-rw")), context.arguments().get(0));
        })) {
            JdbcOrderStore store = wiring.store(config, topology);
            assertSame(construction.constructed().getFirst(), store);
        }
    }

    @Test
    void unknownConnectionNamesFailBeforeConstructingPools() {
        when(config.connections()).thenReturn(Map.of("typo", mock(OrderConfiguration.Connection.class)));
        assertThrows(IllegalArgumentException.class, () -> wiring.store(config, topology));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT2S", "PT2.5S", "PT3S"})
    void acceptsProductionDeadlinesThatFitAcquisitionAndTheHttpCeiling(String deadline) {
        when(config.maxIdCandidates()).thenReturn(1024);
        when(config.catalogConcurrency()).thenReturn(4);
        when(config.catalogDeadline()).thenReturn(Duration.parse(deadline));
        assertNotNull(wiring.service(mock(JdbcOrderStore.class), wiring.settings(config), mock(IdGenerator.class),
                new ShardRouter(topology), topology));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT-1S", "PT0S", "PT1S", "PT1.999999999S", "PT3.000000001S", "PT1M"})
    void rejectsDeadlinesThatCannotFitAcquisitionOrTheHttpCeiling(String deadline) {
        when(config.catalogDeadline()).thenReturn(Duration.parse(deadline));
        assertEquals("Order catalog deadline must be between 2s and 3s",
                assertThrows(IllegalArgumentException.class, () -> wiring.settings(config)).getMessage());
    }

    private Endpoint endpoint(String host) {
        return new Endpoint(secured("jdbc:postgresql://" + host + ":5432/shardshop", host.substring(0, 1)), "order_app", "");
    }

    private String secured(String url, String shard) {
        return url + "?sslmode=verify-full&sslrootcert=%2Fetc%2Fshardshop%2Forder%2F" + shard + "%2Fca.crt";
    }
}
