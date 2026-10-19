package dev.nklip.javacraft.shardshop.order.result;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nklip.javacraft.shardshop.common.IdParser;
import dev.nklip.javacraft.shardshop.common.JsonFields;
import dev.nklip.javacraft.shardshop.common.JsonRequests;
import dev.nklip.javacraft.shardshop.common.Sha256;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Map;

/** Order-owned decoder of ledger's result envelope. The fingerprint covers canonical, validated JSON. */
public record LedgerResult(long messageId, long commandId, long sagaId, long orderId, long buyerId,
                           String requestFingerprint, Instant occurredAt, BigDecimal amount, String currency,
                           String outcome, String rejectionReason, Instant decidedAt, ObjectNode envelope,
                           String payloadFingerprint) {
    private static final int MAX_BODY_BYTES = 1_048_576;
    private static final String MONEY = "(?:0|[1-9][0-9]{0,16})\\.[0-9]{2}";
    private static final String TIME = "[0-9]{4}-[0-9]{2}-[0-9]{2}T(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](?:\\.[0-9]{1,6})?Z";

    public static LedgerResult parse(byte[] body) {
        if (body == null || body.length > MAX_BODY_BYTES) {
            throw new MalformedResultException("Body is absent or larger than 1 MiB");
        }
        try {
            return validated(JsonRequests.parse(body));
        } catch (NumberFormatException invalid) {
            // Number parsing messages can contain the input text.
            throw new MalformedResultException("Invalid number");
        } catch (IllegalArgumentException invalid) {
            // Common validators have fixed messages that never echo JSON data.
            throw new MalformedResultException(invalid.getMessage() == null ? "Invalid ledger result" : invalid.getMessage());
        } catch (RuntimeException invalid) {
            throw new MalformedResultException("Invalid ledger result");
        }
    }

    private static LedgerResult validated(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Expected a JSON object");
        }
        boolean rejected = "LedgerRejected".equals(JsonFields.text(root, "type"));
        if (rejected) {
            JsonFields.requireFields(root, "type", "schemaVersion", "messageId", "commandId", "sagaId",
                    "orderId", "buyerId", "requestFingerprint", "occurredAt", "amount", "currency",
                    "outcome", "rejectionReason", "decidedAt");
        } else {
            JsonFields.requireFields(root, "type", "schemaVersion", "messageId", "commandId", "sagaId",
                    "orderId", "buyerId", "requestFingerprint", "occurredAt", "amount", "currency",
                    "outcome", "decidedAt");
        }
        String outcome = JsonFields.text(root, "outcome");
        if (JsonFields.nonnegativeInteger(root, "schemaVersion") != 1
                || !(rejected ? "REJECTED".equals(outcome) : "LedgerRecorded".equals(JsonFields.text(root, "type"))
                && "RECORDED".equals(outcome))) {
            throw new IllegalArgumentException("Unsupported ledger result type, version or outcome");
        }
        String reason = rejected ? JsonFields.text(root, "rejectionReason") : null;
        if (rejected && !"UNSUPPORTED_CURRENCY".equals(reason)) {
            throw new IllegalArgumentException("Invalid rejection reason");
        }
        IdParser ids = new IdParser();
        long messageId = ids.parse(JsonFields.id(root, "messageId"));
        long commandId = ids.parse(JsonFields.id(root, "commandId"));
        long sagaId = ids.parse(JsonFields.id(root, "sagaId"));
        long orderId = ids.parse(JsonFields.id(root, "orderId"));
        long buyerId = ids.parse(JsonFields.id(root, "buyerId"));
        String requestFingerprint = JsonFields.matchingText(root, "requestFingerprint", "[0-9a-f]{64}");
        Instant occurredAt = time(root, "occurredAt");
        Instant decidedAt = time(root, "decidedAt");
        if (occurredAt.isBefore(decidedAt)) {
            throw new IllegalArgumentException("Result occurredAt precedes decidedAt");
        }
        BigDecimal amount = new BigDecimal(JsonFields.matchingText(root, "amount", MONEY));
        String currency = JsonFields.matchingText(root, "currency", "[A-Z]{3}");
        ObjectNode canonical = (ObjectNode) canonical(root);
        String payloadFingerprint = String.format("%064x", Sha256.unsignedDigest(canonical.toString()));
        return new LedgerResult(messageId, commandId, sagaId, orderId, buyerId, requestFingerprint,
                occurredAt, amount, currency, outcome, reason, decidedAt, canonical, payloadFingerprint);
    }

    private static Instant time(JsonNode root, String field) {
        String value = JsonFields.matchingText(root, field, TIME);
        try {
            return Instant.parse(value);
        } catch (DateTimeException invalid) {
            // DateTimeException messages can repeat input text.
            throw new IllegalArgumentException("Invalid " + field + " timestamp");
        }
    }

    /** Object order and integer spelling do not affect identity; array order remains significant. */
    public static JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = JsonNodeFactory.instance.objectNode();
            node.properties().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> sorted.set(entry.getKey(), canonical(entry.getValue())));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode values = JsonNodeFactory.instance.arrayNode();
            node.forEach(value -> values.add(canonical(value)));
            return values;
        }
        if (node.isNumber()) {
            return JsonNodeFactory.instance.numberNode(node.decimalValue().stripTrailingZeros());
        }
        return node.deepCopy();
    }
}
