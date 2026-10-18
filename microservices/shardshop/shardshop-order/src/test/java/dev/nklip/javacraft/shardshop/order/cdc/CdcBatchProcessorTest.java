package dev.nklip.javacraft.shardshop.order.cdc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CdcBatchProcessorTest {
    private static final String IDENTITY = "shardshop_order_shard_a";
    private static final String DESTINATION = IDENTITY + ".ordering.order_outbox";
    private static final ObjectMapper JSON = new ObjectMapper();

    @SuppressWarnings("unchecked")
    private final DebeziumEngine.RecordCommitter<ChangeEvent<String, String>> committer =
            mock(DebeziumEngine.RecordCommitter.class);
    private final CommandPublisher publisher = mock(CommandPublisher.class);
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
        order.verify(publisher).publish(eq("123"), aryEq(firstEnvelope.getBytes(StandardCharsets.UTF_8)));
        order.verify(status).markPublished("123");
        order.verify(committer).markProcessed(first);
        order.verify(publisher).publish(eq("124"), aryEq(secondEnvelope.getBytes(StandardCharsets.UTF_8)));
        order.verify(status).markPublished("124");
        order.verify(committer).markProcessed(second);
        order.verify(committer).markBatchFinished();
    }

    @Test
    void failedPublishStopsAtRecordWithoutCompletingBatchOrFollowingCommand() throws Exception {
        ChangeEvent<String, String> first = outbox("c", "123", "{\"messageId\":\"123\"}", null);
        ChangeEvent<String, String> second = outbox("c", "124", "{\"messageId\":\"124\"}", null);
        doThrow(new IllegalStateException("broker failed")).when(publisher).publish(eq("123"), any());

        assertThrows(InterruptedException.class, () -> processor.handleBatch(List.of(first, second), committer));
        assertNotNull(processor.failure());
        verify(publisher, times(1)).publish(eq("123"), any());
        verifyNoInteractions(status, committer);
        assertThrows(InterruptedException.class, () -> processor.handleBatch(List.of(second), committer));
        verify(publisher, never()).publish(eq("124"), any());
    }

    @Test
    void failedStatusCommitCannotAdvanceOffset() throws Exception {
        ChangeEvent<String, String> event = outbox("c", "123", "{\"messageId\":\"123\"}", null);
        ChangeEvent<String, String> later = outbox("c", "124", "{\"messageId\":\"124\"}", null);
        doThrow(new IllegalStateException("database failed")).when(status).markPublished("123");

        assertThrows(InterruptedException.class, () -> processor.handleBatch(List.of(event, later), committer));

        verify(publisher).publish(eq("123"), any());
        verify(publisher, never()).publish(eq("124"), any());
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
        order.verify(publisher).publish(eq("123"), aryEq(envelope.getBytes(StandardCharsets.UTF_8)));
        order.verify(status).markPublished("123");
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
