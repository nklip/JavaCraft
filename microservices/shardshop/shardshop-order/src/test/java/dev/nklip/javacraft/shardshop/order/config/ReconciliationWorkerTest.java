package dev.nklip.javacraft.shardshop.order.config;

import dev.nklip.javacraft.shardshop.order.reconciliation.ReconciliationCoordinator;
import dev.nklip.javacraft.shardshop.order.reconciliation.ReconciliationCoordinator.CandidateFailure;
import dev.nklip.javacraft.shardshop.order.reconciliation.ReconciliationCoordinator.ScanResult;
import dev.nklip.javacraft.shardshop.sharding.ShardTopology;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReconciliationWorkerTest {
    private final ShardTopology topology = ShardTopology.fromNamesAndRegions("a,b", "US,EU");
    private final ReconciliationCoordinator coordinator = mock(ReconciliationCoordinator.class);
    private final MutableClock clock = new MutableClock();
    private final List<String> alerts = new ArrayList<>();
    private final ReconciliationWorker.AlertSink sink = new ReconciliationWorker.AlertSink() {
        @Override public void exhausted(String shard, long count) { alerts.add(shard + ":" + count); }
        @Override public void recovered(String shard) { alerts.add(shard + ":recovered"); }
    };
    private final ReconciliationWorker.Settings settings =
            new ReconciliationWorker.Settings(true, Duration.ofSeconds(30), 100);

    @BeforeEach
    void cleanScansByDefault() {
        when(coordinator.scan(any(), eq(100))).thenReturn(new ScanResult(true, false, null));
    }

    @Test
    void oneShardFailureDoesNotStopTheOtherOrTheNextTick() {
        var first = topology.shards().getFirst();
        var second = topology.shards().getLast();
        when(coordinator.scan(first, 100)).thenThrow(new IllegalStateException()).thenReturn(new ScanResult(true, false, null));
        try (var worker = new ReconciliationWorker(coordinator, topology, settings, clock, sink)) {
            worker.poll();
            worker.poll();
        }
        verify(coordinator, times(2)).scan(second, 100);
        verify(coordinator, times(2)).scan(first, 100);
    }

    @Test
    void failedPageAndEmptyWrapStayUnavailableUntilFullCleanPass() {
        var first = topology.shards().getFirst();
        var failure = new CandidateFailure(42, new IllegalStateException("temporary"));
        when(coordinator.scan(first, 100)).thenReturn(
                new ScanResult(false, true, failure),
                new ScanResult(true, true, null),
                new ScanResult(true, false, null));
        try (var worker = new ReconciliationWorker(coordinator, topology, settings, clock, sink)) {
            worker.poll();
            assertTrue(worker.isUnavailable("a"));
            worker.poll();
            assertTrue(worker.isUnavailable("a"));
            worker.poll();
            assertFalse(worker.isUnavailable("a"));
        }
        verify(coordinator, times(3)).scan(topology.shards().getLast(), 100);
    }

    @Test
    void exhaustedAlertsAreBoundedRepeatOnCountChangeAndRecover() {
        var first = topology.shards().getFirst();
        when(coordinator.exhausted(first)).thenReturn(2L, 2L, 3L, 3L, 3L, 0L);
        try (var worker = new ReconciliationWorker(coordinator, topology, settings, clock, sink)) {
            worker.poll();
            clock.advance(Duration.ofMinutes(4));
            worker.poll();
            worker.poll();
            clock.advance(Duration.ofMinutes(4));
            worker.poll();
            clock.advance(Duration.ofMinutes(1));
            worker.poll();
            worker.poll();
        }
        assertEquals(List.of("a:2", "a:3", "a:3", "a:recovered"), alerts);
        when(coordinator.exhausted(first)).thenReturn(3L);
        try (var restarted = new ReconciliationWorker(coordinator, topology, settings, clock, sink)) {
            restarted.poll();
        }
        assertEquals("a:3", alerts.getLast());
    }

    @Test
    void disabledWorkerDoesNotPoll() {
        var disabled = new ReconciliationWorker.Settings(false, Duration.ofSeconds(30), 100);
        try (var worker = new ReconciliationWorker(coordinator, topology, disabled, clock, sink)) {
            worker.start();
        }
        verifyNoInteractions(coordinator);
    }

    @ParameterizedTest
    @CsvSource({"99,100", "30001,100", "30000,0", "30000,1001"})
    void rejectsInvalidSettings(long millis, int batch) {
        assertThrows(IllegalArgumentException.class,
                () -> new ReconciliationWorker.Settings(true, Duration.ofMillis(millis), batch));
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-24T00:00:00Z");
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
