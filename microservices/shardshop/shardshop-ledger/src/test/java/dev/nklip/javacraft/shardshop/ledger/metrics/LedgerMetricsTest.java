package dev.nklip.javacraft.shardshop.ledger.metrics;

import dev.nklip.javacraft.shardshop.ledger.persistence.JdbcLedgerStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LedgerMetricsTest {
    @Test
    void failedRefreshPreservesLastValuesAndMarksThemStale() {
        JdbcLedgerStore store = mock(JdbcLedgerStore.class);
        when(store.metricsSnapshot()).thenReturn(new JdbcLedgerStore.MetricsSnapshot(4, 8.5, 2))
                .thenThrow(new IllegalStateException("unavailable"));
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-24T10:00:00Z"));
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try (LedgerMetrics metrics = new LedgerMetrics(store, registry, clock, false)) {
            assertTrue(Double.isNaN(value(registry, "shardshop.ledger.pending.results")));
            assertTrue(Double.isNaN(value(registry, "shardshop.ledger.snapshot.age.seconds")));
            assertEquals(0, value(registry, "shardshop.ledger.snapshot.success"));

            metrics.refresh();
            assertEquals(4, value(registry, "shardshop.ledger.pending.results"));
            assertEquals(8.5, value(registry, "shardshop.ledger.oldest.pending.age.seconds"));
            assertEquals(2, value(registry, "shardshop.ledger.conflicting.commands"));
            assertEquals(1, value(registry, "shardshop.ledger.snapshot.success"));
            assertEquals(0, value(registry, "shardshop.ledger.snapshot.age.seconds"));

            metrics.refresh();
            now.set(now.get().plusSeconds(15));
            assertEquals(0, value(registry, "shardshop.ledger.snapshot.success"));
            assertEquals(15, value(registry, "shardshop.ledger.snapshot.age.seconds"));
            assertEquals(4, value(registry, "shardshop.ledger.pending.results"));
            assertEquals(2, value(registry, "shardshop.ledger.conflicting.commands"));
        }
    }

    @Test
    void disabledWorkerNeverQueriesDatabase() {
        JdbcLedgerStore store = mock(JdbcLedgerStore.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try (LedgerMetrics metrics = new LedgerMetrics(store, registry, Clock.systemUTC(), false)) {
            metrics.start();
            verifyNoInteractions(store);
        }
    }

    private static double value(SimpleMeterRegistry registry, String name) {
        return registry.get(name).gauge().value();
    }
}
