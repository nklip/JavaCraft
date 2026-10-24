package dev.nklip.javacraft.shardshop.ledger.cleanup;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LedgerTransportCleanupTest {
    private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void fullBatchImmediatelyContinuesAndUsesRetentionCutoff() throws Exception {
        TransportCleanupStore store = mock(TransportCleanupStore.class);
        CountDownLatch nextPass = new CountDownLatch(1);
        AtomicInteger passes = new AtomicInteger();
        Instant cutoff = NOW.minus(Duration.ofHours(24));
        when(store.cleanup(cutoff, 2)).thenAnswer(call -> {
            if (passes.incrementAndGet() == 2) {
                nextPass.countDown();
                return new TransportCleanupStore.Counts(0, 0);
            }
            return new TransportCleanupStore.Counts(2, 0);
        });

        try (var cleanup = new LedgerTransportCleanup(store,
                new LedgerTransportCleanup.Settings(true, Duration.ofHours(24), Duration.ofSeconds(60), 2), CLOCK)) {
            cleanup.start();
            assertTrue(nextPass.await(2, TimeUnit.SECONDS), "A full batch waited for the poll interval");
        }
        verify(store, times(2)).cleanup(cutoff, 2);
    }

    @Test
    void failuresRetryAndARecoveredCycleCanContinue() throws Exception {
        TransportCleanupStore store = mock(TransportCleanupStore.class);
        CountDownLatch recovered = new CountDownLatch(1);
        AtomicInteger passes = new AtomicInteger();
        when(store.cleanup(any(), eq(2))).thenAnswer(call -> {
            if (passes.incrementAndGet() < 3) {
                throw new IllegalStateException("database unavailable");
            }
            recovered.countDown();
            return new TransportCleanupStore.Counts(0, 0);
        });
        try (var cleanup = new LedgerTransportCleanup(store,
                new LedgerTransportCleanup.Settings(true, Duration.ofSeconds(1), Duration.ofMillis(100), 2), CLOCK)) {
            cleanup.start();
            assertTrue(recovered.await(2, TimeUnit.SECONDS));
        }
        assertTrue(passes.get() >= 3);
    }

    @Test
    void disabledOrUnstartedCleanupNeverTouchesStorageAndSettingsAreBounded() {
        TransportCleanupStore store = mock(TransportCleanupStore.class);
        var settings = new LedgerTransportCleanup.Settings(false, Duration.ofHours(24),
                Duration.ofSeconds(30), 1_000);
        try (var cleanup = new LedgerTransportCleanup(store, settings, CLOCK)) {
            cleanup.start();
            cleanup.start();
        }
        verifyNoInteractions(store);

        assertThrows(IllegalArgumentException.class, () -> new LedgerTransportCleanup.Settings(
                true, Duration.ofMillis(999), Duration.ofSeconds(30), 100));
        assertThrows(IllegalArgumentException.class, () -> new LedgerTransportCleanup.Settings(
                true, Duration.ofDays(366), Duration.ofSeconds(30), 100));
        assertThrows(IllegalArgumentException.class, () -> new LedgerTransportCleanup.Settings(
                true, Duration.ofHours(24), Duration.ofMillis(99), 100));
        assertThrows(IllegalArgumentException.class, () -> new LedgerTransportCleanup.Settings(
                true, Duration.ofHours(24), Duration.ofSeconds(61), 100));
        assertThrows(IllegalArgumentException.class, () -> new LedgerTransportCleanup.Settings(
                true, Duration.ofHours(24), Duration.ofSeconds(30), 0));
        assertThrows(IllegalArgumentException.class, () -> new LedgerTransportCleanup.Settings(
                true, Duration.ofHours(24), Duration.ofSeconds(30), 1_001));
    }
}
