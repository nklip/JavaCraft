package dev.nklip.javacraft.shardshop.order.config;

import dev.nklip.javacraft.shardshop.order.cdc.OrderCdcRelay;
import dev.nklip.javacraft.shardshop.order.persistence.JdbcOrderStore;
import dev.nklip.javacraft.shardshop.sharding.ShardTopology;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class OrderOperationalMetricsTest {
    @Test
    void sampleStartsUnavailableThenMapsSnapshotAndStatusValues() {
        AtomicLong now = new AtomicLong(10_000);
        var sample = new OrderOperationalMetrics.Sample(now::get);

        assertEquals(0.0, sample.success(), 0.0);
        assertTrue(Double.isNaN(sample.ageSeconds()));
        assertTrue(Double.isNaN(sample.value(JdbcOrderStore.OperationalSnapshot::pendingOutbox)));
        assertTrue(Double.isNaN(sample.sagaStatus("PENDING_LEDGER")));

        var snapshot = snapshot(7, 19.5, Map.of("PENDING_STOCK", 2L, "PENDING_LEDGER", 3L,
                "CONFIRMED", 4L, "CANCELLED", 5L), 3, true, true, true, 4096, 512);
        OrderOperationalMetrics.refreshSample(sample, () -> snapshot);

        assertEquals(1.0, sample.success(), 0.0);
        assertEquals(0.0, sample.ageSeconds(), 0.0);
        assertEquals(7.0, sample.value(JdbcOrderStore.OperationalSnapshot::pendingOutbox), 0.0);
        assertEquals(19.5, sample.value(JdbcOrderStore.OperationalSnapshot::oldestPendingOutboxSeconds), 0.0);
        assertEquals(3.0, sample.sagaStatus("PENDING_LEDGER"), 0.0);
        assertEquals(1.0, sample.booleanValue(JdbcOrderStore.OperationalSnapshot::slotActive), 0.0);
        assertEquals(1.0, sample.booleanValue(JdbcOrderStore.OperationalSnapshot::checkpointExists), 0.0);

        now.addAndGet(2_500);
        assertEquals(2.5, sample.ageSeconds(), 0.0);

        OrderOperationalMetrics.refreshSample(sample, () -> snapshot(0, 0, Map.of(), 0,
                false, false, false, Double.NaN, Double.NaN));
        assertEquals(1.0, sample.success(), 0.0);
        assertEquals(0.0, sample.value(JdbcOrderStore.OperationalSnapshot::pendingOutbox), 0.0);
        assertEquals(0.0, sample.sagaStatus("PENDING_LEDGER"), 0.0);
        assertEquals(0.0, sample.booleanValue(JdbcOrderStore.OperationalSnapshot::slotExists), 0.0);
        assertTrue(Double.isNaN(sample.value(JdbcOrderStore.OperationalSnapshot::checkpointLagBytes)));
    }

    @Test
    void failedRefreshKeepsLastValuesAndRaisesStalenessSignal() {
        AtomicLong now = new AtomicLong(30_000);
        var sample = new OrderOperationalMetrics.Sample(now::get);
        var snapshot = snapshot(8, 25, Map.of("PENDING_LEDGER", 6L), 2,
                true, false, true, 1000, 250);
        OrderOperationalMetrics.refreshSample(sample, () -> snapshot);
        now.addAndGet(10_000);

        OrderOperationalMetrics.refreshSample(sample, () -> { throw new IllegalStateException("db unavailable"); });

        assertEquals(0.0, sample.success(), 0.0);
        assertEquals(10.0, sample.ageSeconds(), 0.0);
        assertEquals(8.0, sample.value(JdbcOrderStore.OperationalSnapshot::pendingOutbox), 0.0);
        assertEquals(6.0, sample.sagaStatus("PENDING_LEDGER"), 0.0);
        assertEquals(1.0, sample.booleanValue(JdbcOrderStore.OperationalSnapshot::slotExists), 0.0);
    }

    @Test
    void httpMetricsScrapesUsePoolGaugesWithoutStartingDatabaseSnapshots() {
        var registry = new SimpleMeterRegistry();
        try {
            var store = mock(JdbcOrderStore.class);
            var topology = ShardTopology.fromNamesAndRegions("shard-a", "US");
            var config = mock(OrderConfiguration.class);
            when(config.process()).thenReturn(OrderConfiguration.Process.HTTP);
            when(store.poolActive("shard-a")).thenReturn(2L);
            var metrics = new OrderOperationalMetrics(registry, store, topology, config, mock(OrderCdcRelay.class));
            try {
                metrics.startSnapshots();
                assertEquals(2.0, registry.get("shardshop_order_db_pool_active").tag("shard", "shard-a")
                        .tag("process", "http").gauge().value(), 0.0);
                assertNull(registry.find("shardshop_order_outbox_pending").gauge());
                assertNull(registry.find("shardshop_order_metrics_snapshot_success").gauge());
                verify(store, never()).operationalSnapshot(any());
            } finally {
                metrics.stop();
            }
        } finally {
            registry.close();
        }
    }

    @Test
    void cdcRecoveryAndOrdinaryRetryAreSeparateGauges() {
        var registry = new SimpleMeterRegistry();
        try {
            var store = mock(JdbcOrderStore.class);
            var relay = mock(OrderCdcRelay.class);
            var config = mock(OrderConfiguration.class);
            AtomicLong now = new AtomicLong(50_000);
            when(config.process()).thenReturn(OrderConfiguration.Process.SAGA);
            when(relay.readerConfigured("shard-a")).thenReturn(true);
            when(relay.recoveryRequired("shard-a")).thenReturn(true);
            when(relay.retrying("shard-a")).thenReturn(false);
            var metrics = new OrderOperationalMetrics(registry, store,
                    ShardTopology.fromNamesAndRegions("shard-a", "US"), config, relay, now::get);
            try {
                assertEquals(1.0, metric(registry, "shardshop_order_cdc_recovery_required"), 0.0);
                assertEquals(0.0, metric(registry, "shardshop_order_cdc_retrying"), 0.0);
                metrics.refreshSnapshot("shard-a", () -> snapshot(14, 12.5,
                        Map.of("PENDING_LEDGER", 4L), 3, true, true, true, 1000, 250));
                assertEquals(14.0, metric(registry, "shardshop_order_outbox_pending"), 0.0);
                assertEquals(4.0, registry.get("shardshop_order_sagas").tag("shard", "shard-a")
                        .tag("process", "saga").tag("status", "PENDING_LEDGER").gauge().value(), 0.0);
                assertEquals(3.0, metric(registry, "shardshop_order_sagas_reconciliation_exhausted"), 0.0);
                assertEquals(1000.0, metric(registry, "shardshop_order_cdc_retained_wal_bytes"), 0.0);
                now.addAndGet(10_000);
                metrics.refreshSnapshot("shard-a", () -> { throw new IllegalStateException("database unavailable"); });
                assertEquals(0.0, metric(registry, "shardshop_order_metrics_snapshot_success"), 0.0);
                assertEquals(10.0, metric(registry, "shardshop_order_metrics_snapshot_age_seconds"), 0.0);
                assertEquals(14.0, metric(registry, "shardshop_order_outbox_pending"), 0.0);
                metrics.refreshSnapshot("shard-a", () -> snapshot(0, 0, Map.of(), 0,
                        false, false, false, Double.NaN, Double.NaN));
                assertEquals(1.0, metric(registry, "shardshop_order_metrics_snapshot_success"), 0.0);
                assertEquals(0.0, metric(registry, "shardshop_order_outbox_pending"), 0.0);
                assertEquals(0.0, registry.get("shardshop_order_sagas").tag("shard", "shard-a")
                        .tag("process", "saga").tag("status", "PENDING_LEDGER").gauge().value(), 0.0);
                assertTrue(Double.isNaN(metric(registry, "shardshop_order_cdc_checkpoint_lag_bytes")));
                when(relay.recoveryRequired("shard-a")).thenReturn(false);
                when(relay.retrying("shard-a")).thenReturn(true);
                assertEquals(0.0, metric(registry, "shardshop_order_cdc_recovery_required"), 0.0);
                assertEquals(1.0, metric(registry, "shardshop_order_cdc_retrying"), 0.0);
                verify(store, never()).operationalSnapshot(any());
            } finally {
                metrics.stop();
            }
        } finally {
            registry.close();
        }
    }

    private static double metric(SimpleMeterRegistry registry, String name) {
        return registry.get(name).tag("shard", "shard-a").tag("process", "saga").gauge().value();
    }

    private static JdbcOrderStore.OperationalSnapshot snapshot(long pending, double oldest,
            Map<String, Long> statuses, long exhausted, boolean slotExists, boolean slotActive,
            boolean checkpointExists, double retainedWal, double checkpointLag) {
        return new JdbcOrderStore.OperationalSnapshot(pending, oldest, statuses, exhausted,
                2, 1, 3, slotExists, slotActive, checkpointExists, retainedWal, checkpointLag);
    }
}
