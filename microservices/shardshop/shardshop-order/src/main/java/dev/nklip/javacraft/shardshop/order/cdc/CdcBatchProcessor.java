package dev.nklip.javacraft.shardshop.order.cdc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Processes records in source order; a failed record prevents the batch checkpoint. */
final class CdcBatchProcessor implements DebeziumEngine.ChangeConsumer<ChangeEvent<String, String>> {
    @FunctionalInterface
    interface PublicationStatus {
        void markPublished(String messageId) throws Exception;
    }

    private final String destination;
    private final CommandPublisher publisher;
    private final PublicationStatus status;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();

    CdcBatchProcessor(String identity, CommandPublisher publisher, PublicationStatus status) {
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
            for (ChangeEvent<String, String> event : records) {
                process(event);
                committer.markProcessed(event);
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

    private void process(ChangeEvent<String, String> event) throws Exception {
        if (event.value() == null) {
            return; // tombstone
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
            return; // connector metadata or heartbeat
        }
        String op = operation.asText();
        if (op.equals("u") || op.equals("d") || op.equals("t") || op.equals("m")) {
            return; // metadata changes, cleanup, truncation and heartbeat messages
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
            return; // first snapshot may include already confirmed rows
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
        publisher.publish(messageId, envelope);
        status.markPublished(messageId);
    }
}
