package dev.nklip.javacraft.shardshop.product.persistence;

import dev.nklip.javacraft.shardshop.common.jdbc.DatabasePhases;
import dev.nklip.javacraft.shardshop.common.jdbc.Endpoint;
import dev.nklip.javacraft.shardshop.product.catalog.CatalogFailure;
import dev.nklip.javacraft.shardshop.sharding.Shard;
import io.agroal.api.AgroalDataSource;
import io.agroal.api.configuration.supplier.AgroalDataSourceConfigurationSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Product's pools and failure categories; the shared phase engine has its own tests in common. */
class JdbcCatalogStoreTest {
    private static final Shard SHARD = new Shard("shard-a", "US");
    private AgroalDataSource primary;
    private AgroalDataSource replica;
    private Connection connection;
    private ExecutorService workers;
    private ExecutorService cleanup;
    private JdbcCatalogStore store;

    @BeforeEach
    void prepare() throws SQLException {
        primary = mock();
        replica = mock();
        connection = mock();
        workers = mock();
        cleanup = mock();
        PreparedStatement timeouts = mock();
        ResultSet result = mock();
        when(primary.getConnection()).thenReturn(connection);
        when(replica.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(timeouts);
        when(timeouts.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(workers.submit(org.mockito.ArgumentMatchers.<Callable<Object>>any())).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            CompletableFuture<Object> future = new CompletableFuture<>();
            try {
                future.complete(callable.call());
            } catch (Exception failure) {
                future.completeExceptionally(failure);
            }
            return future;
        });
        doAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(0);
            runnable.run();
            return null;
        }).when(cleanup).execute(any());
        store = new JdbcCatalogStore(Map.of(new JdbcCatalogStore.PoolKey("shard-a", false), primary,
                new JdbcCatalogStore.PoolKey("shard-a", true), replica), new DatabasePhases(workers, cleanup, () -> 0));
    }

    @Test
    void writesCommitAndPrimaryReadsAlwaysUseThePrimary() throws SQLException {
        assertEquals("created", store.write(SHARD, session -> "created"));
        assertEquals("read", store.read(SHARD, false, session -> "read"));
        verify(primary, times(2)).getConnection();
        verifyNoInteractions(replica);
        verify(connection).setReadOnly(false);
        verify(connection).setReadOnly(true);
        verify(connection, times(2)).setAutoCommit(false);
        verify(connection, times(2)).commit();
        verify(connection, times(2)).close();
        verify(connection, never()).rollback();
    }

    @Test
    void replicaReadsAreStrictAndDoNotCacheResults() throws SQLException {
        assertEquals("first", store.read(SHARD, true, session -> "first"));
        assertEquals("second", store.read(SHARD, true, session -> "second"));
        when(replica.getConnection()).thenThrow(new SQLException("host=secret"));
        CatalogFailure failure = assertThrows(CatalogFailure.class,
                () -> store.read(SHARD, true, session -> "unreachable"));
        assertEquals(CatalogFailure.Reason.REPLICA_UNAVAILABLE, failure.reason());
        verify(replica, times(3)).getConnection();
        verifyNoInteractions(primary);
    }

    @Test
    void missingEndpointsFailWithoutSubmittingWork() {
        CatalogFailure primaryFailure = assertThrows(CatalogFailure.class,
                () -> store.write(new Shard("unknown", "EU"), session -> "unexpected"));
        CatalogFailure replicaFailure = assertThrows(CatalogFailure.class,
                () -> store.read(new Shard("unknown", "EU"), true, session -> "unexpected"));
        assertEquals(CatalogFailure.Reason.UNAVAILABLE, primaryFailure.reason());
        assertEquals(CatalogFailure.Reason.REPLICA_UNAVAILABLE, replicaFailure.reason());
        verifyNoInteractions(workers);
    }

    @Test
    void listingSharesOneShrinkingDatabaseBudgetAcrossAllRegionalShards() throws SQLException {
        AtomicLong time = new AtomicLong();
        store = regionalStore(time);
        doAnswer(invocation -> { time.compareAndSet(0, 1_200_000_000L); return null; }).when(connection).commit();

        assertEquals(List.of(), store.products(List.of(SHARD, new Shard("shard-d", "US")), true, "US", 26, 0));

        verify(replica, times(2)).getConnection();
        verifyNoInteractions(primary);
        verify(connection, atLeastOnce()).setNetworkTimeout(any(), eq(1800));
    }

    @Test
    void listingCannotSpendAnotherFullBudgetOnLaterShardsOrReturnPartialResults() throws SQLException {
        AtomicLong time = new AtomicLong();
        store = regionalStore(time);
        doAnswer(invocation -> { time.set(2_100_000_000L); return null; }).when(connection).commit();

        CatalogFailure failure = assertThrows(CatalogFailure.class,
                () -> store.products(List.of(SHARD, new Shard("shard-d", "US")), true, "US", 26, 0));

        assertEquals(CatalogFailure.Reason.REPLICA_UNAVAILABLE, failure.reason());
        verify(replica).getConnection();
        verifyNoInteractions(primary);
    }

    @Test
    void listingNeverFallsBackWhenAnyRegionalReplicaEndpointIsMissing() {
        CatalogFailure failure = assertThrows(CatalogFailure.class,
                () -> store.products(List.of(new Shard("shard-d", "US"), SHARD), true, "US", 26, 0));
        assertEquals(CatalogFailure.Reason.REPLICA_UNAVAILABLE, failure.reason());
        verifyNoInteractions(primary, replica, workers);
    }

    private JdbcCatalogStore regionalStore(AtomicLong time) throws SQLException {
        PreparedStatement products = mock();
        ResultSet rows = mock();
        when(connection.prepareStatement(startsWith("WITH page"))).thenReturn(products);
        when(products.executeQuery()).thenReturn(rows);
        return new JdbcCatalogStore(Map.of(new JdbcCatalogStore.PoolKey("shard-a", true), replica,
                new JdbcCatalogStore.PoolKey("shard-d", true), replica),
                new DatabasePhases(workers, cleanup, time::get), time::get);
    }

    @Test
    void domainFailureRollsBackAndKeepsItsCategory() throws SQLException {
        CatalogFailure expected = new CatalogFailure(CatalogFailure.Reason.SELLER_CONFLICT);
        CatalogFailure actual = assertThrows(CatalogFailure.class,
                () -> store.write(SHARD, session -> { throw expected; }));
        assertSame(expected, actual);
        verify(connection).rollback();
        verify(connection, never()).commit();
        verify(connection).close();
    }

    @Test
    void commitFailureIsUnavailableAndNeverRetriesTheWrite() throws SQLException {
        doThrow(new SQLException("unknown commit; password=secret")).when(connection).commit();
        AtomicLong writes = new AtomicLong();
        CatalogFailure failure = assertThrows(CatalogFailure.class,
                () -> store.write(SHARD, session -> writes.incrementAndGet()));
        assertEquals(CatalogFailure.Reason.UNAVAILABLE, failure.reason());
        assertEquals("UNAVAILABLE", failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(1, writes.get());
        verify(connection).rollback();
        verify(primary).getConnection();
    }

    @Test
    void abortFailureFlushesTheAgroalPool() throws SQLException {
        doThrow(new SQLException("rollback failed")).when(connection).rollback();
        doThrow(new SQLException("abort failed")).when(connection).abort(any());
        CatalogFailure expected = new CatalogFailure(CatalogFailure.Reason.PRODUCT_CONFLICT);
        assertSame(expected, assertThrows(CatalogFailure.class,
                () -> store.write(SHARD, session -> { throw expected; })));
        verify(primary).flush(AgroalDataSource.FlushMode.ALL);
        verify(connection, never()).close();
    }

    @Test
    void poolsStartEmptyAndKeepFiniteDriverLimitsEvenWhenUrlOverridesThem() throws SQLException {
        Endpoint endpoint = new Endpoint(
                "jdbc:postgresql://localhost/catalog?connectTimeout=0&socketTimeout=0", "user", "secret");
        try (AgroalDataSource pool = JdbcCatalogStore.pool(endpoint, 4)) {
            var configuration = pool.getConfiguration().connectionPoolConfiguration();
            assertEquals(0, configuration.initialSize());
            assertEquals(0, configuration.minSize());
            assertEquals(4, configuration.maxSize());
            assertEquals(0, configuration.establishmentRetryAttempts());
            assertEquals(Duration.ofSeconds(1), configuration.acquisitionTimeout());
            var factory = configuration.connectionFactoryConfiguration();
            Properties parameters = Driver.parseURL(factory.jdbcUrl(), new Properties());
            assertNotNull(parameters);
            assertEquals("1", parameters.getProperty("connectTimeout"));
            assertEquals("3", parameters.getProperty("socketTimeout"));
            assertEquals("1", parameters.getProperty("loginTimeout"));
            assertEquals("1", parameters.getProperty("cancelSignalTimeout"));
            assertEquals(Duration.ofSeconds(1), factory.loginTimeout());
            assertEquals(Duration.ofSeconds(3), factory.networkTimeout());
        }
    }

    @Test
    void brokenPostgresqlConnectionsAreDiscardedInsteadOfBeingReused() throws SQLException {
        var endpoint = new Endpoint("jdbc:postgresql://localhost/a", "user", "secret");
        try (AgroalDataSource pool = JdbcCatalogStore.pool(endpoint, 2)) {
            var sorter = pool.getConfiguration().connectionPoolConfiguration().exceptionSorter();
            assertTrue(sorter.isFatal(new SQLException("Connection is closed", "08003")));
            assertTrue(sorter.isFatal(new SQLException("Connection failure", "08006")));
            assertFalse(sorter.isFatal(new SQLException("Unique constraint", "23505")));
        }
    }

    @Test
    void constructorValidatesPoolSizesAndBuildsPoolsWithoutOpeningConnections() {
        assertThrows(IllegalArgumentException.class, () -> new JdbcCatalogStore(Map.of(), Map.of(), 0));
        assertThrows(IllegalArgumentException.class, () -> new JdbcCatalogStore(Map.of(), Map.of(), 33));
        var endpoint = new Endpoint("jdbc:postgresql://localhost:1/a", "user", "secret");
        try (JdbcCatalogStore constructed = new JdbcCatalogStore(Map.of("a", endpoint), Map.of("a", endpoint), 1)) {
            CatalogFailure failure = assertThrows(CatalogFailure.class,
                    () -> constructed.read(SHARD, false, session -> "unexpected"));
            assertEquals(CatalogFailure.Reason.UNAVAILABLE, failure.reason());
        }
    }

    @Test
    void failedPoolInitializationClosesPoolsAlreadyCreated() {
        var endpoint = new Endpoint("jdbc:postgresql://localhost/a", "user", "secret");
        try (var factory = mockStatic(AgroalDataSource.class)) {
            factory.when(() -> AgroalDataSource.from(any(AgroalDataSourceConfigurationSupplier.class)))
                    .thenReturn(primary).thenThrow(new SQLException("initialization failed"));
            CatalogFailure failure = assertThrows(CatalogFailure.class,
                    () -> new JdbcCatalogStore(Map.of("a", endpoint), Map.of("a", endpoint), 1));
            assertEquals(CatalogFailure.Reason.UNAVAILABLE, failure.reason());
            verify(primary).close();
        }
    }

    @Test
    void shutdownClosesEveryPoolAndExecutor() {
        store.close();
        verify(workers).shutdownNow();
        verify(primary).close();
        verify(replica).close();
        verify(cleanup).shutdown();
    }
}
