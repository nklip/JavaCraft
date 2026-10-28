package dev.nklip.javacraft.shardshop.product.config;

import dev.nklip.javacraft.shardshop.common.jdbc.Endpoint;
import dev.nklip.javacraft.shardshop.idgen.IdGenerator;
import dev.nklip.javacraft.shardshop.product.persistence.JdbcCatalogStore;
import dev.nklip.javacraft.shardshop.sharding.ShardRouter;
import dev.nklip.javacraft.shardshop.sharding.ShardTopology;
import org.junit.jupiter.api.Test;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CatalogWiringTest {
    private final CatalogConfiguration config = mock(CatalogConfiguration.class);
    private final ShardTopology topology = ShardTopology.fromNamesAndRegions("a,b", "US,EU");
    private final CatalogWiring wiring = new CatalogWiring();

    @Test
    void defaultsUseTopologyServiceNamesAndBoundedPools() {
        when(config.maxPoolSize()).thenReturn(4);
        try (var construction = mockConstruction(JdbcCatalogStore.class, (store, context) -> {
            assertEquals(Map.of("a", endpoint("a-rw"), "b", endpoint("b-rw")), context.arguments().get(0));
            assertEquals(Map.of("a", endpoint("a-ro"), "b", endpoint("b-ro")), context.arguments().get(1));
            assertEquals(4, context.arguments().get(2));
            assertNull(context.arguments().get(3));
        })) {
            JdbcCatalogStore store = wiring.store(config, topology, new SimpleMeterRegistry());
            assertSame(construction.constructed().getFirst(), store);
            wiring.close(store);
            verify(store).close();
        }
    }

    @Test
    void endpointOverridesSupportSeparateCredentialsForEveryShard() {
        var first = mock(CatalogConfiguration.Connection.class);
        when(first.username()).thenReturn("custom");
        when(first.password()).thenReturn(Optional.of("test-secret"));
        when(first.primaryUrl()).thenReturn(Optional.of("jdbc:postgresql://localhost/primary"));
        when(first.replicaUrl()).thenReturn(Optional.of("jdbc:postgresql://localhost/replica"));
        var second = mock(CatalogConfiguration.Connection.class);
        when(second.username()).thenReturn("product_app");
        when(config.connections()).thenReturn(Map.of("a", first, "b", second));
        try (var construction = mockConstruction(JdbcCatalogStore.class, (store, context) -> {
            assertEquals(Map.of("a", new Endpoint(secured("jdbc:postgresql://localhost/primary", "a"), "custom", "test-secret"),
                    "b", endpoint("b-rw")), context.arguments().get(0));
            assertEquals(Map.of("a", new Endpoint(secured("jdbc:postgresql://localhost/replica", "a"), "custom", "test-secret"),
                    "b", endpoint("b-ro")), context.arguments().get(1));
        })) {
            JdbcCatalogStore store = wiring.store(config, topology, new SimpleMeterRegistry());
            assertSame(construction.constructed().getFirst(), store);
        }
    }

    @Test
    void diagnosticsAreCreatedOnlyWhenExplicitlyEnabled() {
        when(config.readDiagnosticsEnabled()).thenReturn(true);
        var registry = new SimpleMeterRegistry();
        try (var construction = mockConstruction(JdbcCatalogStore.class, (store, context) ->
                     assertNotNull(context.arguments().get(3)))) {
            wiring.store(config, topology, registry);
            assertEquals(32, registry.getMeters().size());
        } finally {
            registry.close();
        }
    }

    @Test
    void unknownConnectionNamesFailBeforeConstructingPools() {
        when(config.connections()).thenReturn(Map.of("typo", mock(CatalogConfiguration.Connection.class)));
        assertThrows(IllegalArgumentException.class, () -> wiring.store(config, topology, new SimpleMeterRegistry()));
    }

    @Test
    void readProfileOnlyChangesReadSelection() {
        var store = mock(JdbcCatalogStore.class);
        var generator = mock(IdGenerator.class);
        var router = new ShardRouter(topology);
        when(config.maxIdCandidates()).thenReturn(1024);
        for (var profile : CatalogConfiguration.ReadProfile.values()) {
            when(config.readProfile()).thenReturn(profile);
            var service = wiring.service(store, wiring.settings(config), generator, router, topology);
            assertNull(service.seller(1));
            verify(store).read(eq(router.route(1)), eq(profile == CatalogConfiguration.ReadProfile.REPLICA), any());
        }
        verifyNoInteractions(generator);
    }

    private Endpoint endpoint(String host) {
        return new Endpoint(secured("jdbc:postgresql://" + host + ":5432/shardshop", host.substring(0, 1)), "product_app", "");
    }

    private String secured(String url, String shard) {
        return url + "?sslmode=verify-full&sslrootcert=%2Fetc%2Fshardshop%2Fcatalog%2F" + shard + "%2Fca.crt";
    }
}
