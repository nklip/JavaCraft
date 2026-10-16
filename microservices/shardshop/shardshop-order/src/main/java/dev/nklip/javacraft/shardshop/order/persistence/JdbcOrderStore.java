package dev.nklip.javacraft.shardshop.order.persistence;

import dev.nklip.javacraft.shardshop.common.jdbc.DatabasePhases;
import dev.nklip.javacraft.shardshop.common.jdbc.Endpoint;
import dev.nklip.javacraft.shardshop.common.jdbc.JdbcUrls;
import dev.nklip.javacraft.shardshop.order.ordering.OrderFailure;
import dev.nklip.javacraft.shardshop.order.ordering.OrderStore;
import dev.nklip.javacraft.shardshop.order.ordering.OrderModel.Product;
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
import java.util.Optional;
import java.util.function.Function;

/** Bounded per-endpoint pools and a hard deadline for the entire database phase. */
public final class JdbcOrderStore implements OrderStore, AutoCloseable {
    private final Map<String, AgroalDataSource> pools;
    private final DatabasePhases phases;

    public JdbcOrderStore(Map<String, Endpoint> primary, int maxPoolSize) {
        this.pools = pools(primary, maxPoolSize);
        this.phases = new DatabasePhases("order", Math.max(1, Math.multiplyExact(pools.size(), maxPoolSize)));
    }

    JdbcOrderStore(Map<String, AgroalDataSource> pools, DatabasePhases phases) {
        this.pools = Map.copyOf(pools);
        this.phases = phases;
    }

    private static Map<String, AgroalDataSource> pools(Map<String, Endpoint> primary, int size) {
        if (size < 1 || size > 32) {
            throw new IllegalArgumentException("Order pool size must be between 1 and 32");
        }
        LinkedHashMap<String, AgroalDataSource> result = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, Endpoint> endpoint : primary.entrySet()) {
                result.put(endpoint.getKey(), pool(endpoint.getValue(), size));
            }
            return Map.copyOf(result);
        } catch (SQLException failure) {
            result.values().forEach(AgroalDataSource::close);
            throw new OrderFailure(OrderFailure.Reason.ORDER_STORE_UNAVAILABLE);
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
    public <T> T read(Shard shard, Function<Session, T> work) {
        return execute(shard, true, DatabasePhases.PHASE_BUDGET, OrderFailure.Reason.ORDER_STORE_UNAVAILABLE,
                session -> work.apply(session));
    }

    @Override
    public <T> T write(Shard shard, Function<Session, T> work) {
        return execute(shard, false, DatabasePhases.PHASE_BUDGET, OrderFailure.Reason.ORDER_STORE_UNAVAILABLE,
                session -> work.apply(session));
    }

    @Override
    public Optional<Product> product(Shard shard, long sellerId, long productId, Duration remaining) {
        if (remaining.isNegative() || remaining.isZero()) {
            throw new OrderFailure(OrderFailure.Reason.CATALOG_UNAVAILABLE);
        }
        Duration budget = remaining.compareTo(DatabasePhases.PHASE_BUDGET) >= 0 ? DatabasePhases.PHASE_BUDGET : remaining;
        return execute(shard, true, budget, OrderFailure.Reason.CATALOG_UNAVAILABLE,
                session -> session.product(sellerId, productId));
    }

    private <T> T execute(Shard shard, boolean readOnly, Duration budget, OrderFailure.Reason unavailable,
                          Function<JdbcOrderSession, T> work) {
        AgroalDataSource pool = pools.get(shard.clusterName());
        if (pool == null) {
            throw new OrderFailure(unavailable);
        }
        return phases.run(DatabasePhases.Pool.of(pool, () -> pool.flush(AgroalDataSource.FlushMode.ALL)), readOnly,
                budget, (connection, deadline) -> work.apply(new JdbcOrderSession(connection, deadline, unavailable)),
                OrderFailure.class, () -> new OrderFailure(unavailable));
    }

    @Override
    public void close() {
        phases.close();
        pools.values().forEach(AgroalDataSource::close);
    }
}
