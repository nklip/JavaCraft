package dev.nklip.javacraft.shardshop.workload.control;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Counts events and the durations of finished operations in a sliding window of 10 seconds, in 100-millisecond
 * buckets. Before the window is full, the rates use the time since the first measurement.
 */
final class Throughput {
    static final long WINDOW_NANOS = 10_000_000_000L;
    private static final long BUCKET_NANOS = 100_000_000L;
    private static final int BUCKETS = (int) (WINDOW_NANOS / BUCKET_NANOS);

    private final LongSupplier nanoTime;
    private final long origin;
    private final long[] bucketIndexes = new long[BUCKETS];
    private final long[] events = new long[BUCKETS];
    private final long[] operations = new long[BUCKETS];
    private final long[] operationNanos = new long[BUCKETS];
    private long total;

    Throughput(LongSupplier nanoTime) {
        this.nanoTime = Objects.requireNonNull(nanoTime);
        this.origin = nanoTime.getAsLong();
        Arrays.fill(bucketIndexes, -1);
    }

    synchronized void event() {
        events[bucket(nanoTime.getAsLong())]++;
        total++;
    }

    synchronized void operation(long durationNanos) {
        int bucket = bucket(nanoTime.getAsLong());
        operations[bucket]++;
        operationNanos[bucket] += Math.max(0, durationNanos);
    }

    synchronized Window window() {
        long now = nanoTime.getAsLong();
        long current = (now - origin) / BUCKET_NANOS;
        long eventCount = 0;
        long operationCount = 0;
        long nanos = 0;
        for (int slot = 0; slot < BUCKETS; slot++) {
            if (bucketIndexes[slot] > current - BUCKETS) {
                eventCount += events[slot];
                operationCount += operations[slot];
                nanos += operationNanos[slot];
            }
        }
        double seconds = Math.min(WINDOW_NANOS, Math.max(BUCKET_NANOS, now - origin)) / 1e9;
        return new Window(eventCount / seconds, total,
                operationCount == 0 ? 0 : nanos / (double) operationCount / 1e9);
    }

    private int bucket(long now) {
        long index = (now - origin) / BUCKET_NANOS;
        int slot = (int) (index % BUCKETS);
        if (bucketIndexes[slot] != index) {
            bucketIndexes[slot] = index;
            events[slot] = 0;
            operations[slot] = 0;
            operationNanos[slot] = 0;
        }
        return slot;
    }

    /** Events per second in the window, all events, and the mean seconds of the operations in the window. */
    record Window(double rate, long total, double meanOperationSeconds) { }
}
