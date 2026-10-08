package dev.nklip.javacraft.shardshop.workload.reader.load;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RatePacerTest {
    // System.nanoTime() can be negative; the pacer compares differences only.
    private long now = Long.MAX_VALUE - 400_000_000L;
    private final List<Long> sleeps = new ArrayList<>();
    private final RatePacer pacer = new RatePacer(4, () -> now, sleeps::add);

    @Test
    void givesConcurrentWorkersEvenlySpacedStartsAtTheTotalRate() throws Exception {
        for (int worker = 0; worker < 4; worker++) {
            pacer.await();
        }
        // The first start is immediate; the clock does not move, thus each worker waits for its own later slot.
        assertEquals(List.of(250_000_000L, 500_000_000L, 750_000_000L), sleeps);
        assertEquals(4, pacer.requestsPerSecond());
    }

    @Test
    void keepsTheRateWhenEachWaitPassesAndAcrossNanoTimeOverflow() throws Exception {
        RatePacer sleeping = new RatePacer(4, () -> now, nanos -> {
            sleeps.add(nanos);
            now += nanos;
        });
        long start = now;
        for (int request = 0; request < 9; request++) {
            sleeping.await();
        }
        assertEquals(2_000_000_000L, now - start);
        assertEquals(8, sleeps.size());
    }

    @Test
    void dropsTheStartsThatAStallMissedInsteadOfSendingABurst() throws Exception {
        pacer.await();
        now += 2_000_000_000L;
        pacer.await();
        pacer.await();
        assertEquals(List.of(250_000_000L), sleeps);
    }

    @Test
    void propagatesInterruptionOfTheWait() throws Exception {
        InterruptedException interrupted = new InterruptedException("stop");
        RatePacer stopping = new RatePacer(1, () -> now, nanos -> {
            throw interrupted;
        });
        stopping.await();
        assertSame(interrupted, assertThrows(InterruptedException.class, stopping::await));
    }

    @Test
    void acceptsOnlyABoundedPositiveRate() {
        assertThrows(IllegalArgumentException.class, () -> new RatePacer(0, () -> now, sleeps::add));
        assertThrows(IllegalArgumentException.class, () -> new RatePacer(10_001, () -> now, sleeps::add));
        assertEquals(10_000, new RatePacer(10_000, () -> now, sleeps::add).requestsPerSecond());
        assertThrows(NullPointerException.class, () -> new RatePacer(1, null, sleeps::add));
        assertThrows(NullPointerException.class, () -> new RatePacer(1, () -> now, null));
    }
}
