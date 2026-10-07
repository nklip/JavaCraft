package dev.nklip.javacraft.shardshop.idgen.allocation;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Reserves one incarnation ID. It never caches, releases, or reuses reservations. */
public final class GeneratorAllocator {

    private static final Duration BUDGET = Duration.ofSeconds(30);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final GeneratorRegistry registry;
    private final String expectedUid;
    private final LongSupplier nanoTime;
    private final RetryDelay delay;

    public GeneratorAllocator(GeneratorRegistry registry, String expectedUid) {
        this(registry, expectedUid, System::nanoTime, Thread::sleep);
    }

    GeneratorAllocator(GeneratorRegistry registry, String expectedUid, LongSupplier nanoTime, RetryDelay delay) {
        this.registry = Objects.requireNonNull(registry, "registry");
        RegistryState.requireUid(expectedUid);
        this.expectedUid = expectedUid;
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.delay = Objects.requireNonNull(delay, "delay");
    }

    public int allocate() throws IOException, InterruptedException {
        long started = nanoTime.getAsLong();
        int observed = 0;
        int burned = 0;
        long backoffMillis = 25;
        while (true) {
            RegistryState state = registry.read(remaining(started, REQUEST_TIMEOUT));
            if (!expectedUid.equals(state.uid()) || state.highWaterMark() < observed) {
                throw new AllocationFailure(AllocationFailure.Reason.STALE_REGISTRY);
            }
            observed = state.highWaterMark();
            int next = Math.max(observed, burned) + 1;
            if (next > 1023) {
                throw new AllocationFailure(AllocationFailure.Reason.EXHAUSTED);
            }
            if (registry.reserve(state, next, remaining(started, REQUEST_TIMEOUT))) {
                // A late acknowledgement must not start an application beyond the startup budget.
                remaining(started, REQUEST_TIMEOUT);
                return next;
            }
            // Even when the server did not commit, skip this slot in the next CAS. If this
            // launcher dies, no ID from that unacknowledged attempt was given to a JVM.
            burned = next;
            delay.pause(remaining(started, Duration.ofMillis(backoffMillis)));
            backoffMillis = Math.min(backoffMillis * 2, 500);
        }
    }

    private Duration remaining(long started, Duration maximum) throws IOException {
        long nanos = BUDGET.toNanos() - (nanoTime.getAsLong() - started);
        if (nanos <= 0) {
            throw new AllocationFailure(AllocationFailure.Reason.DEADLINE);
        }
        return Duration.ofNanos(Math.min(nanos, maximum.toNanos()));
    }

    @FunctionalInterface
    interface RetryDelay {
        void pause(Duration duration) throws InterruptedException;
    }
}
