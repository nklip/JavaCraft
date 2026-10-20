package dev.nklip.javacraft.shardshop.testing.kind;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KindDrillGuardTest {
    @TempDir Path snapshot;
    private KindLab lab;
    private String lockOwner;
    private boolean loseCreateResponse;

    @BeforeEach
    void api() throws Exception {
        lab = mock(KindLab.class);
        when(lab.snapshot()).thenReturn(snapshot);
        when(lab.kubectl(any(String[].class))).thenAnswer(invocation -> {
            String[] args = (String[]) invocation.getRawArguments()[0];
            if (args[0].equals("get")) {
                assertEquals(List.of("get", "configmap", KindDrillGuard.LOCK, "--ignore-not-found", "-o", "json"), List.of(args));
                return lockOwner == null ? "" : "{\"metadata\":{\"name\":\"" + KindDrillGuard.LOCK + "\"}}";
            }
            assertEquals(List.of("create", "-f"), List.of(args[0], args[1]));
            if (lockOwner != null) throw new IOException("AlreadyExists");
            lockOwner = KindLab.parse(Files.readString(Path.of(args[2])))
                    .path("metadata").path("labels").path("shardshop.javacraft/drill-owner").asText();
            if (loseCreateResponse) throw new IOException("Response lost after creation");
            return "created";
        });
        when(lab.kubectl(any(Duration.class), any(String[].class))).thenAnswer(invocation -> {
            String[] args = (String[]) invocation.getRawArguments()[1];
            List<String> command = List.of(args);
            assertTrue(command.contains("--field-selector=metadata.name=" + KindDrillGuard.LOCK));
            String selector = command.get(command.indexOf("-l") + 1);
            if (selector.equals("shardshop.javacraft/drill-owner=" + lockOwner)) lockOwner = null;
            return "";
        });
    }

    @Test
    void assertionFailureRestoresChangesBeforeReleasingTheLock() throws Exception {
        List<String> restored = new ArrayList<>();
        assertThrows(AssertionError.class, () -> {
            try (var guard = new KindDrillGuard(lab).acquire()) {
                guard.onClose("drop probe", () -> { restored.add("drop"); return true; });
                guard.onClose("unfence", () -> { restored.add("unfence"); return true; });
                throw new AssertionError("drill failed");
            }
        });
        assertEquals(List.of("unfence", "drop"), restored);
        assertNull(lockOwner);
    }

    @Test
    void failedRecoveryStillRunsRemainingRecoveryAndRetainsTheLock() throws Exception {
        List<String> restored = new ArrayList<>();
        var guard = new KindDrillGuard(lab).acquire();
        guard.onClose("drop probe", () -> { restored.add("drop"); return true; });
        guard.onClose("unfence", () -> { throw new IOException("API unavailable"); });
        IOException failure = assertThrows(IOException.class, guard::close);
        assertTrue(failure.getMessage().contains("unfence"));
        assertTrue(failure.getMessage().contains(KindDrillGuard.LOCK));
        assertEquals(List.of("drop"), restored);
        assertNotNull(lockOwner);
    }

    @Test
    void runningDrillOrRetainedLockStopsReleasesUntilTheLockIsDeleted() throws Exception {
        KindDrillGuard.requireIdle(lab);
        var guard = new KindDrillGuard(lab).acquire();
        guard.onClose("restore ledger allowlist environment", () -> false);
        assertThrows(IOException.class, () -> KindDrillGuard.requireIdle(lab), "A running drill must stop a release");
        assertThrows(IOException.class, guard::close);
        IOException retained = assertThrows(IOException.class, () -> KindDrillGuard.requireIdle(lab),
                "Helm would keep the unrecovered environment change");
        assertTrue(retained.getMessage().contains(KindDrillGuard.LOCK));
        lockOwner = null; // The operator recovered the reported changes and deleted the lock.
        KindDrillGuard.requireIdle(lab);
    }

    @Test
    void incompleteRecoveryCannotSilentlyReleaseTheLock() throws Exception {
        var guard = new KindDrillGuard(lab).acquire();
        guard.onClose("restore reader", () -> false);
        assertThrows(IOException.class, guard::close);
        assertNotNull(lockOwner);
    }

    @Test
    void competingDrillCannotRemoveTheFirstDrillsLock() throws Exception {
        try (var first = new KindDrillGuard(lab).acquire()) {
            String owner = lockOwner;
            assertThrows(IOException.class, () -> new KindDrillGuard(lab).acquire());
            assertEquals(owner, lockOwner);
        }
        assertNull(lockOwner);
    }

    @Test
    void uncertainCreateRemovesOnlyItsOwnLock() {
        loseCreateResponse = true;
        assertThrows(IOException.class, () -> new KindDrillGuard(lab).acquire());
        assertNull(lockOwner);
    }

    @Test
    void interruptionDoesNotPreventRecoveryAndInterruptStatusIsPreserved() throws Exception {
        List<String> restored = new ArrayList<>();
        var guard = new KindDrillGuard(lab).acquire();
        guard.onClose("unfence", () -> {
            assertFalse(Thread.currentThread().isInterrupted());
            restored.add("unfence");
            return true;
        });
        Thread.currentThread().interrupt();
        try {
            guard.close();
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(List.of("unfence"), restored);
            assertNull(lockOwner);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void recoveryCannotOvertakeAnInFlightMutationOrAllowMutationsAfterClose() throws Exception {
        var guard = new KindDrillGuard(lab).acquire();
        AtomicBoolean temporaryResource = new AtomicBoolean();
        guard.onClose("delete temporary resource", () -> { temporaryResource.set(false); return true; });
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finishMutation = new CountDownLatch(1);
        FutureTask<Void> mutation = new FutureTask<>(() -> {
            guard.run(() -> {
                entered.countDown();
                if (!finishMutation.await(5, TimeUnit.SECONDS)) throw new IOException("Controlled mutation stalled");
                temporaryResource.set(true);
            });
            return null;
        });
        Thread.ofVirtual().start(mutation);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        CountDownLatch closeStarted = new CountDownLatch(1);
        FutureTask<Void> recovery = new FutureTask<>(() -> {
            closeStarted.countDown();
            guard.close();
            return null;
        });
        Thread.ofVirtual().start(recovery);
        try {
            assertTrue(closeStarted.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> recovery.get(100, TimeUnit.MILLISECONDS),
                    "Recovery must wait for the in-flight mutation to settle");
        } finally {
            finishMutation.countDown();
            mutation.get(5, TimeUnit.SECONDS);
            recovery.get(5, TimeUnit.SECONDS);
        }
        assertFalse(temporaryResource.get());
        assertNull(lockOwner);
        assertThrows(IllegalStateException.class, () -> guard.run(() -> temporaryResource.set(true)));
    }

    @Test
    void shutdownInterruptsTheDrillAndWaitsForUnwindingBeforeRecovery() throws Exception {
        var guard = new KindDrillGuard(lab).acquire();
        AtomicBoolean inFlight = new AtomicBoolean();
        AtomicBoolean recovered = new AtomicBoolean();
        guard.onClose("restore after command", () -> {
            assertFalse(inFlight.get(), "Command must have unwound before recovery");
            recovered.set(true);
            return true;
        });
        CountDownLatch entered = new CountDownLatch(1);
        FutureTask<Boolean> mutation = new FutureTask<>(() -> {
            try {
                guard.run(() -> {
                    inFlight.set(true);
                    entered.countDown();
                    try { new CountDownLatch(1).await(); }
                    finally { inFlight.set(false); }
                });
                return false;
            } catch (InterruptedException expected) {
                return true;
            }
        });
        Thread.ofVirtual().start(mutation);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        FutureTask<Void> shutdown = new FutureTask<>(() -> { guard.recoverOnShutdown(); return null; });
        Thread.ofVirtual().start(shutdown);
        shutdown.get(5, TimeUnit.SECONDS);
        assertTrue(mutation.get(5, TimeUnit.SECONDS));
        assertTrue(recovered.get());
        assertNull(lockOwner);
    }
}
