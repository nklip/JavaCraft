package dev.nklip.javacraft.shardshop.common.jdbc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Bounded PostgreSQL transactions on connections of pools that the application owns. One hard deadline covers
 * dispatch, pool acquisition, statements and commit. The caller returns at the deadline, and a connection that the
 * caller abandons is aborted, never reused.
 */
public final class DatabasePhases implements AutoCloseable {
    /** The budget of one complete phase; the driver socket limit of {@link JdbcUrls#withDriverLimits} matches it. */
    public static final Duration PHASE_BUDGET = Duration.ofSeconds(3);
    /** The longest wait for a pooled connection; a phase starts only while this wait still fits its deadline. */
    public static final Duration ACQUISITION_TIMEOUT = Duration.ofSeconds(1);

    /** A bounded connection pool of the application. */
    public interface Pool {
        Connection getConnection() throws SQLException;

        /** Closes all connections of the pool after a connection could not be aborted. */
        void flush();

        static Pool of(DataSource dataSource, Runnable flush) {
            return new Pool() {
                @Override
                public Connection getConnection() throws SQLException {
                    return dataSource.getConnection();
                }

                @Override
                public void flush() {
                    flush.run();
                }
            };
        }
    }

    /** The statements of one transaction; the engine commits or rolls back. */
    @FunctionalInterface
    public interface Work<T> {
        T run(Connection connection, Deadline deadline) throws SQLException;
    }

    private final ExecutorService workers;
    private final ExecutorService cleanup;
    private final LongSupplier nanoTime;

    /**
     * Each phase runs on its own virtual thread, thus a burst waits in the bounded pools for at most
     * {@link #ACQUISITION_TIMEOUT} instead of a rejection. {@code maxConnections} bounds the queued aborts.
     */
    public DatabasePhases(String name, int maxConnections) {
        this(Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name(name + "-db-", 0).factory()),
                new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(Math.max(1, maxConnections)),
                        Thread.ofPlatform().daemon().name(name + "-abort-", 0).factory(),
                        new ThreadPoolExecutor.DiscardPolicy()),
                System::nanoTime);
    }

    /** Tests replace the executors and the clock. */
    public DatabasePhases(ExecutorService workers, ExecutorService cleanup, LongSupplier nanoTime) {
        this.workers = workers;
        this.cleanup = cleanup;
        this.nanoTime = nanoTime;
    }

    /**
     * Runs the work in one transaction and commits it. A {@code domain} failure stays unchanged after the
     * rollback, also when the rollback fails, because nothing was committed. All other failures, the deadline, an
     * interrupt and an unknown commit result become {@code unavailable}, without details.
     */
    public <T> T run(Pool pool, boolean readOnly, Duration budget, Work<T> work,
                     Class<? extends RuntimeException> domain, Supplier<? extends RuntimeException> unavailable) {
        return run(pool, readOnly, budget, work, domain, failure -> unavailable.get());
    }

    /**
     * Like the other {@code run}, but {@code unavailable} receives the failure, the deadline timeout, the interrupt or
     * the rejection. A background worker can thus log the cause. Failure messages can contain connection or SQL
     * details, thus they must never reach a client response.
     */
    public <T> T run(Pool pool, boolean readOnly, Duration budget, Work<T> work,
                     Class<? extends RuntimeException> domain,
                     Function<Throwable, ? extends RuntimeException> unavailable) {
        return run(pool, readOnly, budget, work, domain, unavailable, DatabaseTimings.NONE);
    }

    /** Like {@code run}, with optional phase timings; acquisition includes pool wait, creation and validation. */
    public <T> T run(Pool pool, boolean readOnly, Duration budget, Work<T> work,
                     Class<? extends RuntimeException> domain,
                     Function<Throwable, ? extends RuntimeException> unavailable, DatabaseTimings timings) {
        Deadline deadline = new Deadline(nanoTime, nanoTime.getAsLong() + budget.toNanos());
        Request request = new Request(pool, deadline);
        long dispatched = timings.start();
        Future<T> future;
        try {
            future = workers.submit(() -> {
                timings.record(DatabaseTimings.Phase.DISPATCH, dispatched, true);
                return transact(request, readOnly, work, timings);
            });
        } catch (RejectedExecutionException failure) {
            timings.record(DatabaseTimings.Phase.DISPATCH, dispatched, false);
            throw unavailable.apply(failure);
        }
        try {
            return future.get(deadline.remainingNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            request.cancel();
            future.cancel(true);
            throw unavailable.apply(failure);
        } catch (TimeoutException | SQLException failure) {
            request.cancel();
            future.cancel(true);
            throw unavailable.apply(failure);
        } catch (ExecutionException failure) {
            if (domain.isInstance(failure.getCause())) {
                throw domain.cast(failure.getCause());
            }
            throw unavailable.apply(failure.getCause());
        }
    }

    private <T> T transact(Request request, boolean readOnly, Work<T> work, DatabaseTimings timings) throws SQLException {
        // The fixed acquisition/connect bounds must still fit the remaining phase.
        if (request.deadline.remainingMillis() < ACQUISITION_TIMEOUT.toMillis()) {
            throw new SQLException("Database phase expired before acquisition");
        }
        try (Request.Lease lease = timings.measure(DatabaseTimings.Phase.ACQUISITION, request::acquire)) {
            Connection connection = lease.connection;
            try {
                timings.measure(DatabaseTimings.Phase.CONNECTION_SETUP, () -> {
                    connection.setNetworkTimeout(Runnable::run, request.deadline.remainingMillis());
                    connection.setReadOnly(readOnly);
                    connection.setAutoCommit(false);
                    return null;
                });
                T result = timings.measure(DatabaseTimings.Phase.WORK, () -> work.run(connection, request.deadline));
                timings.measure(DatabaseTimings.Phase.PRE_COMMIT_SETUP, () -> {
                    refreshTimeouts(connection, request.deadline);
                    return null;
                });
                timings.measure(DatabaseTimings.Phase.COMMIT, () -> {
                    connection.commit();
                    return null;
                });
                return result;
            } catch (RuntimeException | SQLException failure) {
                try {
                    connection.setNetworkTimeout(Runnable::run, request.deadline.remainingMillis());
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    request.cancel();
                }
                throw failure;
            }
        }
    }

    /** Limits the network, statement and lock waits of the next statements to the rest of the phase. */
    public static void refreshTimeouts(Connection connection, Deadline deadline) throws SQLException {
        int milliseconds = deadline.remainingMillis();
        connection.setNetworkTimeout(Runnable::run, milliseconds);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('statement_timeout', ?, true), set_config('lock_timeout', ?, true)")) {
            statement.setString(1, Integer.toString(milliseconds));
            statement.setString(2, Integer.toString(milliseconds));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Database timeout configuration failed");
                }
            }
        }
    }

    /** Stops running phases; queued aborts still run. The application closes its pools afterwards. */
    @Override
    public void close() {
        workers.shutdownNow();
        cleanup.shutdown();
    }

    /** The absolute end of one phase. */
    public static final class Deadline {
        private final LongSupplier nanoTime;
        private final long end;

        public Deadline(LongSupplier nanoTime, long end) {
            this.nanoTime = nanoTime;
            this.end = end;
        }

        public long remainingNanos() throws SQLException {
            long remaining = end - nanoTime.getAsLong();
            if (remaining <= 0) {
                throw new SQLException("Database phase deadline exceeded");
            }
            return remaining;
        }

        /** At least one millisecond, because a network timeout of zero means no limit. */
        public int remainingMillis() throws SQLException {
            long remaining = TimeUnit.NANOSECONDS.toMillis(remainingNanos());
            if (remaining == 0) {
                throw new SQLException("Database phase deadline exceeded");
            }
            return Math.toIntExact(remaining);
        }
    }

    private final class Request {
        private final Pool pool;
        private final Deadline deadline;
        private final AtomicReference<Lease> active = new AtomicReference<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private Request(Pool pool, Deadline deadline) {
            this.pool = pool;
            this.deadline = deadline;
        }

        private Lease acquire() throws SQLException {
            Lease lease = new Lease(pool.getConnection());
            active.set(lease);
            if (cancelled.get()) {
                lease.close();
                throw new SQLException("Database phase cancelled");
            }
            return lease;
        }

        private void cancel() {
            cancelled.set(true);
            Lease lease = active.get();
            if (lease != null) {
                try {
                    cleanup.execute(lease::abort);
                } catch (RejectedExecutionException failure) {
                    // The cancelled worker still owns the lease and must abort it on release.
                    active.compareAndSet(lease, null);
                }
            }
        }

        /** The worker and cleanup executor can each finish a lease, but only one closes it. */
        private final class Lease implements AutoCloseable {
            private final Connection connection;
            private boolean closed;

            private Lease(Connection connection) {
                this.connection = connection;
            }

            @Override
            public void close() throws SQLException {
                finish(cancelled.get());
            }

            private void abort() {
                try {
                    finish(true);
                } catch (SQLException failure) {
                    pool.flush();
                }
            }

            private synchronized void finish(boolean abort) throws SQLException {
                if (!closed) {
                    closed = true;
                    try {
                        if (abort) {
                            // The physical connection is aborted before the pool releases its wrapper.
                            connection.abort(Runnable::run);
                        } else {
                            connection.close();
                        }
                    } finally {
                        active.compareAndSet(this, null);
                    }
                }
            }
        }
    }
}
