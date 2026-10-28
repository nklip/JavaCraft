package dev.nklip.javacraft.shardshop.product.persistence;

import dev.nklip.javacraft.shardshop.common.jdbc.DatabasePhases;
import dev.nklip.javacraft.shardshop.common.jdbc.DatabaseTimings;
import dev.nklip.javacraft.shardshop.common.jdbc.Endpoint;
import dev.nklip.javacraft.shardshop.common.jdbc.JdbcUrls;
import dev.nklip.javacraft.shardshop.product.catalog.CatalogFailure;
import dev.nklip.javacraft.shardshop.product.catalog.CatalogStore;
import dev.nklip.javacraft.shardshop.sharding.Shard;
import io.agroal.api.AgroalDataSource;
import io.agroal.api.configuration.supplier.AgroalDataSourceConfigurationSupplier;
import io.agroal.api.exceptionsorter.PostgreSQLExceptionSorter;
import io.agroal.api.security.NamePrincipal;
import io.agroal.api.security.SimplePassword;
import org.postgresql.Driver;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.LongSupplier;

import static dev.nklip.javacraft.shardshop.product.catalog.CatalogModel.ListedProduct;

/** Bounded per-endpoint pools and a hard deadline for the entire database phase. */
public final class JdbcCatalogStore implements CatalogStore, AutoCloseable {
    record PoolKey(String shard, boolean replica) {
    }

    private final Map<PoolKey, AgroalDataSource> pools;
    private final DatabasePhases phases;
    private final LongSupplier nanoTime;
    private final CatalogReadDiagnostics diagnostics;

    public JdbcCatalogStore(Map<String, Endpoint> primary, Map<String, Endpoint> replicas, int maxPoolSize) {
        this(primary, replicas, maxPoolSize, null);
    }

    public JdbcCatalogStore(Map<String, Endpoint> primary, Map<String, Endpoint> replicas, int maxPoolSize,
                            CatalogReadDiagnostics diagnostics) {
        this.pools = pools(primary, replicas, maxPoolSize, diagnostics != null);
        this.phases = new DatabasePhases("catalog", Math.max(1, Math.multiplyExact(pools.size(), maxPoolSize)));
        this.nanoTime = System::nanoTime;
        this.diagnostics = diagnostics;
        if (diagnostics != null) {
            diagnostics.registerPools(this.pools);
        }
    }

    JdbcCatalogStore(Map<PoolKey, AgroalDataSource> pools, DatabasePhases phases) {
        this(pools, phases, System::nanoTime);
    }

    JdbcCatalogStore(Map<PoolKey, AgroalDataSource> pools, DatabasePhases phases, LongSupplier nanoTime) {
        this(pools, phases, nanoTime, null);
    }

    JdbcCatalogStore(Map<PoolKey, AgroalDataSource> pools, DatabasePhases phases, LongSupplier nanoTime,
                     CatalogReadDiagnostics diagnostics) {
        this.pools = Map.copyOf(pools);
        this.phases = phases;
        this.nanoTime = nanoTime;
        this.diagnostics = diagnostics;
    }

    private static Map<PoolKey, AgroalDataSource> pools(Map<String, Endpoint> primary,
                                                       Map<String, Endpoint> replicas, int size, boolean metrics) {
        if (size < 1 || size > 32) {
            throw new IllegalArgumentException("Catalog pool size must be between 1 and 32");
        }
        LinkedHashMap<PoolKey, AgroalDataSource> result = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, Endpoint> endpoint : primary.entrySet()) {
                result.put(new PoolKey(endpoint.getKey(), false), pool(endpoint.getValue(), size, metrics));
            }
            for (Map.Entry<String, Endpoint> endpoint : replicas.entrySet()) {
                result.put(new PoolKey(endpoint.getKey(), true), pool(endpoint.getValue(), size, metrics));
            }
            return Map.copyOf(result);
        } catch (SQLException failure) {
            result.values().forEach(AgroalDataSource::close);
            throw new CatalogFailure(CatalogFailure.Reason.UNAVAILABLE);
        }
    }

    static AgroalDataSource pool(Endpoint endpoint, int size) throws SQLException {
        return pool(endpoint, size, false);
    }

    private static AgroalDataSource pool(Endpoint endpoint, int size, boolean metrics) throws SQLException {
        var supplier = new AgroalDataSourceConfigurationSupplier();
        if (metrics) {
            supplier.metricsEnabled();
        }
        return AgroalDataSource.from(supplier
                .connectionPoolConfiguration(configuration -> configuration
                        .initialSize(0).minSize(0).maxSize(size)
                        .acquisitionTimeout(DatabasePhases.ACQUISITION_TIMEOUT)
                        .exceptionSorter(new PostgreSQLExceptionSorter())
                        .establishmentRetryAttempts(0)
                        .connectionFactoryConfiguration(factory -> factory
                                .connectionProviderClass(Driver.class)
                                .jdbcUrl(JdbcUrls.withDriverLimits(endpoint.jdbcUrl()))
                                .principal(new NamePrincipal(endpoint.username()))
                                .credential(new SimplePassword(endpoint.password()))
                                .loginTimeout(Duration.ofSeconds(1))
                                .networkTimeout(DatabasePhases.PHASE_BUDGET)
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

    @Override
    public List<ListedProduct> products(List<Shard> shards, boolean replica, String region, int limit, long after) {
        DatabasePhases.Deadline deadline = new DatabasePhases.Deadline(nanoTime,
                nanoTime.getAsLong() + DatabasePhases.PHASE_BUDGET.toNanos());
        List<ListedProduct> products = new ArrayList<>();
        try {
            for (Shard shard : shards) {
                products.addAll(execute(shard, replica, true, Duration.ofNanos(deadline.remainingNanos()),
                        session -> session.products(region, limit, after)));
            }
            deadline.remainingNanos();
            return List.copyOf(products);
        } catch (SQLException failure) {
            throw new CatalogFailure(replica ? CatalogFailure.Reason.REPLICA_UNAVAILABLE : CatalogFailure.Reason.UNAVAILABLE);
        }
    }

    private <T> T execute(Shard shard, boolean replica, boolean readOnly, Function<Session, T> work) {
        return execute(shard, replica, readOnly, DatabasePhases.PHASE_BUDGET, work);
    }

    private <T> T execute(Shard shard, boolean replica, boolean readOnly, Duration budget, Function<Session, T> work) {
        CatalogFailure.Reason unavailable = replica
                ? CatalogFailure.Reason.REPLICA_UNAVAILABLE
                : CatalogFailure.Reason.UNAVAILABLE;
        AgroalDataSource pool = pools.get(new PoolKey(shard.clusterName(), replica));
        if (pool == null) {
            throw new CatalogFailure(unavailable);
        }
        DatabaseTimings timings = readOnly && diagnostics != null ? diagnostics.timings(replica) : DatabaseTimings.NONE;
        return phases.run(DatabasePhases.Pool.of(pool, () -> pool.flush(AgroalDataSource.FlushMode.ALL)), readOnly,
                budget,
                (connection, deadline) -> work.apply(new JdbcCatalogSession(connection, deadline, unavailable, timings)),
                CatalogFailure.class, failure -> new CatalogFailure(unavailable), timings);
    }

    @Override
    public void close() {
        phases.close();
        pools.values().forEach(AgroalDataSource::close);
    }
}
