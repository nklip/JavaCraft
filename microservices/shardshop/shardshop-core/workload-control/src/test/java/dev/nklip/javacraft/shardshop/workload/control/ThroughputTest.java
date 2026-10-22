package dev.nklip.javacraft.shardshop.workload.control;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ThroughputTest {
    private static final long MILLISECOND = 1_000_000L;

    @Test
    void ratesUseTheTimeSinceStartUntilTheWindowIsFull() {
        AtomicLong now = new AtomicLong(5_000 * MILLISECOND);
        Throughput throughput = new Throughput(now::get);
        for (int event = 0; event < 4; event++) {
            now.addAndGet(500 * MILLISECOND);
            throughput.event();
        }
        Throughput.Window window = throughput.window();
        assertEquals(2.0, window.rate(), 1e-9, "Four events in two seconds");
        assertEquals(4, window.total());
    }

    @Test
    void eventsOlderThanTenSecondsLeaveTheRateButStayInTheTotal() {
        AtomicLong now = new AtomicLong(0);
        Throughput throughput = new Throughput(now::get);
        for (int event = 0; event < 100; event++) {
            throughput.event();
        }
        now.addAndGet(15_000 * MILLISECOND);
        for (int event = 0; event < 20; event++) {
            throughput.event();
        }
        Throughput.Window window = throughput.window();
        assertEquals(2.0, window.rate(), 1e-9, "Only the last ten seconds count");
        assertEquals(120, window.total());
        now.addAndGet(11_000 * MILLISECOND);
        assertEquals(0.0, throughput.window().rate(), "A paused workload drops to zero after the window");
    }

    @Test
    void reportsTheMeanDurationOfTheOperationsInTheWindow() {
        AtomicLong now = new AtomicLong(0);
        Throughput throughput = new Throughput(now::get);
        assertEquals(0.0, throughput.window().meanOperationSeconds(), "No operation, no mean");
        throughput.operation(1_000 * MILLISECOND);
        throughput.operation(3_000 * MILLISECOND);
        throughput.operation(-5);
        assertEquals(4.0 / 3, throughput.window().meanOperationSeconds(), 1e-9, "A negative duration counts as zero");
        now.addAndGet(10_100 * MILLISECOND);
        assertEquals(0.0, throughput.window().meanOperationSeconds());
    }
}
