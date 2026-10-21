package dev.nklip.javacraft.shardshop.order.persistence;

import dev.nklip.javacraft.shardshop.common.jdbc.DatabasePhases;
import dev.nklip.javacraft.shardshop.common.jdbc.Endpoint;
import dev.nklip.javacraft.shardshop.order.ordering.OrderFailure;
import dev.nklip.javacraft.shardshop.order.stock.StockFailure;
import dev.nklip.javacraft.shardshop.order.stock.StockStore.Saga;
import dev.nklip.javacraft.shardshop.sharding.Shard;
import io.agroal.api.AgroalDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.postgresql.Driver;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static dev.nklip.javacraft.shardshop.order.ordering.OrderModel.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Order's pool, failure categories and catalog budget; the shared phase engine has its own tests in common. */
class JdbcOrderStoreTest {
    private static final Shard SHARD = new Shard("shard-a", "US");
    private final AgroalDataSource primary = mock();
    private final Connection connection = mock();
    private final ExecutorService workers = mock();
    private final ExecutorService cleanup = mock();
    private JdbcOrderStore store;

    @BeforeEach
    void setup() throws SQLException {
        PreparedStatement statement = mock();
        ResultSet rows = mock();
        when(primary.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true);
        when(workers.submit(org.mockito.ArgumentMatchers.<Callable<Object>>any())).thenAnswer(invocation -> {
            Callable<?> work = invocation.getArgument(0);
            CompletableFuture<Object> result = new CompletableFuture<>();
            try {
                result.complete(work.call());
            } catch (Exception failure) {
                result.completeExceptionally(failure);
            }
            return result;
        });
        doAnswer(invocation -> {
            Runnable work = invocation.getArgument(0);
            work.run();
            return null;
        }).when(cleanup).execute(any());
        store = new JdbcOrderStore(Map.of("shard-a", primary), new DatabasePhases(workers, cleanup, () -> 0));
    }

    @Test
    void writesAndReadsCommitOnTheOnlyPrimaryAndReleaseTheirConnections() throws SQLException {
        assertEquals("created", store.write(SHARD, session -> "created"));
        assertEquals("read", store.read(SHARD, session -> "read"));
        verify(primary, times(2)).getConnection();
        verify(connection).setReadOnly(false);
        verify(connection).setReadOnly(true);
        verify(connection, times(2)).setAutoCommit(false);
        verify(connection, times(2)).commit();
        verify(connection, times(2)).close();
        verify(connection, never()).rollback();
    }

    @Test
    void listingUsesOneShrinkingDatabasePhaseAcrossEveryRegionalShard() throws SQLException {
        AtomicLong time = new AtomicLong();
        store = regionalStore(time);
        doAnswer(invocation -> { time.compareAndSet(0, 1_200_000_000L); return null; }).when(connection).commit();

        assertEquals(List.of(), store.orders(List.of(SHARD, new Shard("shard-d", "US")),
                "US", null, null, SortDirection.DESC, 26, null));

        verify(primary, times(2)).getConnection();
        verify(connection, atLeastOnce()).setNetworkTimeout(any(), eq(1800));
        verify(connection, times(2)).setReadOnly(true);
    }

    @Test
    void laterShardsCannotStartAnotherFullPhaseOrReturnPartialResults() throws SQLException {
        AtomicLong time = new AtomicLong();
        store = regionalStore(time);
        doAnswer(invocation -> { time.set(2_100_000_000L); return null; }).when(connection).commit();

        assertEquals(OrderFailure.Reason.ORDER_STORE_UNAVAILABLE, assertThrows(OrderFailure.class,
                () -> store.orders(List.of(SHARD, new Shard("shard-d", "US")), "US", 11L, null,
                        SortDirection.ASC, 26, null)).reason());

        verify(primary).getConnection();
    }

    @Test
    void expiredFinalShardAndMissingRequiredShardBothFailWholeListing() throws SQLException {
        AtomicLong time = new AtomicLong();
        store = regionalStore(time);
        doAnswer(invocation -> { time.set(3_100_000_000L); return null; }).when(connection).commit();
        assertEquals(OrderFailure.Reason.ORDER_STORE_UNAVAILABLE, assertThrows(OrderFailure.class,
                () -> store.orders(List.of(SHARD), "US", null, null, SortDirection.DESC, 26, null)).reason());

        time.set(0);
        assertEquals(OrderFailure.Reason.ORDER_STORE_UNAVAILABLE, assertThrows(OrderFailure.class,
                () -> store.orders(List.of(new Shard("missing", "US")), "US", null, null,
                        SortDirection.DESC, 26, null)).reason());
        verify(primary).getConnection();
    }

    private JdbcOrderStore regionalStore(AtomicLong time) throws SQLException {
        PreparedStatement orders = mock();
        ResultSet rows = mock();
        when(connection.prepareStatement(startsWith("WITH page"))).thenReturn(orders);
        when(orders.executeQuery()).thenReturn(rows);
        return new JdbcOrderStore(Map.of("shard-a", primary, "shard-d", primary),
                new DatabasePhases(workers, cleanup, time::get), time::get);
    }

    @Test
    void unknownCommitResultIsUnavailableWithoutRetryOrSensitiveDetails() throws SQLException {
        doThrow(new SQLException("password=secret")).when(connection).commit();
        AtomicInteger mutations = new AtomicInteger();
        OrderFailure failure = assertThrows(OrderFailure.class,
                () -> store.write(SHARD, session -> mutations.incrementAndGet()));
        assertEquals(OrderFailure.Reason.ORDER_STORE_UNAVAILABLE, failure.reason());
        assertEquals("ORDER_STORE_UNAVAILABLE", failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(1, mutations.get());
        verify(primary).getConnection();
        verify(connection).rollback();
    }

    @Test
    void domainFailureRollsBackEveryMutationAndKeepsItsReason() throws SQLException {
        OrderFailure expected = new OrderFailure(OrderFailure.Reason.ORDER_ID_CONFLICT);
        assertSame(expected, assertThrows(OrderFailure.class,
                () -> store.write(SHARD, session -> { throw expected; })));
        verify(connection).rollback();
        verify(connection, never()).commit();
        verify(connection).close();
    }

    @Test
    void sagaInsertFailureRollsBackTheOrderAndItsSnapshotsTogether() throws SQLException {
        PreparedStatement order = mock();
        PreparedStatement item = mock();
        PreparedStatement saga = mock();
        when(connection.prepareStatement(startsWith("INSERT INTO ordering.orders("))).thenReturn(order);
        when(connection.prepareStatement(startsWith("INSERT INTO ordering.order_items("))).thenReturn(item);
        when(connection.prepareStatement(startsWith("INSERT INTO ordering.order_sagas("))).thenReturn(saga);
        when(order.executeUpdate()).thenReturn(1);
        when(item.executeUpdate()).thenReturn(1);
        when(saga.executeUpdate()).thenThrow(new SQLException("unique command"));
        assertEquals(OrderFailure.Reason.ORDER_STORE_UNAVAILABLE, assertThrows(OrderFailure.class,
                () -> store.write(SHARD, session -> {
                    session.insertOrder(new Order(31, 41, "fp", "USD", new BigDecimal("39.90"),
                                    "PENDING_STOCK", null),
                            List.of(new ItemSnapshot(0, 51, 61, 2, "Product", new BigDecimal("19.95"),
                                    new BigDecimal("12.50"), "USD")), 71, 81);
                    return null;
                })).reason());
        verify(order).executeUpdate();
        verify(item).executeUpdate();
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @Test
    void acquisitionFailureDistinguishesOrderAndCatalogStoreWithoutFallback() throws SQLException {
        when(primary.getConnection()).thenThrow(new SQLException("host=secret"));
        assertEquals(OrderFailure.Reason.ORDER_STORE_UNAVAILABLE,
                assertThrows(OrderFailure.class, () -> store.read(SHARD, session -> "read")).reason());
        assertEquals(OrderFailure.Reason.CATALOG_UNAVAILABLE,
                assertThrows(OrderFailure.class,
                        () -> store.product(SHARD, 1, 2, Duration.ofSeconds(2))).reason());
        verify(primary, times(2)).getConnection();
    }

    @Test
    void missingShardFailsImmediately() {
        assertEquals(OrderFailure.Reason.ORDER_STORE_UNAVAILABLE, assertThrows(OrderFailure.class,
                () -> store.write(new Shard("missing", "EU"), session -> "write")).reason());
        assertEquals(OrderFailure.Reason.CATALOG_UNAVAILABLE, assertThrows(OrderFailure.class,
                () -> store.product(new Shard("missing", "EU"), 1, 2, Duration.ofSeconds(2))).reason());
        verifyNoInteractions(workers, primary);
    }

    @Test
    void abortFailureFlushesTheAgroalPool() throws SQLException {
        doThrow(new SQLException("rollback failed")).when(connection).rollback();
        doThrow(new SQLException("abort failed")).when(connection).abort(any());
        OrderFailure expected = new OrderFailure(OrderFailure.Reason.ORDER_ID_CONFLICT);
        assertSame(expected, assertThrows(OrderFailure.class, () -> store.write(SHARD, session -> {
            throw expected;
        })));
        verify(primary).flush(AgroalDataSource.FlushMode.ALL);
        verify(connection, never()).close();
    }

    @Test
    void catalogSharedDeadlineCoversDispatchAndCancelsALateConnection() throws Exception {
        Future<Object> future = mock();
        when(future.get(anyLong(), eq(TimeUnit.NANOSECONDS))).thenThrow(new TimeoutException());
        when(workers.submit(org.mockito.ArgumentMatchers.<Callable<Object>>any())).thenReturn(future);
        assertEquals(OrderFailure.Reason.CATALOG_UNAVAILABLE, assertThrows(OrderFailure.class,
                () -> store.product(SHARD, 1, 2, Duration.ofMillis(1500))).reason());
        verify(future).get(1_500_000_000L, TimeUnit.NANOSECONDS);
        verify(future).cancel(true);
        ArgumentCaptor<Callable<Object>> pending = ArgumentCaptor.captor();
        verify(workers).submit(pending.capture());
        assertThrows(SQLException.class, () -> pending.getValue().call());
        verify(connection).abort(any());
        verify(connection, never()).setAutoCommit(anyBoolean());
    }

    @Test
    void expiredCatalogDeadlineNeverBorrowsAConnection() {
        assertEquals(OrderFailure.Reason.CATALOG_UNAVAILABLE, assertThrows(OrderFailure.class,
                () -> store.product(SHARD, 1, 2, Duration.ZERO)).reason());
        assertEquals(OrderFailure.Reason.CATALOG_UNAVAILABLE, assertThrows(OrderFailure.class,
                () -> store.product(SHARD, 1, 2, Duration.ofMillis(900))).reason());
        verifyNoInteractions(primary);
    }

    @Test
    void poolsStartEmptyWithBoundedAcquisitionAndDriverTimeouts() throws SQLException {
        var endpoint = new Endpoint(
                "jdbc:postgresql://localhost/shardshop?connectTimeout=0&socketTimeout=0", "order_app", "secret");
        try (var pool = JdbcOrderStore.pool(endpoint, 4)) {
            var config = pool.getConfiguration().connectionPoolConfiguration();
            assertEquals(0, config.initialSize());
            assertEquals(0, config.minSize());
            assertEquals(4, config.maxSize());
            assertEquals(Duration.ofSeconds(1), config.acquisitionTimeout());
            Properties parameters = Driver.parseURL(config.connectionFactoryConfiguration().jdbcUrl(), new Properties());
            assertNotNull(parameters);
            assertEquals("1", parameters.getProperty("connectTimeout"));
            assertEquals("3", parameters.getProperty("socketTimeout"));
            assertEquals("1", parameters.getProperty("loginTimeout"));
            assertEquals("1", parameters.getProperty("cancelSignalTimeout"));
            assertTrue(config.exceptionSorter().isFatal(new SQLException("closed", "08003")));
            assertFalse(config.exceptionSorter().isFatal(new SQLException("duplicate", "23505")));
        }
        assertThrows(IllegalArgumentException.class, () -> new JdbcOrderStore(Map.of(), 0));
        assertThrows(IllegalArgumentException.class, () -> new JdbcOrderStore(Map.of(), 33));
    }

    @Test
    void shutdownClosesPoolsAndExecutors() {
        store.close();
        verify(workers).shutdownNow();
        verify(primary).close();
        verify(cleanup).shutdown();
    }

    @Test
    void stockForeignKeyFailureRollsBackAndNeverMasqueradesAsOutOfStock() throws SQLException {
        PreparedStatement insert = mock();
        when(connection.prepareStatement(startsWith("INSERT INTO catalog.stock_reservations "))).thenReturn(insert);
        when(insert.executeUpdate()).thenThrow(new SQLException("sensitive foreign key data", "23503"));
        StockFailure failure = assertThrows(StockFailure.class,
                () -> store.reserve(SHARD, 41, stockSaga().items()));
        assertEquals(StockFailure.Reason.UNAVAILABLE, failure.reason());
        assertEquals("UNAVAILABLE", failure.getMessage());
        assertNull(failure.getCause());
        verify(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        verify(connection).rollback();
        verify(connection, never()).commit();
        verify(connection).close();
    }

    @Test
    void unknownReservationCommitOutcomeRemainsUnavailableAndCanBeSafelyRetried() throws SQLException {
        PreparedStatement insert = mock();
        PreparedStatement decrement = mock();
        when(connection.prepareStatement(startsWith("INSERT INTO catalog.stock_reservations "))).thenReturn(insert);
        when(connection.prepareStatement(startsWith("UPDATE catalog.products SET stock = stock -"))).thenReturn(decrement);
        when(insert.executeUpdate()).thenReturn(1);
        when(decrement.executeUpdate()).thenReturn(1);
        doThrow(new SQLException("password=secret")).when(connection).commit();
        StockFailure failure = assertThrows(StockFailure.class,
                () -> store.reserve(SHARD, 41, stockSaga().items()));
        assertEquals(StockFailure.Reason.UNAVAILABLE, failure.reason());
        assertNull(failure.getCause());
        verify(insert).executeUpdate();
        verify(decrement).executeUpdate();
        verify(connection).rollback();
        verify(primary).getConnection();
    }

    @Test
    void unknownLedgerTransitionCommitOutcomeDoesNotPublishOrCancelLocally() throws SQLException {
        PreparedStatement transition = mock();
        PreparedStatement order = mock();
        PreparedStatement identity = mock();
        PreparedStatement outbox = mock();
        when(connection.prepareStatement(startsWith("UPDATE ordering.order_sagas"))).thenReturn(transition);
        when(connection.prepareStatement(startsWith("UPDATE ordering.orders"))).thenReturn(order);
        when(connection.prepareStatement(startsWith("INSERT INTO ordering.order_result_ids"))).thenReturn(identity);
        when(connection.prepareStatement(startsWith("INSERT INTO ordering.order_outbox"))).thenReturn(outbox);
        for (PreparedStatement statement : List.of(transition, order, identity, outbox)) {
            when(statement.executeUpdate()).thenReturn(1);
        }
        doThrow(new SQLException("unknown outcome")).when(connection).commit();
        AtomicInteger ids = new AtomicInteger(91);
        StockFailure failure = assertThrows(StockFailure.class,
                () -> store.pendingLedger(SHARD, stockSaga(), ids::incrementAndGet));
        assertEquals(StockFailure.Reason.UNAVAILABLE, failure.reason());
        assertNull(failure.getCause());
        assertEquals(93, ids.get());
        verify(transition).executeUpdate();
        verify(order).executeUpdate();
        verify(identity).executeUpdate();
        verify(outbox).executeUpdate();
        verify(connection).rollback();
        verify(primary).getConnection();
    }

    @Test
    void outboxInsertFailureRollsBackBothStatusesAndPermanentTransportBinding() throws SQLException {
        PreparedStatement transition = mock();
        PreparedStatement order = mock();
        PreparedStatement identity = mock();
        PreparedStatement outbox = mock();
        when(connection.prepareStatement(startsWith("UPDATE ordering.order_sagas"))).thenReturn(transition);
        when(connection.prepareStatement(startsWith("UPDATE ordering.orders"))).thenReturn(order);
        when(connection.prepareStatement(startsWith("INSERT INTO ordering.order_result_ids"))).thenReturn(identity);
        when(connection.prepareStatement(startsWith("INSERT INTO ordering.order_outbox"))).thenReturn(outbox);
        for (PreparedStatement statement : List.of(transition, order, identity)) {
            when(statement.executeUpdate()).thenReturn(1);
        }
        when(outbox.executeUpdate()).thenThrow(new SQLException("outbox storage unavailable"));
        AtomicInteger ids = new AtomicInteger(91);
        assertEquals(StockFailure.Reason.UNAVAILABLE, assertThrows(StockFailure.class,
                () -> store.pendingLedger(SHARD, stockSaga(), ids::incrementAndGet)).reason());
        verify(transition).executeUpdate();
        verify(order).executeUpdate();
        verify(identity).executeUpdate();
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    private Saga stockSaga() {
        return new Saga(71, 81, new Order(31, 41, "a".repeat(64), "USD", new BigDecimal("39.90"),
                "PENDING_STOCK", null), List.of(new ItemSnapshot(0, 51, 61, 2, "Product",
                new BigDecimal("19.95"), new BigDecimal("12.50"), "USD")), 1, 0);
    }

}
