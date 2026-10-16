package dev.nklip.javacraft.shardshop.order.config;

import dev.nklip.javacraft.shardshop.order.stock.StockCoordinator;
import dev.nklip.javacraft.shardshop.order.stock.StockFailure;
import dev.nklip.javacraft.shardshop.sharding.ShardTopology;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;

import static dev.nklip.javacraft.shardshop.order.stock.StockCoordinator.Progress.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StockWorkerTest {
    private final StockCoordinator coordinator = mock(StockCoordinator.class);
    private final ShardTopology topology = ShardTopology.fromNamesAndRegions("a,b", "US,EU");
    private final StockWorker.Settings settings = new StockWorker.Settings(true,
            Duration.ofMillis(250), Duration.ofSeconds(30), 2);

    @Test
    void batchLimitLetsOtherShardsProgressEvenWithAnEndlessQueue() {
        when(coordinator.processNext(topology.shards().getFirst())).thenReturn(ADVANCED);
        when(coordinator.processNext(topology.shards().getLast())).thenReturn(IDLE);
        try (var worker = new StockWorker(coordinator, topology, settings)) {
            worker.poll();
        }
        verify(coordinator, times(2)).processNext(topology.shards().getFirst());
        verify(coordinator).processNext(topology.shards().getLast());
    }

    @Test
    void unavailableShardDoesNotStopPollingOtherShardsOrFutureTicks() {
        when(coordinator.processNext(topology.shards().getFirst()))
                .thenThrow(new StockFailure(StockFailure.Reason.UNAVAILABLE)).thenReturn(IDLE);
        when(coordinator.processNext(topology.shards().getLast())).thenReturn(IDLE);
        try (var worker = new StockWorker(coordinator, topology, settings)) {
            worker.poll();
            worker.poll();
        }
        verify(coordinator, times(2)).processNext(topology.shards().getFirst());
        verify(coordinator, times(2)).processNext(topology.shards().getLast());
    }

    @Test
    void deferredSagaDoesNotBusyLoopInTheSameTick() {
        when(coordinator.processNext(topology.shards().getFirst())).thenReturn(DEFERRED);
        when(coordinator.processNext(topology.shards().getLast())).thenReturn(IDLE);
        try (var worker = new StockWorker(coordinator, topology, settings)) {
            worker.poll();
        }
        verify(coordinator).processNext(topology.shards().getFirst());
        verify(coordinator).processNext(topology.shards().getLast());
    }

    @Test
    void disabledWorkerNeverStartsDatabasePolling() {
        try (var worker = new StockWorker(coordinator, topology, new StockWorker.Settings(false,
                settings.interval(), settings.lease(), settings.batchSize()))) {
            worker.start();
            worker.start();
        }
        verifyNoInteractions(coordinator);
    }

    @ParameterizedTest
    @CsvSource({"99,30,1", "30001,30,1", "250,9,1", "250,301,1", "250,30,0", "250,30,101"})
    void rejectsUnboundedOrUnworkableSettings(long interval, long lease, int batch) {
        assertThrows(IllegalArgumentException.class, () -> new StockWorker.Settings(true,
                Duration.ofMillis(interval), Duration.ofSeconds(lease), batch));
    }
}
