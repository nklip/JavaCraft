package dev.nklip.javacraft.shardshop.order.cdc;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nklip.javacraft.shardshop.common.amqp.ConfirmedPublisher;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CdcBatchProcessorTest {
    private static final String IDENTITY = "shardshop_order_shard_a";
    private static final String DESTINATION = IDENTITY + ".ordering.order_outbox";
    private static final ObjectMapper JSON = new ObjectMapper();

    @SuppressWarnings("unchecked")
    private final DebeziumEngine.RecordCommitter<ChangeEvent<String, String>> committer =
            mock(DebeziumEngine.RecordCommitter.class);
    private final ConfirmedPublisher publisher = mock(ConfirmedPublisher.class);
    private final CdcBatchProcessor.PublicationStatus status = mock(CdcBatchProcessor.PublicationStatus.class);
    private final CdcBatchProcessor processor = new CdcBatchProcessor(IDENTITY, publisher, status);

    @Test
    void commandIsCheckpointedOnlyAfterConfirmAndCommittedStatus() throws Exception {
        String firstEnvelope = "{\"messageId\":\"123\", \"orderId\":\"987654321012345678\"}";
        String secondEnvelope = "{\"messageId\":\"124\",\"schemaVersion\":1}";
        ChangeEvent<String, String> first = outbox("c", "123", firstEnvelope, null);
        ChangeEvent<String, String> second = outbox("r", "124", secondEnvelope, null);

        processor.handleBatch(List.of(first, second), committer);

        InOrder order = inOrder(publisher, status, committer);
        order.verify(publisher).publishBatch(argThat(batch -> matches(batch,
                List.of("123", "124"), List.of(firstEnvelope, secondEnvelope))));
        order.verify(status).markPublished(List.of("123", "124"));
        order.verify(committer).markProcessed(first);
        order.verify(committer).markProcessed(second);
        order.verify(committer).markBatchFinished();
    }

    @Test
    void failedPublishStopsAtRecordWithoutCompletingBatchOrFollowingCommand() throws Exception {
        ChangeEvent<String, String> first = outbox("c", "123", "{\"messageId\":\"123\"}", null);
        ChangeEvent<String, String> second = outbox("c", "124", "{\"messageId\":\"124\"}", null);
        doThrow(new IllegalStateException("broker failed")).when(publisher).publishBatch(anyList());

        assertThrows(InterruptedException.class, () -> processor.handleBatch(List.of(first, second), committer));
        assertNotNull(processor.failure());
        verify(publisher, times(1)).publishBatch(argThat(batch -> batch.size() == 2));
        verifyNoInteractions(status, committer);
        assertThrows(InterruptedException.class, () -> processor.handleBatch(List.of(second), committer));
        verify(publisher, times(1)).publishBatch(anyList());
    }

    @Test
    void failedStatusCommitCannotAdvanceOffset() throws Exception {
        ChangeEvent<String, String> event = outbox("c", "123", "{\"messageId\":\"123\"}", null);
        ChangeEvent<String, String> later = outbox("c", "124", "{\"messageId\":\"124\"}", null);
        doThrow(new IllegalStateException("database failed")).when(status).markPublished(List.of("123", "124"));

        assertThrows(InterruptedException.class, () -> processor.handleBatch(List.of(event, later), committer));

        verify(publisher).publishBatch(argThat(batch -> batch.size() == 2));
        verify(committer, never()).markProcessed(any());
        verify(committer, never()).markBatchFinished();
    }

    @Test
    @SuppressWarnings("unchecked")
    void snapshotPublishedRowsAndMetadataChangesDoNotRepublish() throws Exception {
        ChangeEvent<String, String> published = outbox("r", "123", "{\"messageId\":\"123\"}",
                "2026-10-18T00:00:00Z");
        ChangeEvent<String, String> updated = outbox("u", "124", "{\"messageId\":\"124\"}", null);
        ChangeEvent<String, String> tombstone = mock(ChangeEvent.class);
        when(tombstone.value()).thenReturn(null);

        processor.handleBatch(List.of(published, updated, tombstone), committer);

        verifyNoInteractions(publisher, status);
        verify(committer).markProcessed(published);
        verify(committer).markProcessed(updated);
        verify(committer).markProcessed(tombstone);
        verify(committer).markBatchFinished();
    }

    /** Heartbeats advance an idle reader's checkpoint; they are never commands. */
    @Test
    @SuppressWarnings("unchecked")
    void heartbeatRecordsAndHeartbeatMessagesCheckpointWithoutPublication() throws Exception {
        ChangeEvent<String, String> heartbeat = mock(ChangeEvent.class);
        when(heartbeat.destination()).thenReturn("__debezium-heartbeat." + IDENTITY);
        when(heartbeat.value()).thenReturn("{\"ts_ms\":1760745600000}");
        ChangeEvent<String, String> message = mock(ChangeEvent.class);
        when(message.destination()).thenReturn(IDENTITY + ".message");
        when(message.value()).thenReturn("{\"op\":\"m\",\"message\":{\"prefix\":\"shardshop-cdc-heartbeat\",\"content\":\"\"}}");

        processor.handleBatch(List.of(heartbeat, message), committer);

        verifyNoInteractions(publisher, status);
        InOrder order = inOrder(committer);
        order.verify(committer).markProcessed(heartbeat);
        order.verify(committer).markProcessed(message);
        order.verify(committer).markBatchFinished();
    }

    @Test
    void mismatchedEnvelopeIdFailsClosed() throws Exception {
        ChangeEvent<String, String> event = outbox("c", "123", "{\"messageId\":\"999\"}", null);

        assertThrows(InterruptedException.class, () -> processor.handleBatch(List.of(event), committer));

        verifyNoInteractions(publisher, status, committer);
    }

    @Test
    void insertIsPublishedEvenWhenItsMetadataAlreadyHasPublicationTime() throws Exception {
        String envelope = "{\"messageId\":\"123\"}";
        ChangeEvent<String, String> event = outbox("c", "123", envelope,
                "2026-10-18T00:00:00Z");

        processor.handleBatch(List.of(event), committer);

        InOrder order = inOrder(publisher, status, committer);
        order.verify(publisher).publishBatch(argThat(batch -> matches(batch, List.of("123"), List.of(envelope))));
        order.verify(status).markPublished(List.of("123"));
        order.verify(committer).markProcessed(event);
        order.verify(committer).markBatchFinished();
    }

    @Test
    @SuppressWarnings("unchecked")
    void malformedOutboxRecordWithoutOperationCannotAdvanceOffset() throws Exception {
        ChangeEvent<String, String> malformed = mock(ChangeEvent.class);
        when(malformed.destination()).thenReturn(DESTINATION);
        when(malformed.value()).thenReturn("{\"after\":{\"message_id\":123}}");

        assertThrows(InterruptedException.class, () -> processor.handleBatch(List.of(malformed), committer));

        verifyNoInteractions(publisher, status, committer);
    }

    @Test
    @SuppressWarnings("unchecked")
    void metadataBetweenCommandsWaitsForTheWindowCommitBeforeOrderedProcessing() throws Exception {
        var first = outbox("c", "123", "{\"messageId\":\"123\"}", null);
        ChangeEvent<String, String> tombstone = mock(ChangeEvent.class);
        when(tombstone.value()).thenReturn(null);
        var second = outbox("c", "124", "{\"messageId\":\"124\"}", null);

        processor.handleBatch(List.of(first, tombstone, second), committer);

        InOrder order = inOrder(publisher, status, committer);
        order.verify(publisher).publishBatch(argThat(batch -> batch.stream()
                .map(ConfirmedPublisher.Publication::messageId).toList().equals(List.of("123", "124"))));
        order.verify(status).markPublished(List.of("123", "124"));
        order.verify(committer).markProcessed(first);
        order.verify(committer).markProcessed(tombstone);
        order.verify(committer).markProcessed(second);
        order.verify(committer).markBatchFinished();
    }

    @Test
    void fullWindowAndPartialTailCommitInSourceOrder() throws Exception {
        List<ChangeEvent<String, String>> records = commands(19);

        processor.handleBatch(records, committer);

        InOrder order = inOrder(publisher, status, committer);
        order.verify(publisher).publishBatch(argThat(batch -> batch.size() == 16
                && batch.getFirst().messageId().equals("1") && batch.getLast().messageId().equals("16")));
        order.verify(status).markPublished(ids(1, 16));
        for (var record : records.subList(0, 16)) {
            order.verify(committer).markProcessed(record);
        }
        order.verify(publisher).publishBatch(argThat(batch -> batch.size() == 3
                && batch.getFirst().messageId().equals("17") && batch.getLast().messageId().equals("19")));
        order.verify(status).markPublished(ids(17, 19));
        for (var record : records.subList(16, 19)) {
            order.verify(committer).markProcessed(record);
        }
        order.verify(committer).markBatchFinished();
        verify(publisher, times(2)).publishBatch(anyList());
        verify(status, times(2)).markPublished(anyList());
    }

    @Test
    void failedLaterWindowRetainsEarlierProcessedPrefixWithoutFinishingBatch() throws Exception {
        List<ChangeEvent<String, String>> records = commands(35);
        doNothing().doThrow(new IllegalStateException("missing confirm"))
                .when(publisher).publishBatch(anyList());

        assertThrows(InterruptedException.class, () -> processor.handleBatch(records, committer));

        verify(status).markPublished(ids(1, 16));
        verify(status, times(1)).markPublished(anyList());
        for (var record : records.subList(0, 16)) {
            verify(committer).markProcessed(record);
        }
        for (var record : records.subList(16, 35)) {
            verify(committer, never()).markProcessed(record);
        }
        verify(publisher, times(2)).publishBatch(anyList());
        verify(committer, never()).markBatchFinished();
    }

    @Test
    void uncertainStatusCommitReplaysSameIdsAndEnvelopeBeforeAnySourceCheckpoint() throws Exception {
        String firstEnvelope = "{ \"messageId\":\"123\", \"orderId\":\"987654321012345678\" }";
        String secondEnvelope = "{\"messageId\":\"124\",\"schemaVersion\":1}";
        var first = outbox("c", "123", firstEnvelope, null);
        var second = outbox("c", "124", secondEnvelope, null);
        List<String> committed = new ArrayList<>();
        AtomicBoolean firstCommit = new AtomicBoolean(true);
        doAnswer(call -> {
            committed.addAll(call.getArgument(0)); // Database committed, but its response was lost.
            if (firstCommit.getAndSet(false)) {
                throw new java.sql.SQLException("commit outcome unknown");
            }
            return null;
        }).when(status).markPublished(anyList());

        assertThrows(InterruptedException.class, () -> processor.handleBatch(List.of(first, second), committer));
        assertEquals(List.of("123", "124"), committed);
        verifyNoInteractions(committer);

        var restarted = new CdcBatchProcessor(IDENTITY, publisher, status);
        restarted.handleBatch(List.of(first, second), committer);

        verify(publisher, times(2)).publishBatch(argThat(batch -> matches(batch,
                List.of("123", "124"), List.of(firstEnvelope, secondEnvelope))));
        InOrder order = inOrder(status, committer);
        order.verify(status, times(2)).markPublished(List.of("123", "124"));
        order.verify(committer).markProcessed(first);
        order.verify(committer).markProcessed(second);
        order.verify(committer).markBatchFinished();
    }

    @Test
    void malformedRecordPreventsAnyCheckpointWithinItsWindow() throws Exception {
        var first = outbox("c", "123", "{\"messageId\":\"123\"}", null);
        var invalid = outbox("c", "124", "{\"messageId\":\"999\"}", null);

        assertThrows(InterruptedException.class, () -> processor.handleBatch(List.of(first, invalid), committer));

        verifyNoInteractions(publisher, status, committer);
    }

    private List<ChangeEvent<String, String>> commands(int count) throws Exception {
        List<ChangeEvent<String, String>> records = new ArrayList<>();
        for (int id = 1; id <= count; id++) {
            records.add(outbox("c", Integer.toString(id), "{\"messageId\":\"" + id + "\"}", null));
        }
        return records;
    }

    private static List<String> ids(int first, int last) {
        return java.util.stream.IntStream.rangeClosed(first, last).mapToObj(Integer::toString).toList();
    }

    private static boolean matches(List<ConfirmedPublisher.Publication> batch, List<String> ids,
                                   List<String> envelopes) {
        if (!batch.stream().map(ConfirmedPublisher.Publication::messageId).toList().equals(ids)) {
            return false;
        }
        for (int index = 0; index < batch.size(); index++) {
            if (!java.util.Arrays.equals(envelopes.get(index).getBytes(StandardCharsets.UTF_8),
                    batch.get(index).body())) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private ChangeEvent<String, String> outbox(String operation, String messageId, String envelope,
                                                String publishedAt) throws Exception {
        ChangeEvent<String, String> event = mock(ChangeEvent.class);
        Map<String, Object> after = new HashMap<>();
        after.put("message_id", Long.parseLong(messageId));
        after.put("envelope", envelope);
        after.put("published_at", publishedAt);
        String value = JSON.writeValueAsString(Map.of("op", operation, "after", after));
        when(event.value()).thenReturn(value);
        when(event.destination()).thenReturn(DESTINATION);
        return event;
    }
}
