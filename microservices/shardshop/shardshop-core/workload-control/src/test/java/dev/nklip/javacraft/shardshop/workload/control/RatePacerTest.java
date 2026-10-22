package dev.nklip.javacraft.shardshop.workload.control;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RatePacerTest {
    private static final long MILLISECOND = 1_000_000L;

    /**
     * A clock that only the waits move. A timed wait lasts its full time, except the next one after a scripted
     * action: the action models a signal, thus that wait ends at the time that the action leaves.
     */
    private static final class FakeTime {
        long now = 1_000 * MILLISECOND;
        final List<Long> waits = new ArrayList<>();
        Runnable onWait;
        Runnable onIndefiniteWait = () -> {
            throw new AssertionError("Unexpected wait at rate zero");
        };

        RatePacer pacer(double rate) {
            return new RatePacer(rate, () -> now, (condition, nanos) -> {
                if (nanos == Long.MAX_VALUE) {
                    onIndefiniteWait.run();
                    return;
                }
                waits.add(nanos);
                Runnable action = onWait;
                onWait = null;
                if (action == null) {
                    now += nanos;
                } else {
                    action.run();
                }
            });
        }
    }

    @Test
    void spacesStartsEvenlyAtTheTotalRate() throws InterruptedException {
        FakeTime time = new FakeTime();
        RatePacer pacer = time.pacer(4);
        List<Long> starts = new ArrayList<>();
        for (int start = 0; start < 4; start++) {
            pacer.await();
            starts.add(time.now);
        }
        assertEquals(List.of(1_000 * MILLISECOND, 1_250 * MILLISECOND, 1_500 * MILLISECOND, 1_750 * MILLISECOND), starts);
    }

    @Test
    void doesNotSendMissedStartsAfterAStall() throws InterruptedException {
        FakeTime time = new FakeTime();
        RatePacer pacer = time.pacer(10);
        pacer.await();
        time.now += 2_000 * MILLISECOND;
        pacer.await();
        pacer.await();
        assertEquals(List.of(100 * MILLISECOND), time.waits, "Only one interval after the stall, no catch-up burst");
    }

    @Test
    void aRateChangeCancelsTheReservedWaitAndSpacesFromTheLastStart() throws InterruptedException {
        FakeTime time = new FakeTime();
        RatePacer pacer = time.pacer(1);
        pacer.await();
        long first = time.now;
        // The second start waits for one second; 100 ms into that wait the rate rises to 5/s.
        time.onWait = () -> {
            time.now += 100 * MILLISECOND;
            pacer.rate(5);
        };
        pacer.await();
        assertEquals(first + 200 * MILLISECOND, time.now, "The new interval counts from the last granted start");
        assertEquals(List.of(1_000 * MILLISECOND, 100 * MILLISECOND), time.waits);
    }

    @Test
    void aDecreaseAppliesToStartsThatWereAlreadyWaiting() throws InterruptedException {
        FakeTime time = new FakeTime();
        RatePacer pacer = time.pacer(100);
        pacer.await();
        long first = time.now;
        time.onWait = () -> pacer.rate(2);
        pacer.await();
        assertEquals(first + 500 * MILLISECOND, time.now, "The new 500 ms interval counts from the last start");
        assertEquals(List.of(10 * MILLISECOND, 500 * MILLISECOND), time.waits);
    }

    @Test
    void zeroStopsStartsUntilTheRateBecomesPositive() throws InterruptedException {
        FakeTime time = new FakeTime();
        RatePacer pacer = time.pacer(0);
        List<String> events = new ArrayList<>();
        time.onIndefiniteWait = () -> {
            events.add("waiting at zero");
            time.now += 60_000 * MILLISECOND;
            pacer.rate(2);
        };
        pacer.await();
        assertEquals(List.of("waiting at zero"), events);
        assertEquals(List.of(), time.waits, "After a long pause, the first start is immediate and alone");
        pacer.await();
        assertEquals(List.of(500 * MILLISECOND), time.waits);
    }

    @Test
    void openWaitsOnlyWhileTheRateIsZeroAndTakesNoStart() throws InterruptedException {
        FakeTime time = new FakeTime();
        RatePacer pacer = time.pacer(1);
        pacer.awaitOpen();
        pacer.await();
        assertEquals(List.of(), time.waits, "awaitOpen reserved no start time");
        pacer.rate(0);
        time.onIndefiniteWait = () -> pacer.rate(1);
        pacer.awaitOpen();
        assertEquals(1.0, pacer.rate());
    }

    @Test
    void sharesOneScheduleBetweenThreadsAndWakesWaitersAtOnceOnAChange() throws Exception {
        RatePacer pacer = new RatePacer(0.1);
        pacer.await();
        CountDownLatch waiting = new CountDownLatch(2);
        List<Thread> threads = new ArrayList<>();
        List<Long> finished = Collections.synchronizedList(new ArrayList<>());
        for (int worker = 0; worker < 2; worker++) {
            threads.add(Thread.ofPlatform().start(() -> {
                try {
                    waiting.countDown();
                    pacer.await();
                    finished.add(System.nanoTime());
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        assertTrue(waiting.await(5, TimeUnit.SECONDS));
        Thread.sleep(200);
        assertEquals(List.of(), finished, "Both workers wait for slots 10 and 20 seconds away");
        long changed = System.nanoTime();
        pacer.rate(20);
        for (Thread thread : threads) {
            thread.join(Duration.ofSeconds(5));
        }
        assertEquals(2, finished.size());
        long last = Math.max(finished.get(0), finished.get(1));
        assertTrue(last - changed < 2_000 * MILLISECOND, "The new rate applied to the reserved starts at once");
    }

    @Test
    void anInterruptStopsAWaitingStart() throws Exception {
        RatePacer pacer = new RatePacer(0);
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread waiter = Thread.ofPlatform().start(() -> {
            try {
                pacer.await();
            } catch (Throwable failure) {
                outcome.set(failure);
            }
        });
        Thread.sleep(100);
        waiter.interrupt();
        waiter.join(Duration.ofSeconds(5));
        assertTrue(outcome.get() instanceof InterruptedException, String.valueOf(outcome.get()));
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.001, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void rejectsRatesThatAreNotFiniteAndNonnegative(double rate) {
        assertThrows(IllegalArgumentException.class, () -> new RatePacer(rate));
        RatePacer pacer = new RatePacer(5);
        assertThrows(IllegalArgumentException.class, () -> pacer.rate(rate));
        assertEquals(5.0, pacer.rate(), "An invalid change keeps the previous rate");
    }
}
