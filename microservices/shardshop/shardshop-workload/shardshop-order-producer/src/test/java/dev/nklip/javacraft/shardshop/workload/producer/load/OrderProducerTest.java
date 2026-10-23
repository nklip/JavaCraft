package dev.nklip.javacraft.shardshop.workload.producer.load;

import dev.nklip.javacraft.shardshop.workload.control.WorkloadControl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(10)
class OrderProducerTest {
    private final OrderWorkload workload = mock();
    private final WorkloadControl control = new WorkloadControl(OrderProducer.controlSettings(new BigDecimal("30"), 2),
            WorkloadControl.Phase.WAITING);

    @Test
    void startupDeadlineInterruptsHttpWorkAndNeverStartsOrderLoad() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch failed = new CountDownLatch(1);
        when(workload.resolveCatalog()).thenAnswer(call -> {
            try {
                new CountDownLatch(1).await();
                return List.of();
            } finally {
                interrupted.countDown();
            }
        });
        var producer = new OrderProducer(workload, settings(Duration.ofMillis(200), 2, 1, 4), control);
        try {
            producer.start(failed::countDown);
            assertTrue(failed.await(3, TimeUnit.SECONDS));
            assertTrue(interrupted.await(3, TimeUnit.SECONDS));
            assertTrue(producer.failed());
            assertEquals("FAILED", control.status().state());
            verify(workload, never()).createBuyers();
            verify(workload, never()).write(any(), anyString(), anyLong(), any());
        } finally {
            producer.stop();
        }
    }

    @Test
    void shutdownCancelsCatalogWaitWithoutReportingFailure() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        when(workload.resolveCatalog()).thenAnswer(call -> {
            entered.countDown();
            try {
                new CountDownLatch(1).await();
                return List.of();
            } finally {
                interrupted.countDown();
            }
        });
        var producer = new OrderProducer(workload, settings(Duration.ofSeconds(5), 2, 1, 4), control);
        AtomicInteger failures = new AtomicInteger();
        try {
            producer.start(failures::incrementAndGet);
            assertTrue(entered.await(3, TimeUnit.SECONDS));
        } finally {
            producer.stop();
        }
        assertTrue(interrupted.await(3, TimeUnit.SECONDS));
        assertFalse(producer.failed());
        assertEquals(0, failures.get());
    }

    @Test
    void shutdownCancelsAnActiveWriteAndObserverWithoutReportingFailure() throws Exception {
        stubStartup();
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch interrupted = new CountDownLatch(2);
        when(workload.write(any(), anyString(), anyLong(), any())).thenAnswer(call -> {
            if ((long) call.getArgument(2) == 0) {
                ((OrderWorkload.Progress) call.getArgument(3)).accept();
                return WorkloadFixtures.completion("PENDING_LEDGER");
            }
            entered.countDown();
            try {
                new CountDownLatch(1).await();
                return null;
            } finally {
                interrupted.countDown();
            }
        });
        when(workload.observe(any(), any())).thenAnswer(call -> {
            entered.countDown();
            try {
                new CountDownLatch(1).await();
                return null;
            } finally {
                interrupted.countDown();
            }
        });
        AtomicInteger failures = new AtomicInteger();
        var producer = new OrderProducer(workload, settings(Duration.ofSeconds(5), 1, 1, 2), control);
        try {
            producer.start(failures::incrementAndGet);
            assertTrue(entered.await(3, TimeUnit.SECONDS));
        } finally {
            producer.stop();
        }
        assertTrue(interrupted.await(3, TimeUnit.SECONDS));
        awaitUntil(() -> control.status().inFlight() == 0);
        assertFalse(producer.failed());
        assertEquals(0, failures.get());
    }

    @Test
    void buyerFailurePreventsAllocation() throws Exception {
        when(workload.resolveCatalog()).thenReturn(WorkloadFixtures.products());
        when(workload.createBuyers()).thenThrow(new ProducerException("Buyer creation failed: HTTP 409", null));
        CountDownLatch failed = new CountDownLatch(1);
        var producer = new OrderProducer(workload, settings(Duration.ofSeconds(5), 2, 1, 4), control);
        try {
            producer.start(failed::countDown);
            assertTrue(failed.await(3, TimeUnit.SECONDS));
            assertTrue(producer.failed());
            verify(workload, never()).write(any(), anyString(), anyLong(), any());
        } finally {
            producer.stop();
        }
    }

    @Test
    void boundsWritesAndObserversIndependentlyAndBackpressureStopsNewOrdinals() throws Exception {
        stubStartup();
        CountDownLatch firstWrites = new CountDownLatch(2);
        CountDownLatch releaseWrites = new CountDownLatch(1);
        CountDownLatch observerEntered = new CountDownLatch(1);
        CountDownLatch releaseObserver = new CountDownLatch(1);
        AtomicInteger writing = new AtomicInteger();
        AtomicInteger maximumWrites = new AtomicInteger();
        AtomicInteger observing = new AtomicInteger();
        AtomicInteger maximumObservers = new AtomicInteger();
        List<Long> ordinals = new CopyOnWriteArrayList<>();
        when(workload.write(any(), eq("stable"), anyLong(), any())).thenAnswer(call -> {
            long ordinal = call.getArgument(2);
            ordinals.add(ordinal);
            int active = writing.incrementAndGet();
            maximumWrites.accumulateAndGet(active, Math::max);
            try {
                if (ordinal < 2) {
                    firstWrites.countDown();
                    releaseWrites.await();
                }
                ((OrderWorkload.Progress) call.getArgument(3)).accept();
                return WorkloadFixtures.completion("PENDING_STOCK");
            } finally {
                writing.decrementAndGet();
            }
        });
        when(workload.observe(any(), any())).thenAnswer(call -> {
            maximumObservers.accumulateAndGet(observing.incrementAndGet(), Math::max);
            try {
                observerEntered.countDown();
                releaseObserver.await();
                return WorkloadFixtures.status("CONFIRMED");
            } finally {
                observing.decrementAndGet();
            }
        });
        var producer = new OrderProducer(workload, settings(Duration.ofSeconds(5), 2, 1, 4), control);
        try {
            producer.start(() -> fail("Unexpected producer failure"));
            assertTrue(firstWrites.await(3, TimeUnit.SECONDS));
            assertEquals(2, control.status().inFlight());
            assertEquals(2, ordinals.size(), "Only the bounded window may allocate before a write finishes");
            releaseWrites.countDown();
            assertTrue(observerEntered.await(3, TimeUnit.SECONDS));
            awaitUntil(() -> control.status().producer().nonTerminalBacklog() == 4 && control.status().inFlight() == 0);
            assertEquals(List.of(0L, 1L, 2L, 3L), ordinals.stream().sorted().toList());
            assertTrue(control.status().producer().backpressured());
            assertEquals(4, control.status().starts(), "Observer latency does not stop submissions before the backlog bound");
            assertEquals(2, maximumWrites.get());
            assertEquals(1, maximumObservers.get());
            assertEquals("PAUSED", control.pause().state(), "Only writes drain on Pause; the observer still runs");
            releaseObserver.countDown();
            awaitUntil(() -> control.status().producer().nonTerminalBacklog() == 0);
            assertEquals(4, ordinals.size(), "Pause keeps new writes held after backpressure clears");
            assertEquals(4, control.status().producer().confirmedOrders());
            assertEquals(4, control.status().producer().writeLatency().samples());
            assertEquals(4, control.status().producer().sagaLatency().samples());
            assertFalse(control.status().producer().backpressured());
        } finally {
            producer.stop();
        }
        assertFalse(producer.failed());
    }

    @Test
    void aSlowStatusRequestDoesNotHoldTheOtherObserverOrTheSubmissionWindow() throws Exception {
        stubStartup();
        CountDownLatch slow = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch otherObserved = new CountDownLatch(3);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        when(workload.write(any(), anyString(), anyLong(), any())).thenAnswer(call -> {
            long ordinal = call.getArgument(2);
            if (ordinal == 3) control.pause();
            ((OrderWorkload.Progress) call.getArgument(3)).accept();
            var status = new dev.nklip.javacraft.shardshop.workload.producer.http.OrderStatus(
                    "9007199254740993", Long.toString(1000 + ordinal), "PENDING_STOCK", "unused", null);
            return new OrderWorkload.Completion(status, false);
        });
        when(workload.observe(any(), any())).thenAnswer(call -> {
            var previous = (dev.nklip.javacraft.shardshop.workload.producer.http.OrderStatus) call.getArgument(0);
            maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                if (previous.orderId().equals("1000")) {
                    slow.countDown();
                    release.await();
                } else {
                    otherObserved.countDown();
                }
                return WorkloadFixtures.status("CONFIRMED");
            } finally {
                active.decrementAndGet();
            }
        });
        var producer = new OrderProducer(workload, settings(Duration.ofSeconds(5), 2, 2, 4), control);
        try {
            producer.start(() -> fail("Unexpected producer failure"));
            assertTrue(slow.await(3, TimeUnit.SECONDS));
            assertTrue(otherObserved.await(3, TimeUnit.SECONDS));
            awaitUntil(() -> control.status().producer().nonTerminalBacklog() == 1);
            assertEquals(2, maximum.get());
            assertEquals("PAUSED", control.status().state());
            assertEquals(4, control.status().starts());
            assertEquals(3, control.status().producer().confirmedOrders());
            release.countDown();
            awaitUntil(() -> control.status().producer().nonTerminalBacklog() == 0);
            assertEquals(4, control.status().producer().confirmedOrders());
        } finally {
            producer.stop();
        }
    }

    @Test
    void pauseDrainsWritesAndContinuesObservingThenResumeKeepsDenseOrdinals() throws Exception {
        stubStartup();
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);
        CountDownLatch observed = new CountDownLatch(1);
        CountDownLatch resumed = new CountDownLatch(1);
        List<Long> ordinals = new CopyOnWriteArrayList<>();
        when(workload.write(any(), eq("stable"), anyLong(), any())).thenAnswer(call -> {
            long ordinal = call.getArgument(2);
            ordinals.add(ordinal);
            if (ordinal == 0) {
                writing.countDown();
                releaseWrite.await();
            } else {
                control.pause();
                resumed.countDown();
            }
            ((OrderWorkload.Progress) call.getArgument(3)).accept();
            return WorkloadFixtures.completion("PENDING_LEDGER");
        });
        when(workload.observe(any(), any())).thenAnswer(call -> {
            observed.countDown();
            return WorkloadFixtures.status("CONFIRMED");
        });
        var producer = new OrderProducer(workload, settings(Duration.ofSeconds(5), 1, 1, 1), control);
        try {
            producer.start(() -> fail("Unexpected producer failure"));
            assertTrue(writing.await(3, TimeUnit.SECONDS));
            assertEquals("PAUSING", control.pause().state());
            releaseWrite.countDown();
            assertTrue(observed.await(3, TimeUnit.SECONDS), "The observer is not paused");
            awaitUntil(() -> control.status().state().equals("PAUSED"));
            assertEquals(List.of(0L), ordinals);
            control.resume();
            assertTrue(resumed.await(3, TimeUnit.SECONDS));
            assertEquals(List.of(0L, 1L), ordinals);
        } finally {
            producer.stop();
        }
        assertFalse(producer.failed());
    }

    @Test
    void restartReplaysFromZeroAndRecoversPendingOrdersWithoutCountingNewStartsOrLatencies() throws Exception {
        stubStartup();
        List<Long> ordinals = new CopyOnWriteArrayList<>();
        when(workload.write(any(), eq("stable"), anyLong(), any())).thenAnswer(call -> {
            long ordinal = call.getArgument(2);
            ordinals.add(ordinal);
            if (ordinal < 2) {
                return new OrderWorkload.Completion(WorkloadFixtures.status(
                        ordinal == 0 ? "PENDING_LEDGER" : "CONFIRMED"), true);
            }
            control.pause();
            ((OrderWorkload.Progress) call.getArgument(3)).accept();
            return WorkloadFixtures.completion("CONFIRMED");
        });
        when(workload.observe(any(), any())).thenReturn(WorkloadFixtures.status("CONFIRMED"));
        var producer = new OrderProducer(workload, settings(Duration.ofSeconds(5), 2, 1, 4), control);
        try {
            producer.start(() -> fail("Unexpected producer failure"));
            awaitUntil(() -> control.status().state().equals("PAUSED")
                    && control.status().producer().nonTerminalBacklog() == 0);
            assertEquals(List.of(0L, 1L, 2L), ordinals.stream().sorted().toList());
            assertEquals(2, control.status().producer().recoveredOrders());
            assertEquals(1, control.status().starts());
            assertEquals(1, control.status().producer().confirmedOrders());
            assertEquals(1, control.status().producer().writeLatency().samples());
            assertEquals(1, control.status().producer().sagaLatency().samples());
            verify(workload).observe(any(), any());
        } finally {
            producer.stop();
        }
        assertFalse(producer.failed());
    }

    @Test
    void deadlineFailsOnceEvenWhenTheObserverIsBlockedAndNamesThePendingOrdinal() throws Exception {
        stubStartup();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch failed = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();
        when(workload.write(any(), anyString(), anyLong(), any())).thenAnswer(call -> {
            OrderWorkload.Progress progress = call.getArgument(3);
            progress.buyer("9007199254740993");
            progress.order("880803840000004254");
            progress.status("PENDING_LEDGER");
            progress.accept();
            return WorkloadFixtures.completion("PENDING_LEDGER");
        });
        when(workload.observe(any(), any())).thenAnswer(call -> {
            entered.countDown();
            try {
                new CountDownLatch(1).await();
                return null;
            } finally {
                interrupted.countDown();
            }
        });
        var producer = new OrderProducer(workload, settings(Duration.ofMillis(300), 1, 1, 1), control);
        try {
            producer.start(() -> { failures.incrementAndGet(); failed.countDown(); });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertTrue(failed.await(3, TimeUnit.SECONDS));
            assertTrue(interrupted.await(3, TimeUnit.SECONDS));
            assertEquals("FAILED", control.status().state());
            assertTrue(producer.failed());
            assertEquals(1, failures.get());
            assertEquals(1, control.status().starts());
        } finally {
            producer.stop();
        }
    }

    @Test
    void liveRatePacesReplayAndNewStartsWithoutCatchupBurst() throws Exception {
        stubStartup();
        List<Long> starts = new CopyOnWriteArrayList<>();
        CountDownLatch six = new CountDownLatch(6);
        control.rate(new BigDecimal("20"));
        when(workload.write(any(), anyString(), anyLong(), any())).thenAnswer(call -> {
            starts.add(System.nanoTime());
            six.countDown();
            if ((long) call.getArgument(2) == 5) {
                control.pause();
            }
            ((OrderWorkload.Progress) call.getArgument(3)).accept();
            return WorkloadFixtures.completion("CONFIRMED");
        });
        var producer = new OrderProducer(workload, settings(Duration.ofSeconds(5), 2, 1, 4), control);
        try {
            producer.start(() -> fail("Unexpected producer failure"));
            assertTrue(six.await(3, TimeUnit.SECONDS));
            awaitUntil(() -> control.status().state().equals("PAUSED"));
            assertEquals(6, starts.size());
            assertTrue(starts.getLast() - starts.getFirst() >= TimeUnit.MILLISECONDS.toNanos(230),
                    "Five paced intervals at 20 starts/s take at least 250 ms, allowing scheduling jitter");
            assertEquals(6, control.status().starts());
        } finally {
            producer.stop();
        }
    }

    private void stubStartup() throws Exception {
        when(workload.resolveCatalog()).thenReturn(WorkloadFixtures.products());
        when(workload.createBuyers()).thenReturn(WorkloadFixtures.buyers());
        when(workload.planner(eq("stable"), eq(10), any(), any())).thenReturn(WorkloadFixtures.planner("stable", 10));
        when(workload.pollInterval()).thenReturn(Duration.ofMillis(10));
    }

    private static OrderProducer.Settings settings(Duration deadline, int writes, int observers, int backlog) {
        return new OrderProducer.Settings("stable", 10, deadline, deadline, writes, observers, backlog);
    }

    private static void awaitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() - deadline < 0, "Condition not reached within 5 seconds");
            Thread.sleep(10);
        }
    }
}
