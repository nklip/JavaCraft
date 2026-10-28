package dev.nklip.javacraft.shardshop.common.jdbc;

import java.sql.SQLException;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Optional, bounded phase observations. Observers must be fast, nonblocking and thread safe. */
public final class DatabaseTimings {
    public enum Phase {
        DISPATCH, ACQUISITION, CONNECTION_SETUP, STATEMENT_SETUP, QUERY, WORK, PRE_COMMIT_SETUP, COMMIT
    }

    @FunctionalInterface
    public interface Observer {
        void record(Phase phase, long nanoseconds, boolean success);
    }

    @FunctionalInterface
    public interface Operation<T> {
        T run() throws SQLException;
    }

    /** Disabled timings do not read the clock or allocate observations. */
    public static final DatabaseTimings NONE = new DatabaseTimings(null, null);

    private final LongSupplier nanoTime;
    private final Observer observer;

    public DatabaseTimings(LongSupplier nanoTime, Observer observer) {
        this.nanoTime = nanoTime;
        this.observer = observer;
        if (observer != null) {
            Objects.requireNonNull(nanoTime);
        }
    }

    public long start() {
        return observer == null ? 0 : nanoTime.getAsLong();
    }

    public void record(Phase phase, long start, boolean success) {
        if (observer != null) {
            try {
                observer.record(phase, Math.max(0, nanoTime.getAsLong() - start), success);
            } catch (RuntimeException ignored) {
                // Diagnostics must not replace a database result or its failure.
            }
        }
    }

    public <T> T measure(Phase phase, Operation<T> operation) throws SQLException {
        long start = start();
        boolean success = false;
        try {
            T result = operation.run();
            success = true;
            return result;
        } finally {
            record(phase, start, success);
        }
    }
}
