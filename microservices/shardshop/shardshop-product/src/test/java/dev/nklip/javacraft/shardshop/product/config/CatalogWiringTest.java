package dev.nklip.javacraft.shardshop.product.config;

import dev.nklip.javacraft.shardshop.idgen.IdGenerator;
import dev.nklip.javacraft.shardshop.product.persistence.JdbcCatalogStore;
import dev.nklip.javacraft.shardshop.sharding.ShardRouter;
import dev.nklip.javacraft.shardshop.sharding.ShardTopology;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
        })) {
            JdbcCatalogStore store = wiring.store(config, topology);
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
            assertEquals(Map.of("a", new JdbcCatalogStore.Endpoint(secured("jdbc:postgresql://localhost/primary", "a"), "custom", "test-secret"),
                    "b", endpoint("b-rw")), context.arguments().get(0));
            assertEquals(Map.of("a", new JdbcCatalogStore.Endpoint(secured("jdbc:postgresql://localhost/replica", "a"), "custom", "test-secret"),
                    "b", endpoint("b-ro")), context.arguments().get(1));
        })) {
            JdbcCatalogStore store = wiring.store(config, topology);
            assertSame(construction.constructed().getFirst(), store);
        }
    }

    @Test
    void unknownConnectionNamesFailBeforeConstructingPools() {
        when(config.connections()).thenReturn(Map.of("typo", mock(CatalogConfiguration.Connection.class)));
        assertThrows(IllegalArgumentException.class, () -> wiring.store(config, topology));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "jdbc:postgresql://localhost/catalog|jdbc:postgresql://localhost/catalog?sslmode=verify-full&sslrootcert=%2Fcustom+CA%2Froot.crt",
            "jdbc:postgresql://localhost/catalog?applicationName=catalog|jdbc:postgresql://localhost/catalog?applicationName=catalog&sslmode=verify-full&sslrootcert=%2Fcustom+CA%2Froot.crt",
            "jdbc:postgresql://localhost/catalog?sslmode=verify-full|jdbc:postgresql://localhost/catalog?sslmode=verify-full&sslrootcert=%2Fcustom+CA%2Froot.crt",
            "jdbc:postgresql://localhost/catalog?sslmode=verify-ca|jdbc:postgresql://localhost/catalog?sslmode=verify-ca&sslrootcert=%2Fcustom+CA%2Froot.crt",
            "jdbc:postgresql://localhost/catalog?sslmode=VERIFY-FULL|jdbc:postgresql://localhost/catalog?sslmode=VERIFY-FULL&sslrootcert=%2Fcustom+CA%2Froot.crt",
            "jdbc:postgresql://localhost/catalog?sslmode=&sslrootcert=|jdbc:postgresql://localhost/catalog?sslmode=&sslrootcert=&sslmode=verify-full&sslrootcert=%2Fcustom+CA%2Froot.crt",
            "jdbc:postgresql://localhost/catalog?sslmode=verify%2Dfull|jdbc:postgresql://localhost/catalog?sslmode=verify%2Dfull&sslrootcert=%2Fcustom+CA%2Froot.crt",
            "jdbc:postgresql://localhost/catalog?sslmode=verify-full&sslrootcert=%2Fexplicit.crt|jdbc:postgresql://localhost/catalog?sslmode=verify-full&sslrootcert=%2Fexplicit.crt",
            "jdbc:postgresql://localhost/catalog?sslmode=disable|jdbc:postgresql://localhost/catalog?sslmode=disable",
            "jdbc:postgresql://localhost/catalog?sslmode=verify-full&sslmode=require|jdbc:postgresql://localhost/catalog?sslmode=verify-full&sslmode=require"})
    void defaultsSecureCustomUrlsAndPreservesExplicitTlsChoices(String url, String expected) {
        var connection = mock(CatalogConfiguration.Connection.class);
        when(connection.primaryUrl()).thenReturn(Optional.of(url));
        when(connection.replicaUrl()).thenReturn(Optional.of(url));
        when(connection.rootCertificate()).thenReturn(Optional.of("/custom CA/root.crt"));
        when(connection.username()).thenReturn("product_app");
        when(config.connections()).thenReturn(Map.of("a", connection));
        try (var construction = mockConstruction(JdbcCatalogStore.class, (store, context) -> {
            var endpoint = new JdbcCatalogStore.Endpoint(expected, "product_app", "");
            assertEquals(Map.of("a", endpoint, "b", endpoint("b-rw")), context.arguments().get(0));
            assertEquals(Map.of("a", endpoint, "b", endpoint("b-ro")), context.arguments().get(1));
        })) {
            JdbcCatalogStore result = wiring.store(config, topology);
            assertSame(construction.constructed().getFirst(), result);
        }
    }

    @Test
    void rejectsBlankTrustMaterialAndMalformedTlsEncodingWithoutLeakingConfiguration() {
        var connection = mock(CatalogConfiguration.Connection.class);
        when(connection.primaryUrl()).thenReturn(Optional.of("jdbc:postgresql://localhost/catalog"));
        when(connection.rootCertificate()).thenReturn(Optional.of(" "));
        when(connection.username()).thenReturn("product_app");
        when(config.connections()).thenReturn(Map.of("a", connection));
        assertEquals("Catalog root certificate must not be blank",
                assertThrows(IllegalArgumentException.class, () -> wiring.store(config, topology)).getMessage());
        when(connection.primaryUrl()).thenReturn(Optional.of("jdbc:postgresql://localhost/catalog?sslmode=%private"));
        assertEquals("Invalid catalog TLS parameter encoding",
                assertThrows(IllegalArgumentException.class, () -> wiring.store(config, topology)).getMessage());
    }

    @Test
    void readProfileOnlyChangesReadSelection() {
        var store = mock(JdbcCatalogStore.class);
        var generator = mock(IdGenerator.class);
        var router = new ShardRouter(topology);
        when(config.maxIdCandidates()).thenReturn(1024);
        for (var profile : CatalogConfiguration.ReadProfile.values()) {
            when(config.readProfile()).thenReturn(profile);
            var service = wiring.service(store, generator, router, topology, config);
            assertNull(service.seller(1));
            verify(store).read(eq(router.route(1)), eq(profile == CatalogConfiguration.ReadProfile.REPLICA), any());
        }
        verifyNoInteractions(generator);
    }

    private JdbcCatalogStore.Endpoint endpoint(String host) {
        return new JdbcCatalogStore.Endpoint(secured("jdbc:postgresql://" + host + ":5432/shardshop", host.substring(0, 1)), "product_app", "");
    }

    private String secured(String url, String shard) {
        return url + "?sslmode=verify-full&sslrootcert=%2Fetc%2Fshardshop%2Fcatalog%2F" + shard + "%2Fca.crt";
    }
}
