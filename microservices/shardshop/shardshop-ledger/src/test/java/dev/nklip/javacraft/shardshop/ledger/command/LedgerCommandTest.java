package dev.nklip.javacraft.shardshop.ledger.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LedgerCommandTest {
    private static final Path EXAMPLE = Path.of("../shardshop-order/src/main/resources/contracts/examples/record-order.json");

    private static String example() throws IOException {
        return Files.readString(EXAMPLE);
    }

    @Test
    void acceptsTheOrderContractAndEquivalentIntegralNumberSpellings() throws IOException {
        LedgerCommand original = LedgerCommand.parse(example().getBytes(StandardCharsets.UTF_8));
        LedgerCommand decimal = LedgerCommand.parse(example().replace("\"quantity\": 2", "\"quantity\": 2e0")
                .getBytes(StandardCharsets.UTF_8));
        assertEquals("39.90", original.amount().toPlainString());
        assertEquals(original.requestFingerprint(), decimal.requestFingerprint());
        assertEquals(original.payloadFingerprint(), decimal.payloadFingerprint());
        assertEquals(original.envelope(), decimal.envelope());
    }

    @Test
    void rejectsMalformedWireAndCrossFieldMismatches() throws IOException {
        String valid = example();
        String[] malformed = {
                valid.replace("\"quantity\": 2", "\"quantity\": 2.5"),
                valid.replace("\"amount\": \"39.90\"", "\"amount\": \"39.89\""),
                valid.replace("\"itemPosition\": 0", "\"itemPosition\": 1"),
                valid.replace("\"currency\": \"EUR\",\n    \"items\"", "\"currency\": \"USD\",\n    \"items\""),
                valid.replace("\"resultMessageId\": \"100925016375300100\"",
                        "\"resultMessageId\": \"100925016375300099\""),
                valid.replace("\"occurredAt\": \"2026-10-06T12:00:00Z\"",
                        "\"occurredAt\": \"2026-02-30T12:00:00Z\""),
                valid.replace("\"productName\": \"Travel mug\"", "\"productName\": \"\\u0000\""),
                valid.replace("\"type\": \"RecordOrder\"", "\"type\": \"RecordOrder\", \"type\": \"RecordOrder\""),
                valid.replace("\"type\": \"RecordOrder\"", "\"type\": \"RecordOrder\", \"extra\": true"),
                valid.replace("\"requestFingerprint\": \"d792169145bd02ef98e712245fcaccc895c64dd60279db5a5dc2342c4d1fff2f\"",
                        "\"requestFingerprint\": \"0000000000000000000000000000000000000000000000000000000000000000\"")
        };
        for (String document : malformed) {
            assertThrows(MalformedCommandException.class,
                    () -> LedgerCommand.parse(document.getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Test
    void rejectsInvalidUtf8() {
        assertThrows(MalformedCommandException.class, () -> LedgerCommand.parse(new byte[]{(byte) 0xC3, (byte) 0x28}));
    }

    @Test
    void rejectsMidnightTwentyFourWithoutNormalizingTheInstant() throws IOException {
        String invalid = example().replace("2026-10-06T12:00:00Z", "2026-10-06T24:00:00.0Z");
        assertThrows(MalformedCommandException.class,
                () -> LedgerCommand.parse(invalid.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void everyIdMustBeCanonicalPositiveLongText() throws IOException {
        String valid = example();
        String[][] ids = {
                {"messageId", "100925016375300099"}, {"resultMessageId", "100925016375300100"},
                {"commandId", "100925016375300098"}, {"sagaId", "100925016375300097"},
                {"orderId", "100925016375300096"}, {"buyerId", "21380884070404097"},
                {"sellerId", "11234023833608192"}, {"productId", "11234023837802496"}
        };
        for (String[] id : ids) {
            String field = "\"" + id[0] + "\": \"" + id[1] + "\"";
            for (String replacement : new String[]{"0", "01", "9223372036854775808", "1.0", "-1", "1\\n"}) {
                String invalid = valid.replace(field, "\"" + id[0] + "\": \"" + replacement + "\"");
                assertThrows(MalformedCommandException.class,
                        () -> LedgerCommand.parse(invalid.getBytes(StandardCharsets.UTF_8)), id[0] + "=" + replacement);
            }
            String numeric = valid.replace(field, "\"" + id[0] + "\": 1");
            assertThrows(MalformedCommandException.class,
                    () -> LedgerCommand.parse(numeric.getBytes(StandardCharsets.UTF_8)), id[0] + " numeric");
        }
    }

    @Test
    void transportIdsAcceptThePositiveLongBounds() throws IOException {
        String valid = example().replace("\"messageId\": \"100925016375300099\"", "\"messageId\": \"1\"")
                .replace("\"resultMessageId\": \"100925016375300100\"",
                        "\"resultMessageId\": \"9223372036854775807\"");
        LedgerCommand command = LedgerCommand.parse(valid.getBytes(StandardCharsets.UTF_8));
        assertEquals(1L, command.messageId());
        assertEquals(Long.MAX_VALUE, command.resultMessageId());
    }

    @Test
    void moneyQuantityAndNameRespectTheirWireBounds() throws IOException {
        String valid = example();
        String amount = "\"amount\": \"39.90\"";
        String unitPrice = "\"unitPrice\": \"19.95\"";
        String quantity = "\"quantity\": 2";
        String name = "\"productName\": \"Travel mug\"";
        String[] invalid = {
                valid.replace(amount, "\"amount\": \"039.90\""),
                valid.replace(amount, "\"amount\": \"100000000000000000.00\""),
                valid.replace(amount, "\"amount\": 39.90"),
                valid.replace(unitPrice, "\"unitPrice\": \"-0.00\""),
                valid.replace(quantity, "\"quantity\": 0"),
                valid.replace(quantity, "\"quantity\": 2147483648"),
                valid.replace(quantity, "\"quantity\": \"2\""),
                valid.replace(name, "\"productName\": \"   \""),
                valid.replace(name, "\"productName\": \"" + "x".repeat(201) + "\"")
        };
        for (String document : invalid) {
            assertThrows(MalformedCommandException.class,
                    () -> LedgerCommand.parse(document.getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Test
    void brokerOversizePayloadIsRejectedBeforeMaterialization() throws IOException {
        byte[] example = example().getBytes(StandardCharsets.UTF_8);
        String padded = " ".repeat(1_048_577 - example.length) + new String(example, StandardCharsets.UTF_8);
        assertMalformed("Body is absent or larger than 1 MiB", padded);
    }

    /** Step 4.5 regression: only this check rejects an item whose currency differs from the snapshot currency. */
    @Test
    void itemCurrencyMustEqualTheSnapshotCurrency() throws IOException {
        ObjectNode command = node(example());
        ((ObjectNode) command.at("/snapshot/items/0")).put("currency", "USD");
        assertMalformed("Item currency differs from the snapshot currency", command.toString());
    }

    /** Step 4.5 regression: a consistent amount and fingerprint do not make a repeated seller/product pair valid. */
    @Test
    void sellerAndProductPairsAreUnique() throws IOException {
        assertEquals(2, LedgerCommand.parse(twoItems("11234023837802497").toString().getBytes(StandardCharsets.UTF_8))
                .snapshot().get("items").size());
        assertMalformed("Duplicate seller and product pair", twoItems("11234023837802496").toString());
    }

    /** Step 4.5 regression: the parking log names the broken rule, never the untrusted payload. */
    @Test
    void malformedReasonsNameTheBrokenRuleWithoutPayloadData() throws IOException {
        String valid = example();
        assertMalformed("Invalid JSON body", "{not-json");
        assertMalformed("ID must be a canonical decimal string in 1..9223372036854775807",
                valid.replace("\"orderId\": \"100925016375300096\"", "\"orderId\": \"0001\""));
        assertMalformed("Snapshot amount differs from the item total",
                valid.replace("\"amount\": \"39.90\"", "\"amount\": \"39.89\""));
        assertMalformed("requestFingerprint differs from the normalized request",
                valid.replace("d792169145bd02ef98e712245fcaccc895c64dd60279db5a5dc2342c4d1fff2f", "0".repeat(64)));
        assertMalformed("Invalid occurredAt timestamp", valid.replace("2026-10-06T12:00:00Z", "2026-02-30T12:00:00Z"));
        assertMalformed("itemPosition differs from its array index",
                valid.replace("\"itemPosition\": 0", "\"itemPosition\": 1"));
    }

    private static void assertMalformed(String reason, String document) {
        MalformedCommandException failure = assertThrows(MalformedCommandException.class,
                () -> LedgerCommand.parse(document.getBytes(StandardCharsets.UTF_8)));
        assertEquals("Malformed RecordOrder command: " + reason, failure.getMessage());
    }

    private static ObjectNode node(String document) throws IOException {
        return (ObjectNode) new ObjectMapper().readTree(document);
    }

    /** The example order with a second item of the same seller, and the contract's amount and fingerprint. */
    private static ObjectNode twoItems(String secondProductId) throws IOException {
        ObjectNode command = node(example());
        ArrayNode items = (ArrayNode) command.at("/snapshot/items");
        ObjectNode second = items.get(0).deepCopy();
        second.put("itemPosition", 1);
        second.put("productId", secondProductId);
        items.add(second);
        ((ObjectNode) command.get("snapshot")).put("amount", "79.80");
        String normalized = "{\"buyerId\":\"21380884070404097\",\"orderId\":\"100925016375300096\",\"currency\":\"EUR\","
                + "\"items\":[{\"sellerId\":\"11234023833608192\",\"productId\":\"11234023837802496\",\"quantity\":2},"
                + "{\"sellerId\":\"11234023833608192\",\"productId\":\"" + secondProductId + "\",\"quantity\":2}]}";
        try {
            command.put("requestFingerprint", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8))));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
        return command;
    }
}
