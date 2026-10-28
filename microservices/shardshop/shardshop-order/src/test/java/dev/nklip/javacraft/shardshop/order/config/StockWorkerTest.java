package dev.nklip.javacraft.shardshop.order.config;

import dev.nklip.javacraft.shardshop.order.stock.StockCoordinator;
import dev.nklip.javacraft.shardshop.order.stock.StockFailure;
import dev.nklip.javacraft.shardshop.sharding.ShardTopology;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.nklip.javacraft.shardshop.order.stock.StockCoordinator.Progress.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StockWorkerTest {
    private final StockCoordinator coordinator = mock(StockCoordinator.class);
    private final ShardTopology topology = ShardTopology.fromNamesAndRegions("a,b", "US,EU");
    private final StockWorker.Settings settings = new StockWorker.Settings(true,
            Duration.ofMillis(100), Duration.ofSeconds(30), 2, 1);

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
                settings.interval(), settings.lease(), settings.batchSize(), 3))) {
            worker.start();
            worker.start();
        }
        verifyNoInteractions(coordinator);
    }

    @ParameterizedTest
    @CsvSource({"99,30,1", "30001,30,1", "250,9,1", "250,301,1", "250,30,0", "250,30,101"})
    void rejectsUnboundedOrUnworkableSettings(long interval, long lease, int batch) {
        assertThrows(IllegalArgumentException.class, () -> new StockWorker.Settings(true,
                Duration.ofMillis(interval), Duration.ofSeconds(lease), batch, 3));
    }

    @ParameterizedTest
    @CsvSource({"0", "33"})
    void rejectsUnboundedShardConcurrency(int concurrency) {
        assertThrows(IllegalArgumentException.class, () -> concurrentSettings(concurrency));
    }

    @ParameterizedTest
    @CsvSource({"1", "32"})
    void acceptsShardConcurrencyBoundaries(int concurrency) {
        assertEquals(concurrency, concurrentSettings(concurrency).shardConcurrency());
    }

    @Test
    void blockedShardDoesNotDelayIndependentShardsOrOverlapItsOwnLane() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch otherTicks = new CountDownLatch(3);
        AtomicInteger firstCalls = new AtomicInteger();
        when(coordinator.processNext(topology.shards().getFirst())).thenAnswer(invocation -> {
            firstCalls.incrementAndGet();
            firstEntered.countDown();
            waitForRelease(releaseFirst);
            return IDLE;
        });
        when(coordinator.processNext(topology.shards().getLast())).thenAnswer(invocation -> {
            otherTicks.countDown();
            return IDLE;
        });
        try (var worker = new StockWorker(coordinator, topology, concurrentSettings(3))) {
            worker.start();
            worker.start();
            await(firstEntered);
            await(otherTicks);
            assertEquals(1, firstCalls.get(), "One scheduled lane per shard, including repeated start calls");
        } finally {
            releaseFirst.countDown();
        }
    }

    @Test
    void totalActiveShardLanesNeverExceedsConfiguredConcurrency() throws Exception {
        var threeShards = ShardTopology.fromNamesAndRegions("a,b,c", "US,EU,ASIA");
        CountDownLatch twoActive = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch allShardsVisited = new CountDownLatch(3);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        when(coordinator.processNext(any())).thenAnswer(invocation -> {
            maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
            twoActive.countDown();
            try {
                waitForRelease(release);
                return IDLE;
            } finally {
                active.decrementAndGet();
                allShardsVisited.countDown();
            }
        });
        try (var worker = new StockWorker(coordinator, threeShards, concurrentSettings(2))) {
            worker.start();
            await(twoActive);
            assertEquals(2, active.get());
            assertEquals(3, allShardsVisited.getCount(), "No lane should have completed while blocked");
            release.countDown();
            await(allShardsVisited);
        } finally {
            release.countDown();
        }
        assertEquals(2, maximum.get());
        assertEquals(0, active.get());
        for (var shard : threeShards.shards()) {
            verify(coordinator, atLeastOnce()).processNext(shard);
        }
    }

    @Test
    void serialModeFinishesEveryShardBeforeStartingItsNextGlobalPoll() throws Exception {
        CountDownLatch secondEntered = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        AtomicInteger firstCalls = new AtomicInteger();
        when(coordinator.processNext(topology.shards().getFirst())).thenAnswer(invocation -> {
            firstCalls.incrementAndGet();
            return IDLE;
        });
        when(coordinator.processNext(topology.shards().getLast())).thenAnswer(invocation -> {
            secondEntered.countDown();
            waitForRelease(releaseSecond);
            return IDLE;
        });
        try (var worker = new StockWorker(coordinator, topology, settings)) {
            worker.start();
            await(secondEntered);
            assertEquals(1, firstCalls.get());
        } finally {
            releaseSecond.countDown();
        }
    }

    @Test
    void closeInterruptsInFlightWorkStopsItsBatchAndPreventsRestart() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        when(coordinator.processNext(any())).thenAnswer(invocation -> {
            entered.countDown();
            try {
                new CountDownLatch(1).await(5, TimeUnit.SECONDS);
            } catch (InterruptedException stopping) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return ADVANCED;
        });
        var worker = new StockWorker(coordinator, topology, settings);
        try {
            worker.start();
            await(entered);
            worker.close();
            await(interrupted);
            worker.start();
        } finally {
            worker.close();
        }
        verify(coordinator).processNext(topology.shards().getFirst());
        verifyNoMoreInteractions(coordinator);
    }

    private StockWorker.Settings concurrentSettings(int concurrency) {
        return new StockWorker.Settings(true, settings.interval(), settings.lease(), settings.batchSize(), concurrency);
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Worker did not reach the expected state");
    }

    private static void waitForRelease(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Test did not release the worker");
            }
        } catch (InterruptedException stopping) {
            Thread.currentThread().interrupt();
        }
    }
}
