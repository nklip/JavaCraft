package dev.nklip.javacraft.shardshop.order.cdc;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class TransportCleanupWorkerTest {
    private static final TransportCleanupWorker.Settings SETTINGS =
            new TransportCleanupWorker.Settings(true, Duration.ofHours(24), Duration.ofSeconds(30), 8);
    private static final long AGE = Duration.ofHours(24).toNanos() / 1_000;

    @Test
    void waitsForStrictlyLaterDurableCheckpointAndRecapturesAfterDelete() throws SQLException {
        var store = mock(TransportCleanupWorker.Store.class);
        var first = new TransportCleanupStore.Fence(List.of(1L, 2L), 100L);
        var second = new TransportCleanupStore.Fence(List.of(3L), 200L);
        when(store.capture(AGE, 8)).thenReturn(first, second);
        when(store.durableCheckpoint("shardshop_order_shard_a"))
                .thenReturn(null, 99L, 100L, 101L, 201L);
        try (var worker = new TransportCleanupWorker(SETTINGS, "shard-a", store)) {
            for (int i = 0; i < 6; i++) {
                worker.poll();
            }
        }
        var order = inOrder(store);
        order.verify(store).capture(AGE, 8);
        order.verify(store).durableCheckpoint("shardshop_order_shard_a");
        order.verify(store).durableCheckpoint("shardshop_order_shard_a");
        order.verify(store).durableCheckpoint("shardshop_order_shard_a");
        order.verify(store).durableCheckpoint("shardshop_order_shard_a");
        order.verify(store).deleteOutbox(first, AGE);
        order.verify(store).capture(AGE, 8);
        order.verify(store).durableCheckpoint("shardshop_order_shard_a");
        order.verify(store).deleteOutbox(second, AGE);
        verify(store, times(6)).deleteInbox(AGE, 8);
    }

    @Test
    void restartForgetsFenceAndMustCaptureAgain() throws SQLException {
        var store = mock(TransportCleanupWorker.Store.class);
        var fence = new TransportCleanupStore.Fence(List.of(1L), 100L);
        when(store.capture(AGE, 8)).thenReturn(fence);
        try (var first = new TransportCleanupWorker(SETTINGS, "shard-a", store)) {
            first.poll();
        }
        try (var restarted = new TransportCleanupWorker(SETTINGS, "shard-a", store)) {
            restarted.poll();
        }
        verify(store, times(2)).capture(AGE, 8);
        verify(store, never()).durableCheckpoint(anyString());
        verify(store, never()).deleteOutbox(any(), anyLong());
    }

    @Test
    void badCheckpointStillAllowsInboxCleanupAndKeepsPendingFence() throws SQLException {
        var store = mock(TransportCleanupWorker.Store.class);
        var fence = new TransportCleanupStore.Fence(List.of(1L), 100L);
        when(store.capture(AGE, 8)).thenReturn(fence, (TransportCleanupStore.Fence) null);
        when(store.durableCheckpoint(anyString()))
                .thenThrow(new CdcStore.RecoveryRequiredException("foreign offset"))
                .thenReturn(101L);
        try (var worker = new TransportCleanupWorker(SETTINGS, "shard-a", store)) {
            worker.poll();
            worker.poll();
            worker.poll();
        }
        verify(store, times(3)).deleteInbox(AGE, 8);
        verify(store, times(2)).capture(AGE, 8);
        verify(store).deleteOutbox(fence, AGE);
    }

    @Test
    void validatesBounds() {
        assertEquals(1_000_000, new TransportCleanupWorker.Settings(true, Duration.ofSeconds(1),
                Duration.ofMillis(100), 1).retentionMicros());
        assertEquals(1_000_001, new TransportCleanupWorker.Settings(true, Duration.ofSeconds(1).plusNanos(1),
                Duration.ofMillis(100), 1).retentionMicros());
        assertThrows(IllegalArgumentException.class, () -> settings(Duration.ofMillis(999), Duration.ofSeconds(30), 8));
        assertThrows(IllegalArgumentException.class, () -> settings(Duration.ofDays(366), Duration.ofSeconds(30), 8));
        assertThrows(IllegalArgumentException.class, () -> settings(Duration.ofSeconds(1), Duration.ofMillis(99), 8));
        assertThrows(IllegalArgumentException.class, () -> settings(Duration.ofSeconds(1), Duration.ofSeconds(61), 8));
        assertThrows(IllegalArgumentException.class, () -> settings(Duration.ofSeconds(1), Duration.ofSeconds(30), 0));
        assertThrows(IllegalArgumentException.class, () -> settings(Duration.ofSeconds(1), Duration.ofSeconds(30), 1001));
    }

    private static TransportCleanupWorker.Settings settings(Duration retention, Duration poll, int batch) {
        return new TransportCleanupWorker.Settings(true, retention, poll, batch);
    }
}
