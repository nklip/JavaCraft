package dev.nklip.javacraft.shardshop.order.config;

import dev.nklip.javacraft.shardshop.order.profit.ProfitFailure;
import dev.nklip.javacraft.shardshop.order.profit.ProfitStore;
import dev.nklip.javacraft.shardshop.order.profit.ProfitStore.PendingCredit;
import dev.nklip.javacraft.shardshop.sharding.ShardRouter;
import dev.nklip.javacraft.shardshop.sharding.ShardTopology;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProfitWorkerSchedulingTest {
    private final ProfitStore store = mock();
    private final ShardRouter router = mock();
    private final ShardTopology topology = ShardTopology.fromNamesAndRegions("a,b", "US,EU");
    private final PendingCredit first = new PendingCredit(41, 0, 51, 61, "USD", BigDecimal.ONE, 0);
    private final PendingCredit second = new PendingCredit(42, 0, 52, 62, "USD", BigDecimal.ONE, 0);
    private final ClockedScheduler clock = new ClockedScheduler();

    @Test
    void fullBatchesYieldAfterOneBatchAndTakeTurnsOnASingleThread() throws Exception {
        List<String> visited = new ArrayList<>();
        when(store.pendingProfit(any(), eq(2))).thenAnswer(call -> {
            visited.add(call.getArgument(0).toString());
            return List.of(first, second);
        });
        when(router.route(anyLong())).thenReturn(topology.shards().getLast());
        try (var worker = worker(1)) {
            worker.start();
            worker.start();
            for (int turn = 0; turn < 8; turn++) {
                clock.next();
                assertEquals(turn + 1, visited.size(), "Each turn must fetch exactly one batch");
                assertEquals(2, clock.turns.size(), "Each shard must have only one pending turn");
                assertEquals(0, clock.now, "Productive batches must not pay the idle delay");
            }
        }
        assertEquals(List.of(topology.shards().getFirst().toString(), topology.shards().getLast().toString(),
                topology.shards().getFirst().toString(), topology.shards().getLast().toString(),
                topology.shards().getFirst().toString(), topology.shards().getLast().toString(),
                topology.shards().getFirst().toString(), topology.shards().getLast().toString()), visited);
    }

    enum Yield { EMPTY, PARTIAL, DEFERRED, BLOCKED, POLL_FAILED, MARK_FAILED, RETRY_FAILED }

    @ParameterizedTest
    @EnumSource(Yield.class)
    void incompleteOrFailedBatchesKeepTheDelayEvenWhileAnotherShardIsBusy(Yield result) throws Exception {
        var home = topology.shards().getFirst();
        var other = topology.shards().getLast();
        when(router.route(anyLong())).thenReturn(other);
        when(store.pendingProfit(home, 2)).thenReturn(List.of(first, second));
        when(store.pendingProfit(other, 2)).thenReturn(List.of(first, second));
        switch (result) {
            case EMPTY -> when(store.pendingProfit(home, 2)).thenReturn(List.of());
            case PARTIAL -> when(store.pendingProfit(home, 2)).thenReturn(List.of(first));
            case DEFERRED, RETRY_FAILED -> {
                doThrow(new ProfitFailure(ProfitFailure.Reason.UNAVAILABLE)).when(store).creditProfit(other, first);
                if (result == Yield.RETRY_FAILED) {
                    doThrow(new ProfitFailure(ProfitFailure.Reason.UNAVAILABLE)).when(store)
                            .retryProfit(eq(home), eq(first), any());
                }
            }
            case BLOCKED -> doThrow(new ProfitFailure(ProfitFailure.Reason.MISSING_CATALOG_REFERENCE))
                    .when(store).creditProfit(other, first);
            case POLL_FAILED -> when(store.pendingProfit(home, 2))
                    .thenThrow(new ProfitFailure(ProfitFailure.Reason.UNAVAILABLE));
            case MARK_FAILED -> doThrow(new ProfitFailure(ProfitFailure.Reason.UNAVAILABLE))
                    .when(store).markProfitApplied(home, first);
        }
        try (var worker = worker(1)) {
            worker.start();
            clock.next();
            assertEquals(List.of(0L, 250L), clock.dueTimes(), "Failed/partial home must wait its poll interval");
            // Recover the seller so the other home remains continuously productive.
            doNothing().when(store).creditProfit(other, first);
            clock.next();
            clock.next();
            verify(store).pendingProfit(home, 2);
            verify(store, times(2)).pendingProfit(other, 2);
            assertEquals(0, clock.now, "The healthy home continues while the other home's delay remains intact");
        }
        if (result == Yield.DEFERRED || result == Yield.MARK_FAILED || result == Yield.RETRY_FAILED) {
            verify(store).retryProfit(eq(home), eq(first), argThat(delay ->
                    delay.compareTo(Duration.ofSeconds(1)) >= 0 && delay.compareTo(Duration.ofMillis(1250)) <= 0));
        }
        if (result == Yield.BLOCKED) {
            verify(store).blockProfit(home, first, ProfitFailure.Reason.MISSING_CATALOG_REFERENCE);
        }
    }

    @Test
    void parallelBatchWaitsAfterAnyDeferredGroupAndResumesImmediatelyAfterRecovery() throws Exception {
        var home = topology.shards().getFirst();
        var seller = topology.shards().getLast();
        when(router.route(anyLong())).thenReturn(seller);
        when(store.pendingProfit(home, 2)).thenReturn(List.of(first, second));
        doThrow(new ProfitFailure(ProfitFailure.Reason.UNAVAILABLE)).doNothing()
                .when(store).creditProfit(seller, first);
        try (var worker = worker(3)) {
            worker.start();
            clock.next();
            assertEquals(List.of(0L, 250L), clock.dueTimes());
            verify(store).markProfitApplied(home, second);
            clock.next(); // Empty seller home also waits.
            clock.next(); // Deferred home is now eligible again.
            assertEquals(250, clock.now);
            assertEquals(List.of(250L, 250L), clock.dueTimes(), "A recovered full batch requeues immediately");
            verify(store).markProfitApplied(home, first);
        }
    }

    @Test
    void closePreventsAQueuedContinuationFromStartingAnotherBatch() throws Exception {
        when(router.route(anyLong())).thenReturn(topology.shards().getLast());
        when(store.pendingProfit(any(), eq(2))).thenReturn(List.of(first, second));
        var worker = worker(1);
        worker.start();
        clock.next();
        worker.close();
        worker.start();
        while (!clock.turns.isEmpty()) {
            clock.next();
        }
        verify(store).pendingProfit(topology.shards().getFirst(), 2);
        verify(store, never()).pendingProfit(topology.shards().getLast(), 2);
    }

    private ProfitWorker worker(int concurrency) {
        return new ProfitWorker(store, router, topology,
                new ProfitWorker.Settings(true, Duration.ofMillis(250), 2, concurrency, 2), ignored -> clock.executor);
    }

    /** Controlled time proves retry scheduling without sleeping or measuring host scheduling noise. */
    static final class ClockedScheduler {
        private record Turn(Runnable task, long due, long sequence) { }
        final ScheduledExecutorService executor = mock();
        private final PriorityQueue<Turn> turns = new PriorityQueue<>(
                Comparator.comparingLong(Turn::due).thenComparingLong(Turn::sequence));
        private long now;
        private long sequence;

        ClockedScheduler() {
            when(executor.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class))).thenAnswer(call -> {
                TimeUnit unit = call.getArgument(2);
                turns.add(new Turn(call.getArgument(0), now + unit.toMillis(call.getArgument(1)), sequence++));
                return null;
            });
            try {
                when(executor.awaitTermination(anyLong(), any())).thenReturn(true);
            } catch (InterruptedException impossible) {
                throw new AssertionError(impossible);
            }
        }

        void next() {
            Turn turn = turns.remove();
            now = turn.due();
            turn.task().run();
        }

        private List<Long> dueTimes() {
            return turns.stream().map(Turn::due).sorted().toList();
        }
    }
}
