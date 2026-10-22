package dev.nklip.javacraft.shardshop.workload.control;

import java.util.Objects;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * Gives operation starts evenly spaced times at one total rate for all callers. After a stall, it does not send the
 * missed starts later, thus the load never goes above the current rate. A rate change takes effect at once: waiting
 * callers give up their reserved times, and the next start comes one new interval after the last granted start, or
 * now if that time has passed. At rate zero, callers wait until the rate becomes positive.
 */
public final class RatePacer {
    private final LongSupplier nanoTime;
    private final Waiter waiter;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private double rate;
    /** Nanoseconds between two starts; zero while the rate is zero. */
    private long interval;
    private boolean scheduled;
    private long next;
    private boolean started;
    private long lastStart;
    /** Changes with each rate change, which cancels every reserved start time. */
    private long epoch;

    public RatePacer(double startsPerSecond) {
        this(startsPerSecond, System::nanoTime, Condition::awaitNanos);
    }

    RatePacer(double startsPerSecond, LongSupplier nanoTime, Waiter waiter) {
        this.nanoTime = Objects.requireNonNull(nanoTime);
        this.waiter = Objects.requireNonNull(waiter);
        apply(startsPerSecond);
    }

    public double rate() {
        lock.lock();
        try {
            return rate;
        } finally {
            lock.unlock();
        }
    }

    /** Sets the total rate in starts per second; zero stops new starts. */
    public void rate(double startsPerSecond) {
        lock.lock();
        try {
            apply(startsPerSecond);
            epoch++;
            // Missed starts are not sent later: the schedule continues from the last granted start.
            scheduled = started && interval > 0;
            next = lastStart + interval;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Waits until the next free start time of the shared schedule. */
    public void await() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (true) {
                while (interval == 0) {
                    waiter.await(changed, Long.MAX_VALUE);
                }
                long now = nanoTime.getAsLong();
                long start = scheduled && next - now > 0 ? next : now;
                scheduled = true;
                next = start + interval;
                long reserved = epoch;
                long remaining = start - now;
                while (remaining > 0 && reserved == epoch) {
                    waiter.await(changed, remaining);
                    remaining = start - nanoTime.getAsLong();
                }
                if (reserved == epoch) {
                    started = true;
                    lastStart = start;
                    return;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Counts a start that did not wait for the schedule, for example the first new order after a replay. The next
     * paced start then comes at least one interval after it.
     */
    public void mark() {
        lock.lock();
        try {
            long now = nanoTime.getAsLong();
            started = true;
            lastStart = now;
            if (interval > 0 && (!scheduled || next - (now + interval) < 0)) {
                scheduled = true;
                next = now + interval;
            }
        } finally {
            lock.unlock();
        }
    }

    /** Waits while the rate is zero, without taking a start time. */
    public void awaitOpen() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (interval == 0) {
                waiter.await(changed, Long.MAX_VALUE);
            }
        } finally {
            lock.unlock();
        }
    }

    private void apply(double startsPerSecond) {
        if (!Double.isFinite(startsPerSecond) || startsPerSecond < 0) {
            throw new IllegalArgumentException("Invalid pacer rate");
        }
        rate = startsPerSecond;
        interval = startsPerSecond == 0 ? 0 : Math.max(1, Math.round(1e9 / startsPerSecond));
    }

    /** Waits on the condition for at most the given nanoseconds. Tests replace it to control time. */
    @FunctionalInterface
    interface Waiter {
        void await(Condition condition, long nanos) throws InterruptedException;
    }
}
