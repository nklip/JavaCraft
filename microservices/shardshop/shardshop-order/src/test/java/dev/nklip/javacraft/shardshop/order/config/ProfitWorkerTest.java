package dev.nklip.javacraft.shardshop.order.config;

import dev.nklip.javacraft.shardshop.order.profit.ProfitFailure;
import dev.nklip.javacraft.shardshop.order.profit.ProfitStore;
import dev.nklip.javacraft.shardshop.order.profit.ProfitStore.PendingCredit;
import dev.nklip.javacraft.shardshop.sharding.ShardRouter;
import dev.nklip.javacraft.shardshop.sharding.ShardTopology;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProfitWorkerTest {
    private final ProfitStore store = mock();
    private final ShardRouter router = mock();
    private final ShardTopology topology = ShardTopology.fromNamesAndRegions("a,b", "US,EU");
    private final ProfitWorker.Settings settings = new ProfitWorker.Settings(true, Duration.ofMillis(100), 2, 1);

    @Test
    void oneFailedSellerDoesNotStopOtherIntentsOrHomeShards() {
        var first = topology.shards().getFirst();
        var second = topology.shards().getLast();
        var failed = new PendingCredit(41, 0, 51, 61, "USD", new BigDecimal("1.00"), 0);
        var healthy = new PendingCredit(42, 0, 52, 62, "USD", new BigDecimal("2.00"), 0);
        when(store.pendingProfit(first, 2)).thenReturn(List.of(failed, healthy));
        when(store.pendingProfit(second, 2)).thenReturn(List.of());
        when(router.route(51)).thenReturn(second);
        when(router.route(52)).thenReturn(second);
        doThrow(new ProfitFailure(ProfitFailure.Reason.UNAVAILABLE)).when(store).creditProfit(second, failed);
        doThrow(new ProfitFailure(ProfitFailure.Reason.UNAVAILABLE)).when(store)
                .retryProfit(eq(first), eq(failed), any());

        try (var worker = new ProfitWorker(store, router, topology, settings)) {
            worker.poll();
        }
        verify(store).creditProfit(second, healthy);
        verify(store).markProfitApplied(first, healthy);
        verify(store).pendingProfit(second, 2);
    }

    /** An operator searches by order ID and must see which shard failed and why. */
    @Test
    void logLinesNameTheFailedShardTheCauseAndThePlainOrderId() {
        var home = topology.shards().getFirst();
        var seller = topology.shards().getLast();
        var deferred = new PendingCredit(105614554681012224L, 0, 51, 61, "USD", new BigDecimal("1.00"), 0);
        var blocked = new PendingCredit(105614554681012225L, 3, 52, 62, "USD", new BigDecimal("2.00"), 0);
        when(store.pendingProfit(home, 2)).thenReturn(List.of(deferred, blocked), List.of(deferred));
        when(store.pendingProfit(seller, 2)).thenReturn(List.of());
        when(router.route(51)).thenReturn(seller);
        when(router.route(52)).thenReturn(seller);
        doThrow(ProfitFailure.from(new SQLException("Connection refused", "08001"))).doNothing()
                .when(store).creditProfit(seller, deferred);
        doThrow(ProfitFailure.from(new SQLException("Foreign key violation", "23503")))
                .when(store).creditProfit(seller, blocked);
        List<String> lines = new CopyOnWriteArrayList<>();
        Logger log = Logger.getLogger(ProfitWorker.class.getName());
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                String level = record.getLevel().intValue() >= Level.SEVERE.intValue() ? "ERROR"
                        : record.getLevel().intValue() >= Level.WARNING.intValue() ? "WARN" : "INFO";
                lines.add(level + " " + new SimpleFormatter().formatMessage(record));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        log.addHandler(capture);
        log.setUseParentHandlers(false);
        try (var worker = new ProfitWorker(store, router, topology, settings)) {
            worker.poll();
            worker.poll();
        } finally {
            log.removeHandler(capture);
            log.setUseParentHandlers(true);
        }

        assertEquals(List.of(
                "WARN Profit posting unavailable for b; durable intents will retry: "
                        + "ProfitFailure: UNAVAILABLE <- SQLException: Connection refused",
                "ERROR Profit intent blocked for order 105614554681012225, item 3 on a: "
                        + "MISSING_CATALOG_REFERENCE; investigate the durable intent",
                "INFO Profit posting resumed for b"), lines);
    }

    @Test
    void settingsBoundThePollingIntervalAndBatch() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProfitWorker.Settings(true, Duration.ofMillis(99), 1, 3));
        assertThrows(IllegalArgumentException.class,
                () -> new ProfitWorker.Settings(true, Duration.ofSeconds(31), 1, 3));
        assertThrows(IllegalArgumentException.class,
                () -> new ProfitWorker.Settings(true, Duration.ofSeconds(1), 101, 3));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 33})
    void rejectsUnboundedShardConcurrency(int concurrency) {
        assertThrows(IllegalArgumentException.class, () -> concurrentSettings(concurrency));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 32})
    void acceptsShardConcurrencyBoundaries(int concurrency) {
        assertEquals(concurrency, concurrentSettings(concurrency).shardConcurrency());
    }

    @Test
    void disabledAndClosedWorkersNeverStartPolling() {
        try (var worker = new ProfitWorker(store, router, topology,
                new ProfitWorker.Settings(false, settings.interval(), settings.batchSize(), 3))) {
            worker.start();
        }
        var closed = new ProfitWorker(store, router, topology, concurrentSettings(3));
        closed.close();
        closed.start();
        verifyNoInteractions(store, router);
    }

    @Test
    void failedHomeDoesNotStopOtherHomesOrFuturePolls() {
        var home = topology.shards().getFirst();
        var other = topology.shards().getLast();
        when(store.pendingProfit(home, 2)).thenThrow(new ProfitFailure(ProfitFailure.Reason.UNAVAILABLE))
                .thenReturn(List.of());
        when(store.pendingProfit(other, 2)).thenReturn(List.of());
        try (var worker = new ProfitWorker(store, router, topology, settings)) {
            worker.poll();
            worker.poll();
        }
        verify(store, times(2)).pendingProfit(home, 2);
        verify(store, times(2)).pendingProfit(other, 2);
    }

    @Test
    void blockedHomeDoesNotDelayIndependentHomesOrOverlapItsOwnLane() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch otherTicks = new CountDownLatch(3);
        AtomicInteger firstCalls = new AtomicInteger();
        when(store.pendingProfit(topology.shards().getFirst(), 2)).thenAnswer(invocation -> {
            firstCalls.incrementAndGet();
            entered.countDown();
            waitForRelease(release);
            return List.of();
        });
        when(store.pendingProfit(topology.shards().getLast(), 2)).thenAnswer(invocation -> {
            otherTicks.countDown();
            return List.of();
        });
        try (var worker = new ProfitWorker(store, router, topology, concurrentSettings(3))) {
            worker.start();
            worker.start();
            await(entered);
            await(otherTicks);
            assertEquals(1, firstCalls.get(), "Repeated start must not schedule a duplicate home lane");
        } finally {
            release.countDown();
        }
    }

    @Test
    void totalActiveHomesNeverExceedsConfiguredConcurrency() throws Exception {
        var threeShards = ShardTopology.fromNamesAndRegions("a,b,c", "US,EU,ASIA");
        CountDownLatch twoActive = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch allVisited = new CountDownLatch(3);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        when(store.pendingProfit(any(), eq(2))).thenAnswer(invocation -> {
            maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
            twoActive.countDown();
            try {
                waitForRelease(release);
                return List.of();
            } finally {
                active.decrementAndGet();
                allVisited.countDown();
            }
        });
        try (var worker = new ProfitWorker(store, router, threeShards, concurrentSettings(2))) {
            worker.start();
            await(twoActive);
            assertEquals(2, active.get());
            assertEquals(3, allVisited.getCount());
            release.countDown();
            await(allVisited);
        } finally {
            release.countDown();
        }
        assertEquals(2, maximum.get());
        assertEquals(0, active.get());
        for (var home : threeShards.shards()) {
            verify(store, atLeastOnce()).pendingProfit(home, 2);
        }
    }

    @Test
    void serialModeFinishesEveryHomeBeforeStartingTheNextGlobalPoll() throws Exception {
        CountDownLatch secondEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger firstCalls = new AtomicInteger();
        when(store.pendingProfit(topology.shards().getFirst(), 2)).thenAnswer(invocation -> {
            firstCalls.incrementAndGet();
            return List.of();
        });
        when(store.pendingProfit(topology.shards().getLast(), 2)).thenAnswer(invocation -> {
            secondEntered.countDown();
            waitForRelease(release);
            return List.of();
        });
        try (var worker = new ProfitWorker(store, router, topology, settings)) {
            worker.start();
            await(secondEntered);
            assertEquals(1, firstCalls.get());
        } finally {
            release.countDown();
        }
    }

    @Test
    void sharedSellerAllowsIndependentHomesWhileEachHomeKeepsItsIntentOrder() throws Exception {
        var home = topology.shards().getFirst();
        var other = topology.shards().getLast();
        var first = credit(41);
        var second = credit(42);
        var otherFirst = credit(43);
        var otherSecond = credit(44);
        when(router.route(51)).thenReturn(other);
        when(store.pendingProfit(home, 2)).thenReturn(List.of(first, second)).thenReturn(List.of());
        when(store.pendingProfit(other, 2)).thenReturn(List.of(otherFirst, otherSecond)).thenReturn(List.of());
        CountDownLatch bothFirstCredits = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch marked = new CountDownLatch(4);
        AtomicInteger laterCredits = new AtomicInteger();
        doAnswer(invocation -> {
            PendingCredit credit = invocation.getArgument(1);
            if (credit.equals(first) || credit.equals(otherFirst)) {
                bothFirstCredits.countDown();
                waitForRelease(release);
            } else {
                laterCredits.incrementAndGet();
            }
            return null;
        }).when(store).creditProfit(eq(other), any());
        doAnswer(invocation -> {
            marked.countDown();
            return null;
        }).when(store).markProfitApplied(any(), any());
        try (var worker = new ProfitWorker(store, router, topology, concurrentSettings(3))) {
            worker.start();
            await(bothFirstCredits);
            assertEquals(0, laterCredits.get(), "Each home's second intent must wait for its first credit");
            assertEquals(4, marked.getCount(), "No home may mark before its seller credit finishes");
            release.countDown();
            await(marked);
        } finally {
            release.countDown();
        }
        var homeOrder = inOrder(store);
        homeOrder.verify(store).creditProfit(other, first);
        homeOrder.verify(store).markProfitApplied(home, first);
        homeOrder.verify(store).creditProfit(other, second);
        homeOrder.verify(store).markProfitApplied(home, second);
        var otherOrder = inOrder(store);
        otherOrder.verify(store).creditProfit(other, otherFirst);
        otherOrder.verify(store).markProfitApplied(other, otherFirst);
        otherOrder.verify(store).creditProfit(other, otherSecond);
        otherOrder.verify(store).markProfitApplied(other, otherSecond);
    }

    @Test
    void closeInterruptsPollingStopsItsBatchAndPreventsRestart() throws Exception {
        var home = topology.shards().getFirst();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        when(store.pendingProfit(home, 2)).thenAnswer(invocation -> {
            entered.countDown();
            try {
                new CountDownLatch(1).await(5, TimeUnit.SECONDS);
            } catch (InterruptedException stopping) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return List.of(credit(41), credit(42));
        });
        var worker = new ProfitWorker(store, router, topology, settings);
        try {
            worker.start();
            await(entered);
            worker.close();
            await(interrupted);
            worker.start();
        } finally {
            worker.close();
        }
        verify(store).pendingProfit(home, 2);
        verifyNoMoreInteractions(store);
        verifyNoInteractions(router);
    }

    @Test
    void closeDuringCreditFinishesThatIntentAndStopsBeforeTheNextIntent() throws Exception {
        var home = topology.shards().getFirst();
        var seller = topology.shards().getLast();
        var first = credit(41);
        var second = credit(42);
        when(store.pendingProfit(home, 2)).thenReturn(List.of(first, second));
        when(router.route(51)).thenReturn(seller);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            try {
                new CountDownLatch(1).await(5, TimeUnit.SECONDS);
            } catch (InterruptedException stopping) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return null;
        }).when(store).creditProfit(seller, first);
        var worker = new ProfitWorker(store, router, topology, settings);
        try {
            worker.start();
            await(entered);
            worker.close();
            await(interrupted);
        } finally {
            worker.close();
        }
        verify(store).creditProfit(seller, first);
        verify(store).markProfitApplied(home, first);
        verify(store, never()).creditProfit(seller, second);
        verify(store, never()).pendingProfit(seller, 2);
    }

    @ParameterizedTest
    @CsvSource({"polling,true", "posting,true", "polling,false", "posting,false"})
    void shutdownInterruptionIsQuietButGenuineFailuresStillWarn(String phase, boolean interruptionCause)
            throws Exception {
        var home = topology.shards().getFirst();
        var seller = topology.shards().getLast();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        org.mockito.stubbing.Answer<Object> cancelledPhase = invocation -> {
            entered.countDown();
            try {
                if (!new CountDownLatch(1).await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Worker was not closed while its phase was active");
                }
                throw new AssertionError("Unreachable test gate");
            } catch (InterruptedException stopping) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
                throw ProfitFailure.from(interruptionCause ? stopping : new SQLException("Connection refused", "08001"));
            }
        };
        if (phase.equals("polling")) {
            when(store.pendingProfit(home, 2)).thenAnswer(cancelledPhase);
        } else {
            when(store.pendingProfit(home, 2)).thenReturn(List.of(credit(41)));
            when(router.route(51)).thenReturn(seller);
            doAnswer(cancelledPhase).when(store).creditProfit(seller, credit(41));
        }
        List<LogRecord> warnings = new CopyOnWriteArrayList<>();
        Logger log = Logger.getLogger(ProfitWorker.class.getName());
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) warnings.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        boolean parentHandlers = log.getUseParentHandlers();
        log.addHandler(capture);
        log.setUseParentHandlers(false);
        try (var worker = new ProfitWorker(store, router, topology, settings)) {
            worker.start();
            await(entered);
            worker.close();
            await(interrupted);
        } finally {
            log.removeHandler(capture);
            log.setUseParentHandlers(parentHandlers);
        }
        assertEquals(interruptionCause ? 0 : 1, warnings.size(),
                "Only shutdown cancellation may be suppressed; a real failure during close must remain visible");
        if (!interruptionCause) {
            assertTrue(warnings.getFirst().getMessage().contains("Profit " + phase + " unavailable"));
            assertTrue(warnings.getFirst().getMessage().contains("Connection refused"));
        }
    }

    private PendingCredit credit(long orderId) {
        return new PendingCredit(orderId, 0, 51, 61, "USD", new BigDecimal("1.00"), 0);
    }

    private ProfitWorker.Settings concurrentSettings(int concurrency) {
        return new ProfitWorker.Settings(true, settings.interval(), settings.batchSize(), concurrency);
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
