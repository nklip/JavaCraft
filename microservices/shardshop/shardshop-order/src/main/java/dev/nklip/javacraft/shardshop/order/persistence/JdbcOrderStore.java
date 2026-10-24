package dev.nklip.javacraft.shardshop.order.persistence;

import dev.nklip.javacraft.shardshop.common.jdbc.DatabasePhases;
import dev.nklip.javacraft.shardshop.common.jdbc.Endpoint;
import dev.nklip.javacraft.shardshop.common.jdbc.JdbcUrls;
import dev.nklip.javacraft.shardshop.order.ordering.OrderFailure;
import dev.nklip.javacraft.shardshop.order.ordering.OrderStore;
import dev.nklip.javacraft.shardshop.order.ordering.OrderListingStore;
import dev.nklip.javacraft.shardshop.order.ordering.OrderModel.ListedOrder;
import dev.nklip.javacraft.shardshop.order.ordering.OrderModel.OrderCursor;
import dev.nklip.javacraft.shardshop.order.ordering.OrderModel.SortDirection;
import dev.nklip.javacraft.shardshop.order.ordering.OrderModel.Product;
import dev.nklip.javacraft.shardshop.order.ordering.OrderModel.ItemSnapshot;
import dev.nklip.javacraft.shardshop.order.stock.StockFailure;
import dev.nklip.javacraft.shardshop.order.stock.StockStore;
import dev.nklip.javacraft.shardshop.order.result.ResultStore;
import dev.nklip.javacraft.shardshop.order.result.ResultFailure;
import dev.nklip.javacraft.shardshop.order.reconciliation.ReconciliationFailure;
import dev.nklip.javacraft.shardshop.order.reconciliation.ReconciliationStore;
import dev.nklip.javacraft.shardshop.order.profit.ProfitStore;
import dev.nklip.javacraft.shardshop.order.profit.ProfitFailure;
import dev.nklip.javacraft.shardshop.sharding.Shard;
import io.agroal.api.AgroalDataSource;
import io.agroal.api.configuration.supplier.AgroalDataSourceConfigurationSupplier;
import io.agroal.api.exceptionsorter.PostgreSQLExceptionSorter;
import io.agroal.api.security.NamePrincipal;
import io.agroal.api.security.SimplePassword;
import org.postgresql.Driver;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** Bounded per-endpoint pools and a hard deadline for the entire database phase. */
public final class JdbcOrderStore implements OrderStore, OrderListingStore, StockStore, ResultStore, ProfitStore,
        ReconciliationStore, AutoCloseable {
    private final Map<String, AgroalDataSource> pools;
    private final DatabasePhases phases;
    private final LongSupplier nanoTime;

    public JdbcOrderStore(Map<String, Endpoint> primary, int maxPoolSize) {
        this.pools = pools(primary, maxPoolSize);
        this.phases = new DatabasePhases("order", Math.max(1, Math.multiplyExact(pools.size(), maxPoolSize)));
        this.nanoTime = System::nanoTime;
    }

    JdbcOrderStore(Map<String, AgroalDataSource> pools, DatabasePhases phases) {
        this(pools, phases, System::nanoTime);
    }

    JdbcOrderStore(Map<String, AgroalDataSource> pools, DatabasePhases phases, LongSupplier nanoTime) {
        this.pools = Map.copyOf(pools);
        this.phases = phases;
        this.nanoTime = nanoTime;
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
                                .jdbcUrl(JdbcUrls.withDriverLimits(endpoint.jdbcUrl()) + "&logServerErrorDetail=false")
                                .principal(new NamePrincipal(endpoint.username()))
                                .credential(new SimplePassword(endpoint.password()))
                                .loginTimeout(Duration.ofSeconds(1))
                                .networkTimeout(DatabasePhases.PHASE_BUDGET)
                        )
                )
        );
    }

    @Override
    public <T> T read(Shard shard, Function<OrderStore.Session, T> work) {
        return execute(shard, true, DatabasePhases.PHASE_BUDGET, OrderFailure.Reason.ORDER_STORE_UNAVAILABLE,
                session -> work.apply(session));
    }

    @Override
    public <T> T write(Shard shard, Function<OrderStore.Session, T> work) {
        return execute(shard, false, DatabasePhases.PHASE_BUDGET, OrderFailure.Reason.ORDER_STORE_UNAVAILABLE,
                session -> work.apply(session));
    }

    @Override
    public List<ListedOrder> orders(List<Shard> shards, String region, Long productId, String productName,
                                    SortDirection sort, int limit, OrderCursor after) {
        DatabasePhases.Deadline deadline = new DatabasePhases.Deadline(nanoTime,
                nanoTime.getAsLong() + DatabasePhases.PHASE_BUDGET.toNanos());
        List<ListedOrder> result = new ArrayList<>();
        try {
            for (Shard shard : shards) {
                result.addAll(execute(shard, true, Duration.ofNanos(deadline.remainingNanos()),
                        OrderFailure.Reason.ORDER_STORE_UNAVAILABLE,
                        session -> session.orders(region, productId, productName, sort, limit, after)));
            }
            deadline.remainingNanos();
            return List.copyOf(result);
        } catch (SQLException failure) {
            throw new OrderFailure(OrderFailure.Reason.ORDER_STORE_UNAVAILABLE);
        }
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
    public Optional<StockStore.Saga> claim(Shard shard, Duration lease) {
        return stock(shard, session -> session.claim(lease));
    }

    @Override
    public boolean renew(Shard shard, StockStore.Saga saga, Duration lease) {
        return stock(shard, session -> session.renew(saga, lease));
    }

    @Override
    public void reserve(Shard shard, long orderId, List<ItemSnapshot> items) {
        stock(shard, session -> { session.reserve(orderId, items); return null; });
    }

    @Override
    public void release(Shard shard, long orderId, List<ItemSnapshot> items) {
        stock(shard, session -> { session.release(orderId, items); return null; });
    }

    @Override
    public boolean cancel(Shard shard, StockStore.Saga saga) {
        return stock(shard, session -> session.cancel(saga));
    }

    @Override
    public boolean pendingLedger(Shard shard, StockStore.Saga saga, LongSupplier ids) {
        return stock(shard, session -> session.pendingLedger(saga, ids));
    }

    @Override
    public boolean released(Shard shard, StockStore.Saga saga) {
        return stock(shard, session -> session.released(saga));
    }

    @Override
    public void retry(Shard shard, StockStore.Saga saga, Duration delay) {
        stock(shard, session -> { session.retry(saga, delay); return null; });
    }

    @FunctionalInterface
    private interface StockWork<T> {
        T apply(JdbcStockSession session) throws SQLException;
    }

    private <T> T stock(Shard shard, StockWork<T> work) {
        AgroalDataSource pool = pools.get(shard.clusterName());
        if (pool == null) {
            throw new StockFailure(StockFailure.Reason.UNAVAILABLE);
        }
        return phases.run(DatabasePhases.Pool.of(pool, () -> pool.flush(AgroalDataSource.FlushMode.ALL)), false,
                DatabasePhases.PHASE_BUDGET, (connection, deadline) -> {
                    connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                    return work.apply(new JdbcStockSession(connection, deadline, Clock.systemUTC()));
                }, StockFailure.class, () -> new StockFailure(StockFailure.Reason.UNAVAILABLE));
    }

    @Override
    public <T> T result(Shard shard, Function<ResultStore.Session, T> work) {
        AgroalDataSource pool = pools.get(shard.clusterName());
        if (pool == null) {
            throw new ResultFailure(null);
        }
        return phases.run(DatabasePhases.Pool.of(pool, () -> pool.flush(AgroalDataSource.FlushMode.ALL)), false,
                DatabasePhases.PHASE_BUDGET, (connection, deadline) -> {
                    connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                    return work.apply(new JdbcResultSession(connection, deadline));
                }, ResultFailure.class, ResultFailure::new);
    }

    @Override
    public List<ReconciliationStore.Candidate> pendingReconciliation(Shard shard, Instant now,
                                                                      long afterSagaId, int limit) {
        if (limit <= 0 || afterSagaId < 0) {
            throw new IllegalArgumentException("Invalid reconciliation page");
        }
        return reconciliationRead(shard, session -> session.pending(now, afterSagaId, limit));
    }

    @Override
    public long exhaustedReconciliations(Shard shard) {
        return reconciliationRead(shard, JdbcReconciliationSession::exhausted);
    }

    @Override
    public <T> T reconciliation(Shard shard, Function<ReconciliationStore.Session, T> work) {
        return reconciliationPhase(shard, false, (connection, deadline) ->
                work.apply(new JdbcReconciliationSession(connection, deadline)));
    }

    @FunctionalInterface
    private interface ReconciliationWork<T> {
        T apply(Connection connection, DatabasePhases.Deadline deadline) throws SQLException;
    }

    @FunctionalInterface
    private interface ReconciliationRead<T> {
        T apply(JdbcReconciliationSession session) throws SQLException;
    }

    private <T> T reconciliationRead(Shard shard, ReconciliationRead<T> work) {
        return reconciliationPhase(shard, true, (connection, deadline) ->
                work.apply(new JdbcReconciliationSession(connection, deadline)));
    }

    private <T> T reconciliationPhase(Shard shard, boolean readOnly, ReconciliationWork<T> work) {
        AgroalDataSource pool = pools.get(shard.clusterName());
        if (pool == null) {
            throw new ReconciliationFailure(new SQLException("Order shard unavailable"));
        }
        return phases.run(DatabasePhases.Pool.of(pool, () -> pool.flush(AgroalDataSource.FlushMode.ALL)), readOnly,
                DatabasePhases.PHASE_BUDGET, (connection, deadline) -> {
                    connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                    return work.apply(connection, deadline);
                }, ReconciliationFailure.class, ReconciliationFailure::new);
    }

    /** Reuses the order pool and one three-second phase for bounded transport cleanup SQL. */
    public <T> T transportCleanupPhase(Shard shard, boolean readOnly, DatabasePhases.Work<T> work) {
        AgroalDataSource pool = pools.get(shard.clusterName());
        if (pool == null) {
            throw new IllegalStateException("Order shard unavailable");
        }
        return phases.run(DatabasePhases.Pool.of(pool, () -> pool.flush(AgroalDataSource.FlushMode.ALL)), readOnly,
                DatabasePhases.PHASE_BUDGET, (connection, deadline) -> {
                    connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                    return work.run(connection, deadline);
                }, IllegalStateException.class, failure -> new IllegalStateException("Transport cleanup SQL failed", failure));
    }

    @Override
    public List<PendingCredit> pendingProfit(Shard shard, int limit) {
        return profit(shard, session -> session.pendingProfit(limit));
    }

    @Override
    public void creditProfit(Shard shard, PendingCredit credit) {
        profit(shard, session -> { session.creditProfit(credit); return null; });
    }

    @Override
    public void markProfitApplied(Shard shard, PendingCredit credit) {
        profit(shard, session -> { session.markProfitApplied(credit); return null; });
    }

    @Override
    public void retryProfit(Shard shard, PendingCredit credit, Duration delay) {
        profit(shard, session -> { session.retryProfit(credit, delay); return null; });
    }

    @Override
    public void blockProfit(Shard shard, PendingCredit credit, ProfitFailure.Reason reason) {
        profit(shard, session -> { session.blockProfit(credit, reason); return null; });
    }

    @FunctionalInterface
    private interface ProfitWork<T> {
        T apply(JdbcProfitSession session) throws SQLException;
    }

    private <T> T profit(Shard shard, ProfitWork<T> work) {
        AgroalDataSource pool = pools.get(shard.clusterName());
        if (pool == null) {
            throw new ProfitFailure(ProfitFailure.Reason.UNAVAILABLE);
        }
        return phases.run(DatabasePhases.Pool.of(pool, () -> pool.flush(AgroalDataSource.FlushMode.ALL)), false,
                DatabasePhases.PHASE_BUDGET, (connection, deadline) -> {
                    connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                    return work.apply(new JdbcProfitSession(connection, deadline));
                }, ProfitFailure.class, ProfitFailure::from);
    }

    @Override
    public void close() {
        phases.close();
        pools.values().forEach(AgroalDataSource::close);
    }
}
