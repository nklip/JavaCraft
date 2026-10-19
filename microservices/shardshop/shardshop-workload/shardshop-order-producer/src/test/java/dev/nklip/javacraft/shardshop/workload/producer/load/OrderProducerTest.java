package dev.nklip.javacraft.shardshop.workload.producer.load;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(10)
class OrderProducerTest {
    private final OrderWorkload workload = mock();
    private final OrderProducer.Settings settings = new OrderProducer.Settings("stable", 10,
            Duration.ofMillis(1), Duration.ofMillis(200), Duration.ofMillis(200));

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
        OrderProducer producer = new OrderProducer(workload, settings);
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
                Duration.ofSeconds(1), Duration.ofSeconds(30), Duration.ofMinutes(1)));
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
        var producer = new OrderProducer(workload, settings);
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
        var producer = new OrderProducer(workload, settings);
        try {
            producer.start(failed::countDown);
            assertTrue(failed.await(3, TimeUnit.SECONDS));
            assertTrue(producer.failed());
            verify(workload, never()).complete(any(), anyString(), anyLong(), any());
        } finally {
            producer.stop();
        }
    }

    /** A restart recovers finished ordinals without the order interval; only orders of this process are paced. */
    @Test
    void recoveredOrdinalsAreNotPacedButNewOrdersAre() throws Exception {
        stubStartup();
        when(workload.complete(any(), eq("stable"), anyLong(), any())).thenAnswer(call -> new OrderWorkload.Completion(
                WorkloadFixtures.status("CONFIRMED"), (long) call.getArgument(2) < 3));
        List<Duration> pauses = new CopyOnWriteArrayList<>();
        CountDownLatch paced = new CountDownLatch(1);
        List<String> info = new CopyOnWriteArrayList<>();
        var producer = new OrderProducer(workload, settings, interval -> {
            pauses.add(interval);
            paced.countDown();
            new CountDownLatch(1).await();
        });
        try (var log = capture(Level.INFO, info)) {
            producer.start(() -> { });
            assertTrue(paced.await(3, TimeUnit.SECONDS), "The first new order must be paced");
            assertEquals(List.of(settings.orderInterval()), pauses);
            verify(workload, times(4)).complete(any(), eq("stable"), anyLong(), any());
            assertTrue(info.contains("Order producer recovered 3 finished orders before ordinal 3"), info.toString());
        } finally {
            producer.stop();
        }
        assertFalse(producer.failed());
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
        var producer = new OrderProducer(workload, settings);
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
