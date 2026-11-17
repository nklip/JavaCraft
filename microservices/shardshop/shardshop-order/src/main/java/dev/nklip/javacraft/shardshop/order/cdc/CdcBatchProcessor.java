package dev.nklip.javacraft.shardshop.order.cdc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nklip.javacraft.shardshop.common.amqp.ConfirmedPublisher;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Processes records in source order; a failed record prevents the batch checkpoint. */
final class CdcBatchProcessor implements DebeziumEngine.ChangeConsumer<ChangeEvent<String, String>> {
    @FunctionalInterface
    interface PublicationStatus {
        void markPublished(List<String> messageIds) throws Exception;
    }

    private final String destination;
    private final ConfirmedPublisher publisher;
    private final PublicationStatus status;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();

    CdcBatchProcessor(String identity, ConfirmedPublisher publisher, PublicationStatus status) {
        this.destination = identity + ".ordering.order_outbox";
        this.publisher = Objects.requireNonNull(publisher);
        this.status = Objects.requireNonNull(status);
    }

    Throwable failure() {
        return failure.get();
    }

    @Override
    public void handleBatch(List<ChangeEvent<String, String>> records,
                            DebeziumEngine.RecordCommitter<ChangeEvent<String, String>> committer)
            throws InterruptedException {
        if (failure.get() != null) {
            throw stopped();
        }
        try {
            for (int start = 0; start < records.size(); start += ConfirmedPublisher.MAX_BATCH_SIZE) {
                List<ChangeEvent<String, String>> window = records.subList(start,
                        Math.min(records.size(), start + ConfirmedPublisher.MAX_BATCH_SIZE));
                List<ConfirmedPublisher.Publication> publications = new ArrayList<>(window.size());
                for (ChangeEvent<String, String> event : window) {
                    ConfirmedPublisher.Publication publication = publication(event);
                    if (publication != null) {
                        publications.add(publication);
                    }
                }
                if (!publications.isEmpty()) {
                    publisher.publishBatch(publications);
                    status.markPublished(publications.stream().map(ConfirmedPublisher.Publication::messageId).toList());
                }
                // No source offset may pass any command whose confirmation or durable mark failed.
                for (ChangeEvent<String, String> event : window) {
                    committer.markProcessed(event);
                }
            }
            committer.markBatchFinished();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (Exception failed) {
            failure.compareAndSet(null, failed);
            throw stopped();
        }
    }

    private InterruptedException stopped() {
        InterruptedException interrupted = new InterruptedException("CDC batch failed before checkpoint");
        interrupted.initCause(failure.get());
        return interrupted;
    }

    private ConfirmedPublisher.Publication publication(ChangeEvent<String, String> event) throws Exception {
        if (event.value() == null) {
            return null; // tombstone
        }
        JsonNode value = json.readTree(event.value());
        JsonNode payload = value.path("payload");
        if (payload.isMissingNode()) {
            payload = value;
        }
        JsonNode operation = payload.path("op");
        if (operation.isMissingNode() || operation.isNull()) {
            if (destination.equals(event.destination())) {
                throw new IllegalArgumentException("Captured outbox record has no operation");
            }
            return null; // connector metadata or heartbeat
        }
        String op = operation.asText();
        if (op.equals("u") || op.equals("d") || op.equals("t") || op.equals("m")) {
            return null; // metadata changes, cleanup, truncation and heartbeat messages
        }
        if (!op.equals("r") && !op.equals("c")) {
            throw new IllegalArgumentException("Unexpected CDC operation");
        }
        if (!destination.equals(event.destination())) {
            throw new IllegalArgumentException("Captured command came from an unexpected table");
        }
        JsonNode after = payload.path("after");
        if (!after.isObject()) {
            throw new IllegalArgumentException("Captured outbox insert has no row");
        }
        JsonNode idNode = after.path("message_id");
        if (!idNode.isIntegralNumber() && !idNode.isTextual()) {
            throw new IllegalArgumentException("Captured outbox message ID is invalid");
        }
        String messageId = idNode.asText();
        if (!messageId.matches("[1-9][0-9]*") || Long.parseLong(messageId) <= 0) {
            throw new IllegalArgumentException("Captured outbox message ID is invalid");
        }
        JsonNode publishedAt = after.path("published_at");
        if (op.equals("r") && !publishedAt.isMissingNode() && !publishedAt.isNull()) {
            return null; // first snapshot may include already confirmed rows
        }
        JsonNode envelopeNode = after.path("envelope");
        if (!envelopeNode.isTextual()) {
            throw new IllegalArgumentException("Captured outbox envelope is invalid");
        }
        byte[] envelope = envelopeNode.textValue().getBytes(StandardCharsets.UTF_8);
        JsonNode command = json.readTree(envelope);
        if (!command.isObject() || !command.path("messageId").isTextual()
                || !messageId.equals(command.path("messageId").textValue())) {
            throw new IllegalArgumentException("Captured outbox message ID does not match its envelope");
        }
        return new ConfirmedPublisher.Publication(messageId, envelope);
    }
}
