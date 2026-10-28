package dev.nklip.javacraft.shardshop.product.persistence;

import dev.nklip.javacraft.shardshop.common.jdbc.DatabaseTimings;
import io.agroal.api.AgroalDataSource;
import io.agroal.api.AgroalDataSourceMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CatalogReadDiagnosticsTest {
    @Test
    void recordsDurationsAndOutcomesWithBoundedLabels() throws SQLException {
        AtomicLong clock = new AtomicLong();
        var registry = new SimpleMeterRegistry();
        try {
            var diagnostics = new CatalogReadDiagnostics(registry, clock::get);
            assertEquals("read", diagnostics.timings(true).measure(DatabaseTimings.Phase.ACQUISITION, () -> {
                clock.addAndGet(7_000_000);
                return "read";
            }));
            assertThrows(SQLException.class, () -> diagnostics.timings(true).measure(DatabaseTimings.Phase.QUERY, () -> {
                clock.addAndGet(2_000_000);
                throw new SQLException("secret");
            }));
            var acquisition = registry.get("shardshop.product.read.database.phase")
                    .tags("phase", "acquisition", "target", "replica", "outcome", "success").timer();
            assertEquals(1, acquisition.count());
            assertEquals(7, acquisition.totalTime(TimeUnit.MILLISECONDS));
            var query = registry.get("shardshop.product.read.database.phase")
                    .tags("phase", "query", "target", "replica", "outcome", "failure").timer();
            assertEquals(1, query.count());
            assertEquals(2, query.totalTime(TimeUnit.MILLISECONDS));
            assertEquals(DatabaseTimings.Phase.values().length * 4, registry.getMeters().size());
        } finally {
            registry.close();
        }
    }

    @Test
    void exportsPoolPressureAndCreationCountsWithoutShardLabels() {
        var pool = mock(AgroalDataSource.class);
        var metrics = mock(AgroalDataSourceMetrics.class);
        when(pool.getMetrics()).thenReturn(metrics);
        when(metrics.activeCount()).thenReturn(3L);
        when(metrics.availableCount()).thenReturn(1L);
        when(metrics.awaitingCount()).thenReturn(2L);
        when(metrics.creationCount()).thenReturn(5L);
        var registry = new SimpleMeterRegistry();
        try {
            var diagnostics = new CatalogReadDiagnostics(registry);
            diagnostics.registerPools(Map.of(new JdbcCatalogStore.PoolKey("a", false), pool,
                    new JdbcCatalogStore.PoolKey("b", false), pool));
            assertEquals(6, registry.get("shardshop.product.read.pool.connections")
                    .tags("target", "primary", "state", "active").gauge().value());
            assertEquals(2, registry.get("shardshop.product.read.pool.connections")
                    .tags("target", "primary", "state", "available").gauge().value());
            assertEquals(4, registry.get("shardshop.product.read.pool.connections")
                    .tags("target", "primary", "state", "awaiting").gauge().value());
            assertEquals(10, registry.get("shardshop.product.read.pool.creations")
                    .tag("target", "primary").functionCounter().count());
        } finally {
            registry.close();
        }
    }
}
