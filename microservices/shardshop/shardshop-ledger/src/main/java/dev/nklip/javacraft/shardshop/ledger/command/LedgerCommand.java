package dev.nklip.javacraft.shardshop.ledger.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nklip.javacraft.shardshop.common.IdParser;
import dev.nklip.javacraft.shardshop.common.CurrencyPolicy;
import dev.nklip.javacraft.shardshop.common.JsonFields;
import dev.nklip.javacraft.shardshop.common.JsonRequests;
import dev.nklip.javacraft.shardshop.common.Sha256;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;

/** Strict wire model. Both JSONB text and fingerprints use the canonicalized, validated document. */
public record LedgerCommand(long messageId, long resultMessageId, long commandId, long sagaId,
                            long orderId, long buyerId, String requestFingerprint, Instant occurredAt,
                            BigDecimal amount, String currency, ObjectNode envelope, ObjectNode snapshot,
                            String payloadFingerprint) {
    private static final String MONEY = "(?:0|[1-9][0-9]{0,16})\\.[0-9]{2}";
    private static final String TIME = "[0-9]{4}-[0-9]{2}-[0-9]{2}T(?:[01][0-9]|2[0-3]):[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,6})?Z";
    private static final int MAX_BODY_BYTES = 1_048_576;

    public static LedgerCommand parse(byte[] body) {
        if (body == null || body.length > MAX_BODY_BYTES) {
            throw new MalformedCommandException("Body is absent or larger than 1 MiB");
        }
        try {
            return validated(JsonRequests.parse(body));
        } catch (NumberFormatException invalid) {
            // Number parsing messages repeat the input text.
            throw new MalformedCommandException("Invalid number");
        } catch (IllegalArgumentException invalid) {
            // The JSON, ID and rule validators use fixed texts that never contain payload data.
            String reason = invalid.getMessage();
            throw new MalformedCommandException(Objects.requireNonNullElse(reason, "Invalid RecordOrder"));
        } catch (RuntimeException failure) {
            throw new MalformedCommandException("Invalid RecordOrder");
        }
    }

    private static LedgerCommand validated(JsonNode root) {
        JsonFields.requireFields(root, "type", "schemaVersion", "messageId", "commandId", "sagaId",
                "orderId", "buyerId", "requestFingerprint", "occurredAt", "resultMessageId", "snapshot");
        int version = JsonFields.nonnegativeInteger(root, "schemaVersion");
        if (!"RecordOrder".equals(JsonFields.text(root, "type")) || version < 1 || version > 2) {
            throw new IllegalArgumentException("Unsupported RecordOrder type or schema version");
        }
        IdParser ids = new IdParser();
        long messageId = ids.parse(JsonFields.id(root, "messageId"));
        long resultId = ids.parse(JsonFields.id(root, "resultMessageId"));
        if (messageId == resultId) {
            throw new IllegalArgumentException("messageId equals resultMessageId");
        }
        long commandId = ids.parse(JsonFields.id(root, "commandId"));
        long sagaId = ids.parse(JsonFields.id(root, "sagaId"));
        long orderId = ids.parse(JsonFields.id(root, "orderId"));
        long buyerId = ids.parse(JsonFields.id(root, "buyerId"));
        String fingerprint = JsonFields.matchingText(root, "requestFingerprint", "[0-9a-f]{64}");
        Instant occurredAt = time(root, "occurredAt");
        JsonNode snapshot = root.get("snapshot");
        JsonFields.requireFields(snapshot, "amount", "currency", "items");
        BigDecimal amount = money(snapshot, "amount");
        String currency = currency(snapshot, "currency");
        if (version == 2 && !CurrencyPolicy.validAmount(amount, currency)) {
            throw new IllegalArgumentException("Snapshot amount violates currency precision");
        }
        JsonNode items = snapshot.get("items");
        if (items == null || !items.isArray() || items.isEmpty() || items.size() > 1000) {
            throw new IllegalArgumentException("snapshot items must hold 1 to 1000 items");
        }
        HashSet<String> pairs = new HashSet<>();
        BigDecimal sum = BigDecimal.ZERO;
        StringBuilder normalizedRequest = new StringBuilder("{\"buyerId\":\"").append(buyerId)
                .append("\",\"orderId\":\"").append(orderId).append("\",\"currency\":\"")
                .append(currency).append("\",\"items\":[");
        for (int index = 0; index < items.size(); index++) {
            JsonNode item = items.get(index);
            JsonFields.requireFields(item, "itemPosition", "sellerId", "productId", "quantity",
                    "productName", "unitPrice", "currency");
            if (JsonFields.nonnegativeInteger(item, "itemPosition") != index) {
                throw new IllegalArgumentException("itemPosition differs from its array index");
            }
            String seller = JsonFields.id(item, "sellerId");
            String product = JsonFields.id(item, "productId");
            if (!pairs.add(seller + ':' + product)) {
                throw new IllegalArgumentException("Duplicate seller and product pair");
            }
            int quantity = JsonFields.nonnegativeInteger(item, "quantity");
            if (quantity == 0) {
                throw new IllegalArgumentException("Item quantity is zero");
            }
            if (!currency.equals(currency(item, "currency"))) {
                throw new IllegalArgumentException("Item currency differs from the snapshot currency");
            }
            JsonFields.nonblankText(item, "productName", 200);
            BigDecimal unitPrice = money(item, "unitPrice");
            if (version == 2 && !CurrencyPolicy.validAmount(unitPrice, currency)) {
                throw new IllegalArgumentException("Unit price violates currency precision");
            }
            sum = sum.add(unitPrice.multiply(BigDecimal.valueOf(quantity)));
            if (index != 0) {
                normalizedRequest.append(',');
            }
            normalizedRequest.append("{\"sellerId\":\"").append(seller)
                    .append("\",\"productId\":\"").append(product)
                    .append("\",\"quantity\":").append(quantity).append('}');
        }
        if (sum.compareTo(amount) != 0 || sum.precision() - sum.scale() > 17) {
            throw new IllegalArgumentException("Snapshot amount differs from the item total");
        }
        String calculated = String.format("%064x", Sha256.unsignedDigest(normalizedRequest.append("]}").toString()));
        if (!fingerprint.equals(calculated)) {
            throw new IllegalArgumentException("requestFingerprint differs from the normalized request");
        }
        ObjectNode canonical = (ObjectNode) canonical(root);
        return new LedgerCommand(messageId, resultId, commandId, sagaId, orderId, buyerId, fingerprint,
                occurredAt, amount, currency, canonical, (ObjectNode) canonical(snapshot),
                String.format("%064x", Sha256.unsignedDigest(canonical.toString())));
    }

    private static String currency(JsonNode node, String field) {
        return JsonFields.matchingText(node, field, "[A-Z]{3}");
    }

    private static BigDecimal money(JsonNode node, String field) {
        return new BigDecimal(JsonFields.matchingText(node, field, MONEY));
    }

    private static Instant time(JsonNode node, String field) {
        String value = JsonFields.matchingText(node, field, TIME);
        final Instant instant;
        try {
            instant = Instant.parse(value);
        } catch (DateTimeException invalid) {
            // The parser message repeats the input text.
            throw new IllegalArgumentException("Invalid " + field + " timestamp");
        }
        if (instant.getNano() % 1000 != 0) {
            throw new IllegalArgumentException(field + " has more than microsecond precision");
        }
        return instant;
    }

    /** Object order and integral numeric spellings do not affect payload identity. Array order remains significant. */
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
            BigDecimal normalized = node.decimalValue().stripTrailingZeros();
            return JsonNodeFactory.instance.numberNode(normalized);
        }
        return node.deepCopy();
    }
}
