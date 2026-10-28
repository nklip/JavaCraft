package dev.nklip.javacraft.shardshop.common.jdbc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DatabasePhasesTest {
    private DataSource dataSource;
    private Runnable flush;
    private Connection connection;
    private PreparedStatement timeouts;
    private ExecutorService workers;
    private ExecutorService cleanup;
    private DatabasePhases phases;
    private DatabasePhases.Pool pool;

    @BeforeEach
    void prepare() throws SQLException {
        dataSource = mock();
        flush = mock();
        connection = mock();
        workers = mock();
        cleanup = mock();
        timeouts = mock();
        ResultSet result = mock();
        when(dataSource.getConnection()).thenReturn(connection);
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
        pool = DatabasePhases.Pool.of(dataSource, flush);
        phases = new DatabasePhases(workers, cleanup, () -> 0);
    }

    @Test
    void commitsEachPhaseWithBoundedTimeoutsAndReleasesItsConnection() throws SQLException {
        assertEquals("written", run(false, (connection, deadline) -> "written"));
        assertEquals("read", run(true, (connection, deadline) -> "read"));
        verify(connection).setReadOnly(false);
        verify(connection).setReadOnly(true);
        verify(connection, times(2)).setAutoCommit(false);
        verify(connection, atLeast(4)).setNetworkTimeout(any(), eq(3_000));
        verify(timeouts, times(2)).setString(1, "3000");
        verify(timeouts, times(2)).setString(2, "3000");
        verify(connection, times(2)).commit();
        verify(connection, times(2)).close();
        verify(connection, never()).rollback();
    }

    @Test
    void domainFailureRollsBackAndStaysUnchanged() throws SQLException {
        Conflict expected = new Conflict();
        assertSame(expected, assertThrows(Conflict.class, () -> run(false, (connection, deadline) -> {
            throw expected;
        })));
        verify(connection).rollback();
        verify(connection, never()).commit();
        verify(connection).close();
    }

    @Test
    void otherFailuresBecomeUnavailableWithoutDetails() throws SQLException {
        assertUnavailable(() -> run(false, (connection, deadline) -> {
            throw new SQLException("password=secret");
        }));
        assertUnavailable(() -> run(false, (connection, deadline) -> {
            throw new IllegalStateException("host=secret");
        }));
        verify(connection, times(2)).rollback();
    }

    /** Step 4.5 regression: a background consumer must log why its transaction failed. */
    @Test
    void aClassifyingCallerReceivesTheFailureThatMadeThePhaseUnavailable() throws Exception {
        SQLException statement = new SQLException("column does not exist", "42703");
        AtomicReference<Throwable> received = new AtomicReference<>();
        Unavailable failure = assertThrows(Unavailable.class, () -> phases.run(pool, false,
                DatabasePhases.PHASE_BUDGET, (connection, deadline) -> {
                    throw statement;
                }, Conflict.class, cause -> {
                    received.set(cause);
                    return new Unavailable();
                }));
        assertSame(statement, received.get());
        assertNull(failure.getCause());
        verify(connection).rollback();

        Future<Object> late = mock();
        TimeoutException timeout = new TimeoutException();
        when(late.get(anyLong(), eq(TimeUnit.NANOSECONDS))).thenThrow(timeout);
        when(workers.submit(org.mockito.ArgumentMatchers.<Callable<Object>>any())).thenReturn(late);
        assertThrows(Unavailable.class, () -> phases.run(pool, false, DatabasePhases.PHASE_BUDGET,
                (connection, deadline) -> "late", Conflict.class, cause -> {
                    received.set(cause);
                    return new Unavailable();
                }));
        assertSame(timeout, received.get());
    }

    @Test
    void unknownCommitResultIsUnavailableAndNeverRetriesTheWork() throws SQLException {
        doThrow(new SQLException("unknown commit; password=secret")).when(connection).commit();
        AtomicLong writes = new AtomicLong();
        assertUnavailable(() -> run(false, (connection, deadline) -> writes.incrementAndGet()));
        assertEquals(1, writes.get());
        verify(connection).rollback();
        verify(dataSource).getConnection();
    }

    @Test
    void failedRollbackDiscardsTheConnectionAndKeepsTheDomainFailure() throws SQLException {
        doThrow(new SQLException("rollback failed")).when(connection).rollback();
        Conflict expected = new Conflict();
        assertSame(expected, assertThrows(Conflict.class, () -> run(false, (connection, deadline) -> {
            throw expected;
        })));
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
        assertThrows(Conflict.class, () -> run(false, (connection, deadline) -> {
            throw new Conflict();
        }));
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
        assertThrows(Conflict.class, () -> run(false, (connection, deadline) -> {
            throw new Conflict();
        }));
        verify(connection).abort(any());
        verify(connection, never()).close();
    }

    @Test
    void abortFailureFlushesThePool() throws SQLException {
        doThrow(new SQLException("rollback failed")).when(connection).rollback();
        doThrow(new SQLException("abort failed")).when(connection).abort(any());
        assertThrows(Conflict.class, () -> run(false, (connection, deadline) -> {
            throw new Conflict();
        }));
        verify(flush).run();
        verify(connection, never()).close();
    }

    @Test
    void timeoutCancelsTheWorkBeforeALateConnectionCanBeUsed() throws Exception {
        Future<Object> future = mock();
        when(future.get(anyLong(), eq(TimeUnit.NANOSECONDS))).thenThrow(new TimeoutException());
        when(workers.submit(org.mockito.ArgumentMatchers.<Callable<Object>>any())).thenReturn(future);
        assertUnavailable(() -> phases.run(pool, false, Duration.ofMillis(1500), (connection, deadline) -> "late",
                Conflict.class, Unavailable::new));
        verify(future).get(1_500_000_000L, TimeUnit.NANOSECONDS);
        verify(future).cancel(true);
        ArgumentCaptor<Callable<Object>> callable = ArgumentCaptor.captor();
        verify(workers).submit(callable.capture());
        assertThrows(SQLException.class, () -> callable.getValue().call());
        verify(connection).abort(any());
        verify(connection, never()).setAutoCommit(anyBoolean());
        verify(connection, never()).close();
    }

    @Test
    void interruptedCallerKeepsItsInterruptAndCancelsTheWork() throws Exception {
        Future<Object> future = mock();
        when(future.get(anyLong(), eq(TimeUnit.NANOSECONDS))).thenThrow(new InterruptedException());
        when(workers.submit(org.mockito.ArgumentMatchers.<Callable<Object>>any())).thenReturn(future);
        try {
            assertUnavailable(() -> run(false, (connection, deadline) -> "unexpected"));
            assertTrue(Thread.currentThread().isInterrupted());
            verify(future).cancel(true);
        } finally {
            assertTrue(Thread.interrupted());
        }
    }

    @Test
    void theBudgetIncludesDispatchAndAcquisitionMustStillFitIt() throws SQLException {
        AtomicLong time = new AtomicLong();
        DatabasePhases delayed = new DatabasePhases(workers, cleanup, () -> time.getAndAdd(2_500_000_000L));
        assertUnavailable(() -> delayed.run(pool, false, DatabasePhases.PHASE_BUDGET,
                (connection, deadline) -> "unexpected", Conflict.class, Unavailable::new));
        assertUnavailable(() -> phases.run(pool, true, Duration.ofMillis(900),
                (connection, deadline) -> "unexpected", Conflict.class, Unavailable::new));
        verify(dataSource, never()).getConnection();
    }

    @Test
    void deadlinesNeverAllowZeroOrExpiredNetworkTimeouts() throws SQLException {
        assertEquals(1, new DatabasePhases.Deadline(() -> 0, 1_000_000L).remainingMillis());
        assertThrows(SQLException.class, () -> new DatabasePhases.Deadline(() -> 0, 0).remainingNanos());
        assertThrows(SQLException.class, () -> new DatabasePhases.Deadline(() -> 0, 999_999L).remainingMillis());
    }

    @Test
    void closedPhasesRejectNewWorkAsUnavailable() {
        when(workers.submit(org.mockito.ArgumentMatchers.<Callable<Object>>any())).thenThrow(new RejectedExecutionException());
        assertUnavailable(() -> run(false, (connection, deadline) -> "unexpected"));
        phases.close();
        verify(workers).shutdownNow();
        verify(cleanup).shutdown();
        verifyNoInteractions(dataSource);
    }

    /** Step 4.1 regression: a burst larger than the pool waits for a connection; before, it got 503 at once. */
    @Test
    void aBurstLargerThanThePoolWaitsForAConnectionInsteadOfARejection() throws Exception {
        Semaphore permits = new Semaphore(1);
        AtomicInteger open = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        Connection shared = mock();
        when(shared.prepareStatement(anyString())).thenReturn(timeouts);
        doAnswer(invocation -> {
            open.decrementAndGet();
            permits.release();
            return null;
        }).when(shared).close();
        DatabasePhases.Pool single = new DatabasePhases.Pool() {
            @Override
            public Connection getConnection() throws SQLException {
                try {
                    if (!permits.tryAcquire(DatabasePhases.ACQUISITION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                        throw new SQLException("Pool acquisition timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("Interrupted");
                }
                maximum.accumulateAndGet(open.incrementAndGet(), Math::max);
                return shared;
            }

            @Override
            public void flush() {
                fail("No connection failed");
            }
        };
        try (DatabasePhases real = new DatabasePhases("burst", 1);
             var clients = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> results = new ArrayList<>();
            for (int client = 0; client < 32; client++) {
                results.add(clients.submit(() -> {
                    start.await();
                    return real.run(single, false, DatabasePhases.PHASE_BUDGET, (connection, deadline) -> {
                        LockSupport.parkNanos(Duration.ofMillis(5).toNanos());
                        return "done";
                    }, Conflict.class, Unavailable::new);
                }));
            }
            start.countDown();
            for (Future<String> result : results) {
                assertEquals("done", result.get(10, TimeUnit.SECONDS));
            }
        }
        assertEquals(1, maximum.get());
        verify(shared, times(32)).commit();
    }

    @Test
    void diagnosticPhasesSeparateAcquisitionSetupWorkAndCommit() throws SQLException {
        AtomicLong clock = new AtomicLong();
        record Event(DatabaseTimings.Phase phase, long nanos, boolean success) { }
        List<Event> events = new ArrayList<>();
        var timings = new DatabaseTimings(clock::get,
                (phase, nanos, success) -> events.add(new Event(phase, nanos, success)));
        when(dataSource.getConnection()).thenAnswer(invocation -> {
            clock.addAndGet(11);
            return connection;
        });
        doAnswer(invocation -> { clock.addAndGet(13); return null; }).when(connection).setAutoCommit(false);
        when(timeouts.executeQuery()).thenAnswer(invocation -> {
            clock.addAndGet(17);
            ResultSet result = mock();
            when(result.next()).thenReturn(true);
            return result;
        });
        doAnswer(invocation -> { clock.addAndGet(19); return null; }).when(connection).commit();

        assertEquals("read", phases.run(pool, true, DatabasePhases.PHASE_BUDGET, (connection, deadline) -> {
            clock.addAndGet(23);
            return "read";
        }, Conflict.class, failure -> new Unavailable(), timings));

        assertEquals(List.of(new Event(DatabaseTimings.Phase.DISPATCH, 0, true),
                new Event(DatabaseTimings.Phase.ACQUISITION, 11, true),
                new Event(DatabaseTimings.Phase.CONNECTION_SETUP, 13, true),
                new Event(DatabaseTimings.Phase.WORK, 23, true),
                new Event(DatabaseTimings.Phase.PRE_COMMIT_SETUP, 17, true),
                new Event(DatabaseTimings.Phase.COMMIT, 19, true)), events);
        verify(connection).close();
        verify(connection, never()).rollback();
    }

    private <T> T run(boolean readOnly, DatabasePhases.Work<T> work) {
        return phases.run(pool, readOnly, DatabasePhases.PHASE_BUDGET, work, Conflict.class, Unavailable::new);
    }

    private static void assertUnavailable(org.junit.jupiter.api.function.Executable executable) {
        Unavailable failure = assertThrows(Unavailable.class, executable);
        assertNull(failure.getMessage());
        assertNull(failure.getCause());
    }

    private static final class Conflict extends RuntimeException {
    }

    private static final class Unavailable extends RuntimeException {
    }
}
