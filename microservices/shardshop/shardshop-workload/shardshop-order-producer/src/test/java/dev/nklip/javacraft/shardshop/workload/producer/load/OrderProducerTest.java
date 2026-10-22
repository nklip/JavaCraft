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
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(10)
class OrderProducerTest {
    private final OrderWorkload workload = mock();
    private final OrderProducer.Settings settings = new OrderProducer.Settings("stable", 10,
            Duration.ofMillis(200), Duration.ofMillis(200));
    private final WorkloadControl control = WorkloadFixtures.control("100");

    @Test
    void startupDeadlineInterruptsHttpWorkAndNeverStartsOrderLoad() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch failed = new CountDownLatch(1);
        when(workload.resolveCatalog()).thenAnswer(call -> {
            try {
                new CountDownLatch(1).await();
                return List.of();
            } catch (InterruptedException failure) {
                interrupted.countDown();
                throw failure;
            }
        });
        OrderProducer producer = new OrderProducer(workload, settings, control);
        try {
            producer.start(failed::countDown);
            assertTrue(failed.await(3, TimeUnit.SECONDS));
            assertTrue(interrupted.await(3, TimeUnit.SECONDS));
            assertTrue(producer.failed());
            verify(workload, never()).createBuyers();
            verify(workload, never()).complete(any(), anyString(), anyLong(), any());
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
        var producer = new OrderProducer(workload, new OrderProducer.Settings("stable", 10,
                Duration.ofSeconds(30), Duration.ofMinutes(1)), control);
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
    void advancesOnlyCompletedOrdinalsAndStopsAtTheCompleteOrderDeadline() throws Exception {
        stubStartup();
        List<Long> ordinals = new CopyOnWriteArrayList<>();
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch failed = new CountDownLatch(1);
        when(workload.complete(any(), eq("stable"), anyLong(), any())).thenAnswer(call -> {
            long ordinal = call.getArgument(2);
            ordinals.add(ordinal);
            if (ordinal == 0) {
                return WorkloadFixtures.completion("CONFIRMED");
            }
            try {
                new CountDownLatch(1).await();
                return null;
            } finally {
                interrupted.countDown();
            }
        });
        var producer = new OrderProducer(workload, settings, control);
        try {
            producer.start(failed::countDown);
            assertTrue(failed.await(3, TimeUnit.SECONDS));
            assertTrue(interrupted.await(3, TimeUnit.SECONDS));
            assertEquals(List.of(0L, 1L), ordinals);
            assertTrue(producer.failed());
        } finally {
            producer.stop();
        }
    }

    @Test
    void buyerFailurePreventsAllocation() throws Exception {
        when(workload.resolveCatalog()).thenReturn(WorkloadFixtures.products());
        when(workload.createBuyers()).thenThrow(new ProducerException("Buyer creation failed: HTTP 409", null));
        CountDownLatch failed = new CountDownLatch(1);
        var producer = new OrderProducer(workload, settings, control);
        try {
            producer.start(failed::countDown);
            assertTrue(failed.await(3, TimeUnit.SECONDS));
            assertTrue(producer.failed());
            verify(workload, never()).complete(any(), anyString(), anyLong(), any());
        } finally {
            producer.stop();
        }
    }

    /**
     * A restart recovers finished ordinals without pacing; the first new order starts the paced schedule, thus the next
     * new order waits one interval after it.
     */
    @Test
    void recoveredOrdinalsAreNotPacedButNewOrdersAre() throws Exception {
        stubStartup();
        when(workload.complete(any(), eq("stable"), anyLong(), any())).thenAnswer(call -> {
            boolean recovered = (long) call.getArgument(2) < 3;
            if (!recovered) {
                ((OrderWorkload.Progress) call.getArgument(3)).accept();
            }
            return new OrderWorkload.Completion(WorkloadFixtures.status("CONFIRMED"), recovered);
        });
        control.rate(new BigDecimal("0.01"));
        List<String> info = new CopyOnWriteArrayList<>();
        var producer = new OrderProducer(workload, settings, control);
        try (var log = capture(Level.INFO, info)) {
            producer.start(() -> { });
            awaitUntil(() -> mockingDetails(workload).getInvocations().stream()
                    .filter(call -> call.getMethod().getName().equals("complete")).count() == 4);
            Thread.sleep(300);
            verify(workload, times(4)).complete(any(), eq("stable"), anyLong(), any());
            assertTrue(info.contains("Order producer recovered 3 finished orders before ordinal 3"), info.toString());
            assertEquals("RUNNING", control.status().state());
            assertEquals(1, control.status().starts(), "Only the accepted new order counts as a start");
        } finally {
            producer.stop();
        }
        assertFalse(producer.failed());
    }

    @Test
    void pauseObservesTheOrderInFlightUntilTerminalThenStopsNewAllocations() throws Exception {
        stubStartup();
        CountDownLatch inFlight = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Long> ordinals = new CopyOnWriteArrayList<>();
        when(workload.complete(any(), eq("stable"), anyLong(), any())).thenAnswer(call -> {
            long ordinal = call.getArgument(2);
            ordinals.add(ordinal);
            ((OrderWorkload.Progress) call.getArgument(3)).accept();
            if (ordinal == 0) {
                inFlight.countDown();
                release.await();
            }
            return WorkloadFixtures.completion("CONFIRMED");
        });
        var producer = new OrderProducer(workload, settings, control);
        try {
            producer.start(() -> { });
            assertTrue(inFlight.await(3, TimeUnit.SECONDS));
            assertEquals("PAUSING", control.pause().state(), "The order in flight is observed first");
            assertEquals(1, control.status().inFlight());
            release.countDown();
            awaitUntil(() -> control.status().state().equals("PAUSED"));
            Thread.sleep(300);
            assertEquals(List.of(0L), ordinals, "No new allocation while paused");
            control.resume();
            awaitUntil(() -> ordinals.size() >= 3);
            assertEquals(List.of(0L, 1L, 2L), ordinals.subList(0, 3), "Resume continues the same ordinals");
        } finally {
            producer.stop();
        }
        assertFalse(producer.failed());
    }

    @Test
    void newOrdersFollowTheLiveRateWithoutABurst() throws Exception {
        stubStartup();
        List<Long> starts = new CopyOnWriteArrayList<>();
        when(workload.complete(any(), eq("stable"), anyLong(), any())).thenAnswer(call -> {
            starts.add(System.nanoTime());
            ((OrderWorkload.Progress) call.getArgument(3)).accept();
            return WorkloadFixtures.completion("CONFIRMED");
        });
        control.rate(new BigDecimal("20"));
        var producer = new OrderProducer(workload, settings, control);
        try {
            producer.start(() -> { });
            awaitUntil(() -> starts.size() >= 10);
            control.rate(BigDecimal.ZERO);
            awaitUntil(() -> control.status().state().equals("PAUSED"));
            int stopped = starts.size();
            Thread.sleep(300);
            assertEquals(stopped, starts.size(), "Rate zero stops new orders");
            control.rate(new BigDecimal("5"));
            awaitUntil(() -> starts.size() >= stopped + 3);
        } finally {
            producer.stop();
        }
        // The schedule spaces the starts exactly; thread scheduling can move one observed start by a few ms.
        for (int index = 1; index < 10; index++) {
            long gap = starts.get(index) - starts.get(index - 1);
            assertTrue(gap >= TimeUnit.MILLISECONDS.toNanos(30), "Starts at 20/s keep 50 ms gaps: " + gap);
        }
        long nine = starts.get(9) - starts.getFirst();
        assertTrue(nine >= TimeUnit.MILLISECONDS.toNanos(430), "Nine intervals at 20/s: " + nine);
        int resumed = starts.size() - 3;
        long slow = starts.get(resumed + 2) - starts.get(resumed + 1);
        assertTrue(slow >= TimeUnit.MILLISECONDS.toNanos(190), "Starts at 5/s keep 200 ms gaps: " + slow);
    }

    /** The same ordinal stops every restart, thus the failure line names it, its order and its last status. */
    @Test
    void deadlineFailureNamesTheOrdinalOrderAndLastStatus() throws Exception {
        stubStartup();
        when(workload.complete(any(), eq("stable"), anyLong(), any())).thenAnswer(call -> {
            if ((long) call.getArgument(2) == 0) {
                return WorkloadFixtures.completion("CONFIRMED");
            }
            OrderWorkload.Progress progress = call.getArgument(3);
            progress.buyer("9007199254740993");
            progress.order("880803840000004254");
            progress.status("PENDING_LEDGER");
            new CountDownLatch(1).await();
            return null;
        });
        List<String> errors = new CopyOnWriteArrayList<>();
        CountDownLatch failed = new CountDownLatch(1);
        var producer = new OrderProducer(workload, settings, control);
        try (var log = capture(Level.SEVERE, errors)) {
            producer.start(failed::countDown);
            assertTrue(failed.await(3, TimeUnit.SECONDS));
        } finally {
            producer.stop();
        }
        assertEquals(List.of("Order producer failed: Order completion deadline exceeded (ordinal 1, buyer "
                + "9007199254740993, order 880803840000004254, last status PENDING_LEDGER)"), errors);
    }

    private void stubStartup() throws Exception {
        when(workload.resolveCatalog()).thenReturn(WorkloadFixtures.products());
        when(workload.createBuyers()).thenReturn(WorkloadFixtures.buyers());
        when(workload.planner(eq("stable"), eq(10), any(), any())).thenReturn(WorkloadFixtures.planner("stable", 10));
    }

    private static void awaitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() - deadline < 0, "Condition not reached within 5 seconds");
            Thread.sleep(10);
        }
    }

    /** Collects the messages of one exact level from the producer logger until closed. */
    private static AutoCloseable capture(Level level, List<String> messages) {
        Logger log = Logger.getLogger(OrderProducer.class.getName());
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() == level.intValue()) {
                    // The producer logs printf-style patterns through JBoss Logging.
                    Object[] parameters = record.getParameters();
                    messages.add(parameters == null || parameters.length == 0 ? record.getMessage()
                            : String.format(record.getMessage(), parameters));
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        log.addHandler(handler);
        log.setUseParentHandlers(false);
        return () -> {
            log.removeHandler(handler);
            log.setUseParentHandlers(true);
        };
    }
}
