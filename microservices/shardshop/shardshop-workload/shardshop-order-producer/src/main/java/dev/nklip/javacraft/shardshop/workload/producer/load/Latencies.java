package dev.nklip.javacraft.shardshop.workload.producer.load;

import dev.nklip.javacraft.shardshop.workload.control.ControlStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;

/** Bounded recent latency sample, independent of the number of orders in the run. */
final class Latencies {
    private final long[] recent = new long[1024];
    private long count;

    synchronized void add(long nanos) {
        recent[(int) (count++ % recent.length)] = Math.max(0, nanos);
    }

    synchronized ControlStatus.Latency snapshot() {
        int size = (int) Math.min(count, recent.length);
        if (size == 0) {
            return new ControlStatus.Latency(0, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        long[] sorted = Arrays.copyOf(recent, size);
        Arrays.sort(sorted);
        double mean = Arrays.stream(sorted).average().orElseThrow();
        long p95 = sorted[(int) Math.ceil(size * 0.95) - 1];
        return new ControlStatus.Latency(count, millis(mean), millis(p95));
    }

    private static BigDecimal millis(double nanos) {
        return BigDecimal.valueOf(nanos / 1_000_000).setScale(3, RoundingMode.HALF_UP);
    }
}
