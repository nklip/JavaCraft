package dev.nklip.javacraft.shardshop.workload.control;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkloadControlTest {
    private static final long MILLISECOND = 1_000_000L;
    private static final WorkloadControl.Settings PRODUCER = new WorkloadControl.Settings("order-producer",
            "new order starts/s", new BigDecimal("0.50"), new BigDecimal("100"), new BigDecimal("0.1"), 1,
            List.of("startup", "allocation"));

    private final AtomicLong now = new AtomicLong(0);

    private WorkloadControl control(WorkloadControl.Phase phase) {
        return new WorkloadControl(PRODUCER, phase, now::get, (condition, nanos) -> now.addAndGet(nanos));
    }

    @Test
    void startsAtTheNormalizedDefaultRateUnpaused() {
        ControlStatus status = control(WorkloadControl.Phase.WAITING).status();
        assertEquals("order-producer", status.workload());
        assertEquals("new order starts/s", status.unit());
        assertEquals("WAITING", status.state());
        assertEquals("0.5", status.targetRate().toPlainString());
        assertEquals("0.5", status.effectiveRate().toPlainString());
        assertEquals("0.5", status.defaultRate().toPlainString());
        assertEquals("100", status.maximumRate().toPlainString());
        assertEquals("0.1", status.rateStep().toPlainString());
        assertEquals(false, status.paused());
        assertEquals(0, status.revision());
        assertNull(status.limit());
        assertNull(status.run());
    }

    @Test
    void pauseKeepsTheTargetAndDrainsTheOperationInFlight() {
        WorkloadControl control = control(WorkloadControl.Phase.ACTIVE);
        assertEquals("RUNNING", control.status().state());
        WorkloadControl.Operation operation = control.begin();
        ControlStatus pausing = control.pause();
        assertEquals("PAUSING", pausing.state());
        assertEquals(1, pausing.inFlight());
        assertEquals("0.5", pausing.targetRate().toPlainString());
        assertEquals(0, pausing.effectiveRate().signum());
        operation.close();
        operation.close();
        assertEquals("PAUSED", control.status().state());
        assertEquals(0, control.status().inFlight(), "Closing an operation twice counts it once");
        ControlStatus resumed = control.resume();
        assertEquals("RUNNING", resumed.state());
        assertEquals("0.5", resumed.effectiveRate().toPlainString());
        assertEquals(2, resumed.revision());
    }

    @Test
    void pacedAdmissionHoldsAtPauseAndIsVisibleBeforeTheNextPauseReturns() throws Exception {
        CountDownLatch waiting = new CountDownLatch(1);
        WorkloadControl control = new WorkloadControl(PRODUCER, WorkloadControl.Phase.ACTIVE, System::nanoTime,
                (condition, nanos) -> { waiting.countDown(); condition.awaitNanos(nanos); });
        control.pause();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<WorkloadControl.Operation> admitted = executor.submit(control::awaitOperation);
            assertTrue(waiting.await(3, TimeUnit.SECONDS));
            assertEquals(0, control.status().inFlight());
            assertEquals(false, admitted.isDone());
            control.resume();
            try (WorkloadControl.Operation operation = admitted.get(3, TimeUnit.SECONDS)) {
                assertEquals("PAUSING", control.pause().state());
                assertEquals(1, control.status().inFlight(), "Admission is visible atomically to Pause");
            }
            assertEquals("PAUSED", control.status().state());
        }
    }

    @Test
    void zeroRatePausesNewWorkWithoutChangingThePauseSetting() {
        WorkloadControl control = control(WorkloadControl.Phase.ACTIVE);
        ControlStatus zero = control.rate(BigDecimal.ZERO);
        assertEquals("PAUSED", zero.state());
        assertEquals(false, zero.paused());
        assertEquals("0", zero.targetRate().toPlainString());
        assertEquals("PAUSED", control.resume().state(), "Resume keeps a zero target");
    }

    @Test
    void otherPhasesShowTheirOwnState() {
        WorkloadControl control = control(WorkloadControl.Phase.IDLE);
        control.pause();
        assertEquals("IDLE", control.status().state());
        for (WorkloadControl.Phase phase : List.of(WorkloadControl.Phase.COMPLETED, WorkloadControl.Phase.FAILED,
                WorkloadControl.Phase.WAITING)) {
            control.phase(phase);
            assertSame(phase, control.phase());
            assertEquals(phase.name(), control.status().state());
        }
    }

    @Test
    void normalizesAcceptedRatesWithoutExponentsOrTrailingZeros() {
        WorkloadControl control = control(WorkloadControl.Phase.ACTIVE);
        assertEquals("100", control.rate(new BigDecimal("1E+2")).targetRate().toPlainString());
        assertEquals("100", control.status().targetRate().toString());
        assertEquals("2.5", control.rate(new BigDecimal("2.500")).targetRate().toString());
        assertEquals("0.001", control.rate(new BigDecimal("0.001")).targetRate().toString());
        assertEquals(3, control.status().revision());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.001", "100.001", "0.0001", "1E+3", "1e400"})
    void anInvalidRateLeavesThePreviousSettingAndRevision(String rate) {
        WorkloadControl control = control(WorkloadControl.Phase.ACTIVE);
        control.rate(new BigDecimal("7"));
        ControlFailure failure = assertThrows(ControlFailure.class, () -> control.rate(new BigDecimal(rate)));
        assertSame(ControlFailure.Reason.INVALID_RATE, failure.reason());
        assertEquals("7", control.status().targetRate().toPlainString());
        assertEquals(1, control.status().revision());
        assertThrows(ControlFailure.class, () -> control.rate(null));
    }

    @Test
    void concurrentUpdatesApplyOneAtATime() throws Exception {
        WorkloadControl control = control(WorkloadControl.Phase.ACTIVE);
        int updates = 64;
        CountDownLatch ready = new CountDownLatch(updates);
        List<Callable<ControlStatus>> tasks = new ArrayList<>();
        for (int update = 1; update <= updates; update++) {
            BigDecimal rate = BigDecimal.valueOf(update);
            boolean pause = update % 2 == 0;
            tasks.add(() -> {
                ready.countDown();
                ready.await();
                return pause ? control.pause() : control.rate(rate);
            });
        }
        Set<Long> revisions = new HashSet<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(updates)) {
            for (Future<ControlStatus> result : executor.invokeAll(tasks)) {
                revisions.add(result.get().revision());
            }
        }
        assertEquals(updates, revisions.size(), "Each update saw its own revision");
        ControlStatus last = control.status();
        assertEquals(updates, last.revision());
        assertTrue(last.paused());
        assertTrue(last.targetRate().intValueExact() % 2 == 1, "The target is one of the submitted rates");
    }

    @Test
    void countsStartsAndShowsWhenConcurrencyLimitsTheTarget() throws InterruptedException {
        WorkloadControl control = control(WorkloadControl.Phase.ACTIVE);
        control.rate(new BigDecimal("2"));
        control.awaitStart();
        control.started();
        try (WorkloadControl.Operation ignored = control.begin()) {
            now.addAndGet(1_250 * MILLISECOND);
        }
        ControlStatus limited = control.status();
        assertEquals(1, limited.starts());
        assertEquals(new ControlStatus.Limit("CONCURRENCY", 1, new BigDecimal("1.250"), new BigDecimal("0.800")),
                limited.limit());
        assertNull(control.rate(new BigDecimal("0.5")).limit(), "A target below the achievable rate has no limit");
        assertNull(control.pause().limit());
    }

    @Test
    void countsTrafficTotalsAndShowsTheRegisteredRun() {
        WorkloadControl control = control(WorkloadControl.Phase.IDLE);
        control.traffic().sent("startup", false);
        control.traffic().sent("allocation", true);
        control.traffic().failed("allocation");
        ControlStatus.Run run = new ControlStatus.Run(1, "RUNNING", "2026-10-22T10:15:30.123Z", null, 2, 1056, null, null);
        control.run(() -> run);
        ControlStatus status = control.status();
        assertEquals(2, status.requests());
        assertEquals(1, status.errors());
        assertEquals(run, status.run());
    }

    @Test
    void awaitOpenReturnsWhileTheEffectiveRateIsPositive() throws InterruptedException {
        WorkloadControl control = control(WorkloadControl.Phase.ACTIVE);
        control.awaitOpen();
        assertEquals(0, now.get(), "No paced wait");
    }

    @Test
    void rejectsInvalidSettings() {
        BigDecimal one = BigDecimal.ONE;
        List<String> categories = List.of("reads");
        assertThrows(IllegalArgumentException.class, () -> settings(new BigDecimal("2"), one, one, 1, categories));
        assertThrows(IllegalArgumentException.class, () -> settings(one, BigDecimal.ZERO, one, 1, categories));
        assertThrows(IllegalArgumentException.class, () -> settings(one, new BigDecimal("1.0001"), one, 1, categories));
        assertThrows(IllegalArgumentException.class, () -> settings(one, one, new BigDecimal("2"), 1, categories));
        assertThrows(IllegalArgumentException.class, () -> settings(one, one, BigDecimal.ZERO, 1, categories));
        assertThrows(IllegalArgumentException.class, () -> settings(one, one, new BigDecimal("0.0001"), 1, categories));
        assertThrows(IllegalArgumentException.class, () -> settings(one, one, one, 0, categories));
        assertThrows(IllegalArgumentException.class, () -> settings(one, one, one, 1, List.of()));
        assertThrows(IllegalArgumentException.class, () -> settings(one, null, one, 1, categories));
        assertThrows(IllegalArgumentException.class, () -> settings(one, one, null, 1, categories));
        assertThrows(IllegalArgumentException.class, () -> settings(null, one, one, 1, categories));
    }

    private static WorkloadControl.Settings settings(BigDecimal defaultRate, BigDecimal maximum, BigDecimal step,
                                                     int concurrency, List<String> categories) {
        return new WorkloadControl.Settings("product-reader", "product read starts/s", defaultRate, maximum, step,
                concurrency, categories);
    }
}
