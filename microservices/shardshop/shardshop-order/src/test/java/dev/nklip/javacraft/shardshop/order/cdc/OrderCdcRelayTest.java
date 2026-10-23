package dev.nklip.javacraft.shardshop.order.cdc;

import io.debezium.engine.DebeziumEngine;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderCdcRelayTest {
    /** Connector cleanup owns a replication socket after the engine begins shutting down. Interrupting
     * that cleanup can leave the socket alive and make every subsequent slot acquisition fail. */
    @Test
    void shutdownLetsEngineCleanupFinishWithoutInterruptingIt() throws Exception {
        DebeziumEngine<?> engine = mock(DebeziumEngine.class);
        CountDownLatch cleanupStarted = new CountDownLatch(1);
        CountDownLatch closeRequested = new CountDownLatch(1);
        CountDownLatch finishCleanup = new CountDownLatch(1);
        AtomicBoolean cleanupInterrupted = new AtomicBoolean();
        doAnswer(ignored -> { closeRequested.countDown(); return null; }).when(engine).close();
        Thread running = Thread.ofPlatform().daemon().start(() -> {
            cleanupStarted.countDown();
            while (finishCleanup.getCount() != 0) {
                try {
                    finishCleanup.await();
                } catch (InterruptedException interrupted) {
                    cleanupInterrupted.set(true);
                }
            }
        });
        Thread stopping = Thread.ofVirtual().unstarted(() -> OrderCdcRelay.stopEngine(engine, running));
        try {
            assertTrue(cleanupStarted.await(3, TimeUnit.SECONDS));
            stopping.start();
            assertTrue(closeRequested.await(3, TimeUnit.SECONDS));
            awaitJoining(stopping);
            assertFalse(cleanupInterrupted.get() || running.isInterrupted(),
                    "Engine cleanup must retain its chance to close the replication socket");
            assertTrue(stopping.isAlive(), "Shutdown must wait while connector cleanup is still running");
        } finally {
            finishCleanup.countDown();
            running.join(3_000);
            stopping.join(3_000);
        }
        assertFalse(running.isAlive());
        assertFalse(stopping.isAlive());
    }

    @Test
    void supervisorInterruptionDoesNotAbortCleanupAndIsRestoredAfterward() throws Exception {
        DebeziumEngine<?> engine = mock(DebeziumEngine.class);
        CountDownLatch closeRequested = new CountDownLatch(1);
        CountDownLatch finishCleanup = new CountDownLatch(1);
        AtomicBoolean interruptedOnClose = new AtomicBoolean();
        AtomicBoolean interruptedAfterCleanup = new AtomicBoolean();
        doAnswer(ignored -> {
            interruptedOnClose.set(Thread.currentThread().isInterrupted());
            closeRequested.countDown();
            return null;
        }).when(engine).close();
        Thread running = Thread.ofPlatform().daemon().start(() -> {
            try {
                finishCleanup.await();
            } catch (InterruptedException unexpected) {
                throw new AssertionError("Engine cleanup interrupted", unexpected);
            }
        });
        Thread stopping = Thread.ofVirtual().start(() -> {
            Thread.currentThread().interrupt();
            OrderCdcRelay.stopEngine(engine, running);
            interruptedAfterCleanup.set(Thread.currentThread().isInterrupted());
        });
        try {
            assertTrue(closeRequested.await(3, TimeUnit.SECONDS));
            awaitJoining(stopping);
            stopping.interrupt();
            finishCleanup.countDown();
            stopping.join(3_000);
            assertFalse(stopping.isAlive());
            assertFalse(interruptedOnClose.get(), "Debezium must close with a clear interrupt status");
            assertTrue(interruptedAfterCleanup.get(), "Restore the supervisor's interruption after cleanup");
        } finally {
            finishCleanup.countDown();
            running.join(3_000);
            stopping.join(3_000);
        }
    }

    @Test
    void closeRejectedDuringTaskStartupIsRetriedWithoutInterruptingTheEngine() throws Exception {
        DebeziumEngine<?> engine = mock(DebeziumEngine.class);
        CountDownLatch finishCleanup = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread running = Thread.ofPlatform().daemon().start(() -> {
            try {
                finishCleanup.await();
            } catch (InterruptedException unexpected) {
                interrupted.set(true);
            }
        });
        doThrow(new IllegalStateException("Cannot stop engine while tasks are starting"))
                .doAnswer(ignored -> { finishCleanup.countDown(); return null; }).when(engine).close();
        Thread stopping = Thread.ofVirtual().start(() -> OrderCdcRelay.stopEngine(engine, running));
        try {
            stopping.join(4_000);
            assertFalse(stopping.isAlive(), "A rejected early close must be retried after startup");
            assertFalse(interrupted.get());
            verify(engine, times(2)).close();
        } finally {
            finishCleanup.countDown();
            running.join(3_000);
            stopping.join(3_000);
        }
    }

    private static void awaitJoining(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (thread.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(Thread.State.TIMED_WAITING, thread.getState());
    }
}
