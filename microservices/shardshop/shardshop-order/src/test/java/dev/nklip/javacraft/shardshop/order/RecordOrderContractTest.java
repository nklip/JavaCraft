package dev.nklip.javacraft.shardshop.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nklip.javacraft.shardshop.common.contract.ContractDocument;
import dev.nklip.javacraft.shardshop.common.contract.ContractDocument.Example;
import dev.nklip.javacraft.shardshop.common.contract.ExampleIds;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The RecordOrder message schema and its example, and the order rules that connect the example command to the
 * order OpenAPI examples: the creation request, the fingerprint, the snapshot total and the reserved IDs.
 */
class RecordOrderContractTest {
    private static final Path CONTRACTS = Path.of("src/main/resources/contracts");
    private static final ContractDocument SCHEMA = ContractDocument.messageSchema(
            CONTRACTS.resolve("record-order.schema.json"));
    private static final JsonObject COMMAND = (JsonObject) ContractDocument.read(
            CONTRACTS.resolve("examples/record-order.json"));
    private static final String DEFS = "#/$defs/";
    private static final String LONG_MAX = "9223372036854775807";
    private static final BigDecimal NUMERIC_19_2_MAX = new BigDecimal("99999999999999999.99");
    private static final ObjectMapper CANONICAL_JSON = new ObjectMapper();
    private static final String PUT_ORDER = "#/paths/~1api~1v1~1buyers~1{buyerId}~1orders~1{orderId}/put";

    @Test
    void isAValidSelfContainedJsonSchema() {
        assertEquals(List.of(), SCHEMA.structureErrors());
    }

    @Test
    void theExampleCommandMatchesTheSchema() {
        assertEquals(List.of(), SCHEMA.errors("#", COMMAND));
    }

    @Test
    void idsAreCanonicalSignedLongText() {
        assertEquals(List.of(), SCHEMA.unexpected(DEFS + "snowflakeId",
                List.of("1", "9007199254740993", "9223372036854775806", LONG_MAX),
                List.of(1, "0", "01", "+1", "-1", "1.0", "1e3", "1\n", "1\r\n", "\t1", "１", "9223372036854775808",
                        "9999999999999999999")));
    }

    @Test
    void amountsAreFixedTwoDecimalText() {
        assertEquals(List.of(), SCHEMA.unexpected(DEFS + "money",
                List.of("0.00", "19.95", "99999999999999999.99"),
                List.of(0, "-0.00", "+1.00", "01.00", "1", "1.0", "1.000", "1e2", "1.00\n", "100000000000000000.00")));
    }

    @Test
    void timestampsAreUtcWithAtMostSixFractionalDigits() {
        assertEquals(List.of(), SCHEMA.unexpected(DEFS + "timestamp",
                List.of("2026-10-06T12:00:00Z", "2026-10-06T12:00:00.1Z", "2026-10-06T12:00:00.123456Z"),
                List.of("2026-10-06T12:00:00.1234567Z", "2026-10-06T12:00:00.123456789Z", "2026-10-06T12:00:00+04:00",
                        "2026-10-06T12:00:00+00:00", "2026-10-06t12:00:00z", "2026-10-06T12:00:00Z\n",
                        "2026-02-30T12:00:00Z", "2026-10-06T25:00:00Z")));
    }

    @Test
    void productNamesAreNonblankUnicodeScalarText() {
        assertEquals(List.of(), SCHEMA.unexpected(DEFS + "orderItem/properties/productName",
                List.of("A", " Café ", "🚀", "A".repeat(200)),
                List.of("", " \t\n", "A".repeat(201), "A\u0000", "\ud800", "A\udfff", "\udc00\ud800")));
    }

    @Test
    void theCommandCarriesTheAcceptedHttpOrder() {
        JsonObject request = (JsonObject) OrderApiContractTest.API.node(PUT_ORDER
                + "/requestBody/content/application~1json/example");
        JsonObject accepted = OrderApiContractTest.example(PUT_ORDER + "/responses/202");
        assertEquals(accepted.getString("buyerId"), COMMAND.getString("buyerId"), "buyerId");
        assertEquals(accepted.getString("orderId"), COMMAND.getString("orderId"), "orderId");
        assertEquals(request, creation(COMMAND.getJsonObject("snapshot")),
                "The HTTP order request is the creation request of the command snapshot");
    }

    @Test
    void theFingerprintIsTheSha256OfTheNormalizedCreationRequest() {
        JsonObject creation = creation(COMMAND.getJsonObject("snapshot"));
        String buyerId = COMMAND.getString("buyerId");
        String orderId = COMMAND.getString("orderId");
        assertEquals(fingerprint(buyerId, orderId, creation), COMMAND.getString("requestFingerprint"));
        for (String quantity : List.of("2", "2.0", "2e0")) {
            JsonObject normalized = creation.copy();
            normalized.getJsonArray("items").getJsonObject(0).put("quantity", new BigDecimal(quantity));
            assertEquals(COMMAND.getString("requestFingerprint"), fingerprint(buyerId, orderId, normalized),
                    "Quantity " + quantity + " normalizes to the integer of the example");
        }
    }

    @Test
    void theSnapshotIsAnExactTotalOfDistinctItems() {
        JsonObject snapshot = COMMAND.getJsonObject("snapshot");
        JsonArray items = snapshot.getJsonArray("items");
        BigDecimal total = BigDecimal.ZERO;
        Set<String> pairs = new HashSet<>();
        for (int position = 0; position < items.size(); position++) {
            JsonObject item = items.getJsonObject(position);
            total = total.add(new BigDecimal(item.getString("unitPrice"))
                    .multiply(new BigDecimal(item.getValue("quantity").toString()), MathContext.UNLIMITED));
            assertTrue(pairs.add(item.getString("sellerId") + "/" + item.getString("productId")),
                    "Each seller/product pair occurs once");
            assertEquals(position, item.getInteger("itemPosition"), "itemPosition");
            assertEquals(snapshot.getString("currency"), item.getString("currency"), "Item currency");
        }
        assertEquals(0, total.compareTo(new BigDecimal(snapshot.getString("amount"))), "Total of price x quantity");
        assertTrue(total.compareTo(NUMERIC_19_2_MAX) <= 0, "The total fits NUMERIC(19,2)");
    }

    @Test
    void reservedIdsAreDistinctAndOlderThanTheCommand() {
        List<String> reserved = Stream.of("orderId", "sagaId", "commandId", "messageId", "resultMessageId")
                .map(COMMAND::getString).toList();
        assertEquals(reserved.size(), Set.copyOf(reserved).size(), "The command reserves distinct IDs");
        Instant occurredAt = Instant.parse(COMMAND.getString("occurredAt"));
        for (JsonObject node : ExampleIds.objects(COMMAND)) {
            for (String key : node.fieldNames()) {
                if (key.endsWith("Id")) {
                    assertFalse(ExampleIds.time(node.getString(key)).isAfter(occurredAt),
                            key + " was generated before occurredAt");
                }
            }
        }
    }

    @Test
    void exampleIdsFollowTheServiceIssuedIdRules() {
        List<Object> values = new ArrayList<>(OrderApiContractTest.API.examples().stream().map(Example::value)
                .toList());
        values.add(COMMAND);
        assertEquals(List.of(), ExampleIds.violations(values, Set.of("sellerId", "productId", "buyerId")));
    }

    /** The order creation request that a snapshot records: its currency and the requested items. */
    private static JsonObject creation(JsonObject snapshot) {
        JsonArray items = new JsonArray();
        snapshot.getJsonArray("items").forEach(item -> {
            JsonObject snapshotItem = (JsonObject) item;
            items.add(new JsonObject().put("sellerId", snapshotItem.getString("sellerId"))
                    .put("productId", snapshotItem.getString("productId"))
                    .put("quantity", snapshotItem.getValue("quantity")));
        });
        return new JsonObject().put("currency", snapshot.getString("currency")).put("items", items);
    }

    /** Fingerprint v1 of the order OpenAPI: SHA-256 of compact UTF-8 JSON with these keys in this order. */
    private static String fingerprint(String buyerId, String orderId, JsonObject creation) {
        List<Map<String, Object>> items = new ArrayList<>();
        creation.getJsonArray("items").forEach(item -> {
            JsonObject requested = (JsonObject) item;
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("sellerId", requested.getString("sellerId"));
            normalized.put("productId", requested.getString("productId"));
            normalized.put("quantity", new BigDecimal(requested.getValue("quantity").toString()).toBigIntegerExact());
            items.add(normalized);
        });
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("buyerId", buyerId);
        wire.put("orderId", orderId);
        wire.put("currency", creation.getString("currency"));
        wire.put("items", items);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(CANONICAL_JSON.writeValueAsString(wire)
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
