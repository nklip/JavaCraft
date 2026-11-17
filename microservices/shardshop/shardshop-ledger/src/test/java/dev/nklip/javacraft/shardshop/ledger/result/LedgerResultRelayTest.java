package dev.nklip.javacraft.shardshop.ledger.result;

import dev.nklip.javacraft.shardshop.common.amqp.ConfirmedPublisher;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LedgerResultRelayTest {
    private static final PendingResult RESULT = new PendingResult(42, 3, "{\"messageId\": \"42\", \"currency\": \"€\"}");
    private static final PendingResult OTHER = new PendingResult(43, 7, "{\"messageId\": \"43\"}");
    private static final List<PendingResult> PAIR = List.of(RESULT, OTHER);
    private static final LedgerResultRelay.Settings FAST = new LedgerResultRelay.Settings(true, 2,
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
        when(outbox.markPublished(List.of(RESULT))).thenAnswer(call -> {
            marked.countDown();
            return 0; // A concurrent rearm is harmless to this attempt.
        });
        var slowPolls = new LedgerResultRelay.Settings(true, 1, Duration.ofSeconds(30));
        try (var relay = new LedgerResultRelay(outbox, () -> publisher, slowPolls)) {
            relay.start();
            assertTrue(marked.await(2, TimeUnit.SECONDS));
            assertTrue(repolled.await(2, TimeUnit.SECONDS), "A full batch waited for the poll interval");
        }
        var order = inOrder(outbox, publisher);
        order.verify(outbox).pending(1);
        order.verify(publisher).publishBatch(argThat(publications -> matches(publications, List.of(RESULT))));
        order.verify(outbox).markPublished(List.of(RESULT));
        verify(outbox, atLeast(2)).pending(1);
    }

    @Test
    void dividesFetchedRowsIntoBoundedWindowsAndMarksEachOnlyAfterItsConfirm() throws Exception {
        List<PendingResult> batch = LongStream.rangeClosed(1, 33)
                .mapToObj(id -> new PendingResult(id, 1, "{\"messageId\":\"" + id + "\"}")).toList();
        ResultOutbox outbox = mock(ResultOutbox.class);
        ConfirmedPublisher publisher = mock(ConfirmedPublisher.class);
        CountDownLatch settled = new CountDownLatch(3);
        when(outbox.pending(33)).thenReturn(batch, List.of());
        when(outbox.markPublished(anyList())).thenAnswer(call -> {
            settled.countDown();
            return ((List<?>) call.getArgument(0)).size();
        });
        try (var relay = new LedgerResultRelay(outbox, () -> publisher,
                new LedgerResultRelay.Settings(true, 33, Duration.ofSeconds(30)))) {
            relay.start();
            assertTrue(settled.await(2, TimeUnit.SECONDS));
        }
        var order = inOrder(outbox, publisher);
        order.verify(outbox).pending(33);
        for (List<PendingResult> window : List.of(batch.subList(0, 16), batch.subList(16, 32), batch.subList(32, 33))) {
            order.verify(publisher).publishBatch(argThat(publications -> matches(publications, window)));
            order.verify(outbox).markPublished(window);
        }
        verify(outbox, never()).markPublished(anyLong(), anyLong());
    }

    @Test
    void failedWindowRemainsPendingAndRetriesEverySavedEnvelopeOnFreshStream() throws Exception {
        ResultOutbox outbox = mock(ResultOutbox.class);
        ConfirmedPublisher failed = mock(ConfirmedPublisher.class);
        ConfirmedPublisher recovered = mock(ConfirmedPublisher.class);
        CountDownLatch marked = new CountDownLatch(1);
        AtomicInteger streams = new AtomicInteger();
        when(outbox.pending(2)).thenReturn(PAIR, PAIR, List.of());
        doThrow(new IllegalStateException("uncertain second confirm")).when(failed).publishBatch(anyList());
        when(outbox.markPublished(PAIR)).thenAnswer(call -> {
            marked.countDown();
            return 2;
        });
        try (var relay = new LedgerResultRelay(outbox,
                () -> streams.getAndIncrement() == 0 ? failed : recovered, FAST)) {
            relay.start();
            assertTrue(marked.await(3, TimeUnit.SECONDS));
        }
        var order = inOrder(outbox, failed, recovered);
        order.verify(outbox).pending(2);
        order.verify(failed).publishBatch(argThat(publications -> matches(publications, PAIR)));
        order.verify(failed).close();
        order.verify(outbox).pending(2);
        order.verify(recovered).publishBatch(argThat(publications -> matches(publications, PAIR)));
        order.verify(outbox).markPublished(PAIR);
        verify(outbox, times(1)).markPublished(anyList());
        assertEquals(2, streams.get());
    }

    @Test
    void failureInLaterWindowPreservesEarlierMarkAndRetriesUnsettledRows() throws Exception {
        List<PendingResult> batch = LongStream.rangeClosed(1, 33)
                .mapToObj(id -> new PendingResult(id, 1, "{\"messageId\":\"" + id + "\"}")).toList();
        List<PendingResult> firstWindow = batch.subList(0, 16);
        List<PendingResult> failedWindow = batch.subList(16, 32);
        List<PendingResult> lastWindow = batch.subList(32, 33);
        ResultOutbox outbox = mock(ResultOutbox.class);
        ConfirmedPublisher first = mock(ConfirmedPublisher.class);
        ConfirmedPublisher recovered = mock(ConfirmedPublisher.class);
        AtomicInteger streams = new AtomicInteger();
        CountDownLatch settled = new CountDownLatch(3);
        when(outbox.pending(33)).thenReturn(batch, batch.subList(16, 33), List.of());
        doNothing().doThrow(new IllegalStateException("second window failed")).when(first).publishBatch(anyList());
        when(outbox.markPublished(anyList())).thenAnswer(call -> {
            settled.countDown();
            return ((List<?>) call.getArgument(0)).size();
        });
        try (var relay = new LedgerResultRelay(outbox,
                () -> streams.getAndIncrement() == 0 ? first : recovered,
                new LedgerResultRelay.Settings(true, 33, Duration.ofMillis(20)))) {
            relay.start();
            assertTrue(settled.await(3, TimeUnit.SECONDS));
        }
        var order = inOrder(outbox, first, recovered);
        order.verify(outbox).pending(33);
        order.verify(first).publishBatch(argThat(publications -> matches(publications, firstWindow)));
        order.verify(outbox).markPublished(firstWindow);
        order.verify(first).publishBatch(argThat(publications -> matches(publications, failedWindow)));
        order.verify(first).close();
        order.verify(outbox).pending(33);
        order.verify(recovered).publishBatch(argThat(publications -> matches(publications, failedWindow)));
        order.verify(outbox).markPublished(failedWindow);
        order.verify(recovered).publishBatch(argThat(publications -> matches(publications, lastWindow)));
        order.verify(outbox).markPublished(lastWindow);
        verify(outbox, times(3)).markPublished(anyList());
    }

    @Test
    void uncertainBatchMarkReplaysTheSameWholeWindowOnAFreshStream() throws Exception {
        ResultOutbox outbox = mock(ResultOutbox.class);
        ConfirmedPublisher first = mock(ConfirmedPublisher.class);
        ConfirmedPublisher second = mock(ConfirmedPublisher.class);
        AtomicInteger streams = new AtomicInteger();
        CountDownLatch settled = new CountDownLatch(1);
        when(outbox.pending(2)).thenReturn(PAIR, PAIR, List.of());
        when(outbox.markPublished(PAIR)).thenThrow(new IllegalStateException("unknown commit"))
                .thenAnswer(call -> {
                    settled.countDown();
                    return 2;
                });
        try (var relay = new LedgerResultRelay(outbox,
                () -> streams.getAndIncrement() == 0 ? first : second, FAST)) {
            relay.start();
            assertTrue(settled.await(3, TimeUnit.SECONDS));
        }
        verify(first).publishBatch(argThat(publications -> matches(publications, PAIR)));
        verify(first).close();
        verify(second).publishBatch(argThat(publications -> matches(publications, PAIR)));
        verify(outbox, times(2)).markPublished(PAIR);
        assertEquals(2, streams.get());
    }

    @Test
    void shutdownDuringPublicationDoesNotMarkEvenIfPublisherReturnsSuccess() throws Exception {
        ResultOutbox outbox = mock(ResultOutbox.class);
        ConfirmedPublisher publisher = mock(ConfirmedPublisher.class);
        CountDownLatch publishing = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        when(outbox.pending(2)).thenReturn(PAIR);
        doAnswer(call -> {
            publishing.countDown();
            try {
                new CountDownLatch(1).await(2, TimeUnit.SECONDS);
            } catch (InterruptedException shutdown) {
                // Model a completed confirm arriving as shutdown interrupts the wait.
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
            return null;
        }).when(publisher).publishBatch(anyList());
        try (var relay = new LedgerResultRelay(outbox, () -> publisher, FAST)) {
            relay.start();
            assertTrue(publishing.await(2, TimeUnit.SECONDS));
        }
        assertTrue(interrupted.get(), "Shutdown must interrupt the pending publication");
        verify(outbox, never()).markPublished(anyList());
        verify(publisher).close();
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

    private static boolean matches(List<ConfirmedPublisher.Publication> publications, List<PendingResult> results) {
        if (publications.size() != results.size()) {
            return false;
        }
        for (int index = 0; index < results.size(); index++) {
            PendingResult result = results.get(index);
            ConfirmedPublisher.Publication publication = publications.get(index);
            if (!Long.toString(result.messageId()).equals(publication.messageId())
                    || !Arrays.equals(result.envelope().getBytes(StandardCharsets.UTF_8), publication.body())) {
                return false;
            }
        }
        return true;
    }
}
