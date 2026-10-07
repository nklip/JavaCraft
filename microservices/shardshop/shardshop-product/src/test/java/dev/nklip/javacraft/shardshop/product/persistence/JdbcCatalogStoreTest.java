package dev.nklip.javacraft.shardshop.product.persistence;

import dev.nklip.javacraft.shardshop.product.catalog.CatalogFailure;
import dev.nklip.javacraft.shardshop.sharding.Shard;
import io.agroal.api.AgroalDataSource;
import io.agroal.api.configuration.supplier.AgroalDataSourceConfigurationSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.postgresql.Driver;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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
                new JdbcCatalogStore.PoolKey("shard-a", true), replica), workers, cleanup, () -> 0);
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
    void failedRollbackDiscardsTheConnectionAndPreservesDomainFailure() throws SQLException {
        doThrow(new SQLException("rollback failed")).when(connection).rollback();
        CatalogFailure expected = new CatalogFailure(CatalogFailure.Reason.PRODUCT_CONFLICT);
        assertSame(expected, assertThrows(CatalogFailure.class,
                () -> store.write(SHARD, session -> { throw expected; })));
        verify(connection).abort(any());
        verify(connection, never()).close();
    }

    @Test
    void lateCleanupCannotAbortAnAlreadyReleasedLease() throws SQLException {
        AtomicReference<Runnable> queued = new AtomicReference<>();
        doAnswer(invocation -> {
            queued.set(invocation.getArgument(0));
            return null;
        }).when(cleanup).execute(any());
        doThrow(new SQLException("rollback failed")).when(connection).rollback();
        CatalogFailure expected = new CatalogFailure(CatalogFailure.Reason.PRODUCT_CONFLICT);

        assertSame(expected, assertThrows(CatalogFailure.class,
                () -> store.write(SHARD, session -> { throw expected; })));

        verify(connection).abort(any());
        assertNotNull(queued.get());
        queued.get().run();
        verify(connection, times(1)).abort(any());
        verify(connection, never()).close();
    }

    @Test
    void rejectedCleanupStillAbortsOnWorkerRelease() throws SQLException {
        doThrow(new RejectedExecutionException("closed")).when(cleanup).execute(any());
        doThrow(new SQLException("rollback failed")).when(connection).rollback();
        CatalogFailure expected = new CatalogFailure(CatalogFailure.Reason.PRODUCT_CONFLICT);
        assertSame(expected, assertThrows(CatalogFailure.class,
                () -> store.write(SHARD, session -> { throw expected; })));
        verify(connection).abort(any());
        verify(connection, never()).close();
    }

    @Test
    void abortFailureFlushesThePool() throws SQLException {
        doThrow(new SQLException("rollback failed")).when(connection).rollback();
        doThrow(new SQLException("abort failed")).when(connection).abort(any());
        CatalogFailure expected = new CatalogFailure(CatalogFailure.Reason.PRODUCT_CONFLICT);
        assertSame(expected, assertThrows(CatalogFailure.class,
                () -> store.write(SHARD, session -> { throw expected; })));
        verify(primary).flush(AgroalDataSource.FlushMode.ALL);
        verify(connection, never()).close();
    }

    @Test
    void fullWorkerCapacityRejectsImmediately() {
        when(workers.submit(org.mockito.ArgumentMatchers.<Callable<Object>>any()))
                .thenThrow(new RejectedExecutionException("full"));
        CatalogFailure failure = assertThrows(CatalogFailure.class,
                () -> store.write(SHARD, session -> "unexpected"));
        assertEquals(CatalogFailure.Reason.UNAVAILABLE, failure.reason());
        verifyNoInteractions(primary, replica);
    }

    @Test
    void timeoutCancelsWorkBeforeALateConnectionCanBeUsed() throws Exception {
        Future<Object> future = mock();
        when(future.get(anyLong(), eq(TimeUnit.NANOSECONDS))).thenThrow(new TimeoutException());
        when(workers.submit(org.mockito.ArgumentMatchers.<Callable<Object>>any())).thenReturn(future);
        CatalogFailure failure = assertThrows(CatalogFailure.class,
                () -> store.write(SHARD, session -> "must not execute"));
        assertEquals(CatalogFailure.Reason.UNAVAILABLE, failure.reason());
        verify(future).get(3_000_000_000L, TimeUnit.NANOSECONDS);
        verify(future).cancel(true);
        ArgumentCaptor<Callable<Object>> callable = ArgumentCaptor.captor();
        verify(workers).submit(callable.capture());
        assertThrows(SQLException.class, () -> callable.getValue().call());
        verify(connection).abort(any());
        verify(connection, never()).setAutoCommit(anyBoolean());
        verify(connection, never()).close();
    }

    @Test
    void interruptedCallerPreservesInterruptAndCancelsWork() throws Exception {
        Future<Object> future = mock();
        when(future.get(anyLong(), eq(TimeUnit.NANOSECONDS))).thenThrow(new InterruptedException());
        when(workers.submit(org.mockito.ArgumentMatchers.<Callable<Object>>any())).thenReturn(future);
        try {
            CatalogFailure failure = assertThrows(CatalogFailure.class,
                    () -> store.write(SHARD, session -> "unexpected"));
            assertEquals(CatalogFailure.Reason.UNAVAILABLE, failure.reason());
            assertTrue(Thread.currentThread().isInterrupted());
            verify(future).cancel(true);
        } finally {
            assertTrue(Thread.interrupted());
        }
    }

    @Test
    void budgetIncludesDispatchAndDoesNotRestartForAcquisition() throws SQLException {
        AtomicLong time = new AtomicLong();
        JdbcCatalogStore delayed = new JdbcCatalogStore(
                Map.of(new JdbcCatalogStore.PoolKey("shard-a", false), primary), workers, cleanup,
                () -> time.getAndAdd(2_500_000_000L));
        CatalogFailure failure = assertThrows(CatalogFailure.class,
                () -> delayed.write(SHARD, session -> "unexpected"));
        assertEquals(CatalogFailure.Reason.UNAVAILABLE, failure.reason());
        verify(primary, never()).getConnection();
    }

    @Test
    void deadlinesNeverAllowZeroOrExpiredNetworkTimeouts() throws SQLException {
        assertEquals(1, new JdbcCatalogStore.Deadline(() -> 0, 1_000_000L).remainingMillis());
        assertThrows(SQLException.class, () -> new JdbcCatalogStore.Deadline(() -> 0, 0).remainingNanos());
        assertThrows(SQLException.class,
                () -> new JdbcCatalogStore.Deadline(() -> 0, 999_999L).remainingMillis());
    }

    @Test
    void poolsStartEmptyAndKeepFiniteDriverLimitsEvenWhenUrlOverridesThem() throws SQLException {
        JdbcCatalogStore.Endpoint endpoint = new JdbcCatalogStore.Endpoint(
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
        assertEquals("Endpoint[configured]", endpoint.toString());
    }

    @Test
    void brokenPostgresqlConnectionsAreDiscardedInsteadOfBeingReused() throws SQLException {
        var endpoint = new JdbcCatalogStore.Endpoint("jdbc:postgresql://localhost/a", "user", "secret");
        try (AgroalDataSource pool = JdbcCatalogStore.pool(endpoint, 2)) {
            var sorter = pool.getConfiguration().connectionPoolConfiguration().exceptionSorter();
            assertTrue(sorter.isFatal(new SQLException("Connection is closed", "08003")));
            assertTrue(sorter.isFatal(new SQLException("Connection failure", "08006")));
            assertFalse(sorter.isFatal(new SQLException("Unique constraint", "23505")));
        }
    }

    @Test
    void constructorValidatesEndpointsAndBuildsPoolsWithoutOpeningConnections() {
        assertThrows(NullPointerException.class, () -> new JdbcCatalogStore.Endpoint(null, "user", "secret"));
        assertThrows(NullPointerException.class,
                () -> new JdbcCatalogStore.Endpoint("jdbc:postgresql://localhost/a", null, "secret"));
        assertThrows(NullPointerException.class,
                () -> new JdbcCatalogStore.Endpoint("jdbc:postgresql://localhost/a", "user", null));
        assertThrows(IllegalArgumentException.class, () -> new JdbcCatalogStore.Endpoint("wrong", "user", ""));
        assertThrows(IllegalArgumentException.class,
                () -> new JdbcCatalogStore.Endpoint("jdbc:postgresql://localhost/a", " ", ""));
        assertThrows(IllegalArgumentException.class, () -> new JdbcCatalogStore(Map.of(), Map.of(), 0));
        assertThrows(IllegalArgumentException.class, () -> new JdbcCatalogStore(Map.of(), Map.of(), 33));
        var endpoint = new JdbcCatalogStore.Endpoint("jdbc:postgresql://localhost:1/a", "user", "secret");
        try (JdbcCatalogStore constructed = new JdbcCatalogStore(Map.of("a", endpoint), Map.of("a", endpoint), 1)) {
            CatalogFailure failure = assertThrows(CatalogFailure.class,
                    () -> constructed.read(SHARD, false, session -> "unexpected"));
            assertEquals(CatalogFailure.Reason.UNAVAILABLE, failure.reason());
        }
    }

    @Test
    void failedPoolInitializationClosesPoolsAlreadyCreated() {
        var endpoint = new JdbcCatalogStore.Endpoint("jdbc:postgresql://localhost/a", "user", "secret");
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
