package dev.nklip.javacraft.shardshop.product.persistence;

import dev.nklip.javacraft.shardshop.common.jdbc.DatabasePhases;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/** Bounded per-endpoint pools and a hard deadline for the entire database phase. */
public final class JdbcCatalogStore implements CatalogStore, AutoCloseable {
    record PoolKey(String shard, boolean replica) {
    }

    private final Map<PoolKey, AgroalDataSource> pools;
    private final DatabasePhases phases;

    public JdbcCatalogStore(Map<String, Endpoint> primary, Map<String, Endpoint> replicas, int maxPoolSize) {
        this.pools = pools(primary, replicas, maxPoolSize);
        this.phases = new DatabasePhases("catalog", Math.max(1, Math.multiplyExact(pools.size(), maxPoolSize)));
    }

    JdbcCatalogStore(Map<PoolKey, AgroalDataSource> pools, DatabasePhases phases) {
        this.pools = Map.copyOf(pools);
        this.phases = phases;
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
        return AgroalDataSource.from(new AgroalDataSourceConfigurationSupplier()
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

    private <T> T execute(Shard shard, boolean replica, boolean readOnly, Function<Session, T> work) {
        CatalogFailure.Reason unavailable = replica
                ? CatalogFailure.Reason.REPLICA_UNAVAILABLE
                : CatalogFailure.Reason.UNAVAILABLE;
        AgroalDataSource pool = pools.get(new PoolKey(shard.clusterName(), replica));
        if (pool == null) {
            throw new CatalogFailure(unavailable);
        }
        return phases.run(DatabasePhases.Pool.of(pool, () -> pool.flush(AgroalDataSource.FlushMode.ALL)), readOnly,
                DatabasePhases.PHASE_BUDGET,
                (connection, deadline) -> work.apply(new JdbcCatalogSession(connection, deadline, unavailable)),
                CatalogFailure.class, () -> new CatalogFailure(unavailable));
    }

    @Override
    public void close() {
        phases.close();
        pools.values().forEach(AgroalDataSource::close);
    }
}
