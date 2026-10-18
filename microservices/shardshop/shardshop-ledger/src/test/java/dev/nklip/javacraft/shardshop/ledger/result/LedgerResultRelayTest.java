package dev.nklip.javacraft.shardshop.ledger.result;

import dev.nklip.javacraft.shardshop.common.amqp.ConfirmedPublisher;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LedgerResultRelayTest {
    private static final PendingResult RESULT = new PendingResult(42, 3, "{\"messageId\": \"42\", \"currency\": \"€\"}");
    private static final byte[] SAVED = RESULT.envelope().getBytes(StandardCharsets.UTF_8);
    private static final LedgerResultRelay.Settings FAST = new LedgerResultRelay.Settings(true, 1,
            Duration.ofMillis(20));

    @Test
    void publishesBeforeConditionallyMarkingAndImmediatelyReadsAnotherFullBatch() throws Exception {
        ResultOutbox outbox = mock(ResultOutbox.class);
        ConfirmedPublisher publisher = mock(ConfirmedPublisher.class);
        CountDownLatch marked = new CountDownLatch(1);
        CountDownLatch repolled = new CountDownLatch(1);
        when(outbox.pending(1)).thenAnswer(call -> {
            if (marked.getCount() == 0) {
                repolled.countDown();
                return List.of();
            }
            return List.of(RESULT);
        });
        when(outbox.markPublished(42, 3)).thenAnswer(call -> {
            marked.countDown();
            return false; // A concurrent rearm is harmless to this attempt.
        });
        // The next read of a full batch must not wait for this 30-second poll interval.
        var slowPolls = new LedgerResultRelay.Settings(true, 1, Duration.ofSeconds(30));
        try (var relay = new LedgerResultRelay(outbox, () -> publisher, slowPolls)) {
            relay.start();
            assertTrue(marked.await(2, TimeUnit.SECONDS));
            assertTrue(repolled.await(2, TimeUnit.SECONDS), "A full batch waited for the poll interval");
        }
        var order = inOrder(outbox, publisher);
        order.verify(outbox).pending(1);
        // The reserved result ID and the exact UTF-8 bytes of the saved envelope.
        order.verify(publisher).publish(eq("42"), aryEq(SAVED));
        order.verify(outbox).markPublished(42, 3);
        verify(outbox, atLeast(2)).pending(1);
    }

    @Test
    void failedPublishLeavesRowPendingAndUsesFreshStreamForRetry() throws Exception {
        ResultOutbox outbox = mock(ResultOutbox.class);
        ConfirmedPublisher failed = mock(ConfirmedPublisher.class);
        ConfirmedPublisher recovered = mock(ConfirmedPublisher.class);
        CountDownLatch published = new CountDownLatch(1);
        AtomicInteger streams = new AtomicInteger();
        when(outbox.pending(1)).thenReturn(List.of(RESULT), List.of(RESULT), List.of());
        doThrow(new IllegalStateException("broker unavailable")).when(failed).publish(eq("42"), any());
        when(outbox.markPublished(42, 3)).thenAnswer(call -> {
            published.countDown();
            return true;
        });
        try (var relay = new LedgerResultRelay(outbox,
                () -> streams.getAndIncrement() == 0 ? failed : recovered, FAST)) {
            relay.start();
            assertTrue(published.await(3, TimeUnit.SECONDS));
        }
        verify(failed).close();
        verify(failed).publish(eq("42"), aryEq(SAVED));
        verify(recovered).publish(eq("42"), aryEq(SAVED));
        verify(outbox, times(1)).markPublished(42, 3);
        assertEquals(2, streams.get());
    }

    @Test
    void uncertainMarkReplaysTheSameSavedResultOnAFreshStream() throws Exception {
        ResultOutbox outbox = mock(ResultOutbox.class);
        ConfirmedPublisher first = mock(ConfirmedPublisher.class);
        ConfirmedPublisher second = mock(ConfirmedPublisher.class);
        AtomicInteger streams = new AtomicInteger();
        CountDownLatch settled = new CountDownLatch(1);
        when(outbox.pending(1)).thenReturn(List.of(RESULT), List.of(RESULT), List.of());
        when(outbox.markPublished(42, 3)).thenThrow(new IllegalStateException("unknown commit"))
                .thenAnswer(call -> {
                    settled.countDown();
                    return true;
                });
        try (var relay = new LedgerResultRelay(outbox,
                () -> streams.getAndIncrement() == 0 ? first : second, FAST)) {
            relay.start();
            assertTrue(settled.await(3, TimeUnit.SECONDS));
        }
        verify(first).publish(eq("42"), aryEq(SAVED));
        verify(first).close();
        verify(second).publish(eq("42"), aryEq(SAVED));
        verify(outbox, times(2)).markPublished(42, 3);
        assertEquals(2, streams.get());
    }

    @Test
    void disabledOrUnstartedRelayNeverTouchesStorageOrTransport() {
        ResultOutbox outbox = mock(ResultOutbox.class);
        AtomicInteger streams = new AtomicInteger();
        try (var relay = new LedgerResultRelay(outbox, () -> {
            streams.incrementAndGet();
            return mock(ConfirmedPublisher.class);
        }, new LedgerResultRelay.Settings(false, 100, Duration.ofMillis(500)))) {
            relay.start();
            relay.start();
        }
        verifyNoInteractions(outbox);
        assertEquals(0, streams.get());
        assertThrows(IllegalArgumentException.class,
                () -> new LedgerResultRelay.Settings(true, 0, Duration.ofMillis(500)));
        assertThrows(IllegalArgumentException.class,
                () -> new LedgerResultRelay.Settings(true, 100, Duration.ZERO));
    }
}
