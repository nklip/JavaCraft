package dev.nklip.javacraft.shardshop.product.persistence;

import dev.nklip.javacraft.shardshop.product.catalog.CatalogFailure;
import dev.nklip.javacraft.shardshop.product.catalog.CatalogStore;
import dev.nklip.javacraft.shardshop.sharding.Shard;
import io.agroal.api.AgroalDataSource;
import io.agroal.api.configuration.supplier.AgroalDataSourceConfigurationSupplier;
import io.agroal.api.exceptionsorter.PostgreSQLExceptionSorter;
import io.agroal.api.security.NamePrincipal;
import io.agroal.api.security.SimplePassword;
import org.postgresql.Driver;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** Bounded per-endpoint pools and a hard deadline for the entire database phase. */
public final class JdbcCatalogStore implements CatalogStore, AutoCloseable {
    private static final long BUDGET_NANOS = Duration.ofSeconds(3).toNanos();
    private static final int ACQUISITION_MILLIS = 1_000;

    public record Endpoint(String jdbcUrl, String username, String password) {
        public Endpoint {
            Objects.requireNonNull(jdbcUrl, "jdbcUrl");
            Objects.requireNonNull(username, "username");
            Objects.requireNonNull(password, "password");
            if (!jdbcUrl.startsWith("jdbc:postgresql:") || username.isBlank()) {
                throw new IllegalArgumentException("A PostgreSQL endpoint and username are required");
            }
        }

        @Override
        public String toString() {
            return "Endpoint[configured]";
        }
    }

    record PoolKey(String shard, boolean replica) {
    }

    private final Map<PoolKey, AgroalDataSource> pools;
    private final ExecutorService workers;
    private final ExecutorService cleanup;
    private final LongSupplier nanoTime;

    public JdbcCatalogStore(Map<String, Endpoint> primary, Map<String, Endpoint> replicas, int maxPoolSize) {
        this.pools = pools(primary, replicas, maxPoolSize);
        int concurrency = Math.max(1, Math.multiplyExact(pools.size(), maxPoolSize));
        this.workers = new ThreadPoolExecutor(
                concurrency, concurrency, 0, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(),
                Thread.ofPlatform().daemon().name("catalog-db-", 0).factory()
        );
        this.cleanup = new ThreadPoolExecutor(
                1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(concurrency),
                Thread.ofPlatform().daemon().name("catalog-abort-", 0).factory(),
                new ThreadPoolExecutor.DiscardPolicy()
        );
        this.nanoTime = System::nanoTime;
    }

    JdbcCatalogStore(Map<PoolKey, AgroalDataSource> pools, ExecutorService workers,
                     ExecutorService cleanup, LongSupplier nanoTime) {
        this.pools = Map.copyOf(pools);
        this.workers = workers;
        this.cleanup = cleanup;
        this.nanoTime = nanoTime;
    }

    private static Map<PoolKey, AgroalDataSource> pools(Map<String, Endpoint> primary,
                                                       Map<String, Endpoint> replicas, int size) {
        if (size < 1 || size > 32) {
            throw new IllegalArgumentException("Catalog pool size must be between 1 and 32");
        }
        LinkedHashMap<PoolKey, AgroalDataSource> result = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, Endpoint> endpoint : primary.entrySet()) {
                result.put(new PoolKey(endpoint.getKey(), false), pool(endpoint.getValue(), size));
            }
            for (Map.Entry<String, Endpoint> endpoint : replicas.entrySet()) {
                result.put(new PoolKey(endpoint.getKey(), true), pool(endpoint.getValue(), size));
            }
            return Map.copyOf(result);
        } catch (SQLException failure) {
            result.values().forEach(AgroalDataSource::close);
            throw new CatalogFailure(CatalogFailure.Reason.UNAVAILABLE);
        }
    }

    static AgroalDataSource pool(Endpoint endpoint, int size) throws SQLException {
        // URL parameters take precedence over driver properties. Append our finite bounds last.
        String url = endpoint.jdbcUrl() + (endpoint.jdbcUrl().contains("?") ? "&" : "?")
                + "connectTimeout=1&loginTimeout=1&socketTimeout=3&cancelSignalTimeout=1";
        return AgroalDataSource.from(new AgroalDataSourceConfigurationSupplier()
                .connectionPoolConfiguration(configuration -> configuration
                        .initialSize(0).minSize(0).maxSize(size)
                        .acquisitionTimeout(Duration.ofMillis(ACQUISITION_MILLIS))
                        .exceptionSorter(new PostgreSQLExceptionSorter())
                        .establishmentRetryAttempts(0)
                        .connectionFactoryConfiguration(factory -> factory
                                .connectionProviderClass(Driver.class)
                                .jdbcUrl(url)
                                .principal(new NamePrincipal(endpoint.username()))
                                .credential(new SimplePassword(endpoint.password()))
                                .loginTimeout(Duration.ofSeconds(1))
                                .networkTimeout(Duration.ofSeconds(3))
                        )
                )
        );
    }

    @Override
    public <T> T read(Shard shard, boolean replica, Function<Session, T> work) {
        return execute(shard, replica, true, work);
    }

    @Override
    public <T> T write(Shard shard, Function<Session, T> work) {
        return execute(shard, false, false, work);
    }

    private <T> T execute(Shard shard, boolean replica, boolean readOnly, Function<Session, T> work) {
        CatalogFailure.Reason unavailable = replica
                ? CatalogFailure.Reason.REPLICA_UNAVAILABLE
                : CatalogFailure.Reason.UNAVAILABLE;
        Deadline deadline = new Deadline(nanoTime, nanoTime.getAsLong() + BUDGET_NANOS);
        AgroalDataSource pool = pools.get(new PoolKey(shard.clusterName(), replica));
        if (pool == null) {
            throw new CatalogFailure(unavailable);
        }
        Request request = new Request(pool, deadline);
        Future<T> future;
        try {
            future = workers.submit(() -> transact(request, readOnly, work, unavailable));
        } catch (RejectedExecutionException failure) {
            throw new CatalogFailure(unavailable);
        }
        try {
            return future.get(deadline.remainingNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            request.cancel();
            future.cancel(true);
            throw new CatalogFailure(unavailable);
        } catch (TimeoutException | SQLException failure) {
            request.cancel();
            future.cancel(true);
            throw new CatalogFailure(unavailable);
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof CatalogFailure catalogFailure) {
                throw catalogFailure;
            }
            throw new CatalogFailure(unavailable);
        }
    }

    private <T> T transact(Request request, boolean readOnly, Function<Session, T> work,
                           CatalogFailure.Reason unavailable) throws SQLException {
        // The fixed acquisition/connect bounds must still fit the remaining phase.
        if (request.deadline.remainingMillis() < ACQUISITION_MILLIS) {
            throw new SQLException("Database phase expired before acquisition");
        }
        try (Request.Lease lease = request.acquire()) {
            Connection connection = lease.connection;
            try {
                connection.setNetworkTimeout(Runnable::run, request.deadline.remainingMillis());
                connection.setReadOnly(readOnly);
                connection.setAutoCommit(false);
                JdbcCatalogSession session = new JdbcCatalogSession(connection, request.deadline, unavailable);
                T result = work.apply(session);
                session.refreshTimeouts();
                connection.commit();
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

    @Override
    public void close() {
        workers.shutdownNow();
        pools.values().forEach(AgroalDataSource::close);
        cleanup.shutdown();
    }

    static final class Deadline {
        private final LongSupplier nanoTime;
        private final long end;

        Deadline(LongSupplier nanoTime, long end) {
            this.nanoTime = nanoTime;
            this.end = end;
        }

        long remainingNanos() throws SQLException {
            long remaining = end - nanoTime.getAsLong();
            if (remaining <= 0) {
                throw new SQLException("Database phase deadline exceeded");
            }
            return remaining;
        }

        int remainingMillis() throws SQLException {
            long remaining = TimeUnit.NANOSECONDS.toMillis(remainingNanos());
            if (remaining == 0) {
                throw new SQLException("Database phase deadline exceeded");
            }
            return Math.toIntExact(remaining);
        }
    }

    private final class Request {
        private final AgroalDataSource pool;
        private final Deadline deadline;
        private final AtomicReference<Lease> active = new AtomicReference<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private Request(AgroalDataSource pool, Deadline deadline) {
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
                    pool.flush(AgroalDataSource.FlushMode.ALL);
                }
            }

            private synchronized void finish(boolean abort) throws SQLException {
                if (!closed) {
                    closed = true;
                    try {
                        if (abort) {
                            // The physical connection is aborted before Agroal releases its wrapper.
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
