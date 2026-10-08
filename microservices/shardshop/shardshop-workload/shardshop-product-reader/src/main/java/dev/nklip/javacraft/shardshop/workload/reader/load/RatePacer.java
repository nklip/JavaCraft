package dev.nklip.javacraft.shardshop.workload.reader.load;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Gives request starts evenly spaced times at one total rate for all workers. After a stall, it does not send the
 * missed starts later, thus the load never goes above the configured rate.
 */
public final class RatePacer {
    private final int requestsPerSecond;
    private final long intervalNanos;
    private final LongSupplier nanoTime;
    private final Pause pause;
    private boolean started;
    private long next;

    public RatePacer(int requestsPerSecond, LongSupplier nanoTime, Pause pause) {
        if (requestsPerSecond < 1 || requestsPerSecond > 10_000) {
            throw new IllegalArgumentException("Invalid reader request rate");
        }
        this.requestsPerSecond = requestsPerSecond;
        this.intervalNanos = 1_000_000_000L / requestsPerSecond;
        this.nanoTime = Objects.requireNonNull(nanoTime);
        this.pause = Objects.requireNonNull(pause);
    }

    public int requestsPerSecond() {
        return requestsPerSecond;
    }

    /** Waits until the next free start time of the shared schedule. */
    public void await() throws InterruptedException {
        long start;
        synchronized (this) {
            long now = nanoTime.getAsLong();
            start = started && next - now > 0 ? next : now;
            started = true;
            next = start + intervalNanos;
        }
        long wait = start - nanoTime.getAsLong();
        if (wait > 0) {
            pause.sleep(wait);
        }
    }
}
