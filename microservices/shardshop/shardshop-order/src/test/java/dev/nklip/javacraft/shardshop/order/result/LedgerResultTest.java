package dev.nklip.javacraft.shardshop.order.result;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class LedgerResultTest {
    private static final String RECORDED = """
            {"type":"LedgerRecorded","schemaVersion":1,"messageId":"9223372036854775807",
             "commandId":"2","sagaId":"3","orderId":"4","buyerId":"5",
             "requestFingerprint":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
             "occurredAt":"2026-10-06T12:00:01.000001Z","amount":"99999999999999999.99",
             "currency":"USD","outcome":"RECORDED","decidedAt":"2026-10-06T12:00:01Z"}
            """;

    @Test
    void acceptsRecordedAndRejectedWithCanonicalPayloadIdentity() {
        LedgerResult recorded = parse(RECORDED);
        assertEquals(Long.MAX_VALUE, recorded.messageId());
        assertEquals(new java.math.BigDecimal("99999999999999999.99"), recorded.amount());
        assertEquals(Instant.parse("2026-10-06T12:00:01.000001Z"), recorded.occurredAt());
        assertNull(recorded.rejectionReason());
        assertEquals(64, recorded.payloadFingerprint().length());

        String reordered = RECORDED.replace("\"type\":\"LedgerRecorded\",\"schemaVersion\":1,",
                "\"schemaVersion\":1,\"type\":\"LedgerRecorded\",");
        assertEquals(recorded.payloadFingerprint(), parse(reordered).payloadFingerprint());

        LedgerResult rejected = parse(RECORDED.replace("LedgerRecorded", "LedgerRejected")
                .replace("\"RECORDED\"", "\"REJECTED\"")
                .replace("\"outcome\":\"REJECTED\"", "\"outcome\":\"REJECTED\",\"rejectionReason\":\"UNSUPPORTED_CURRENCY\""));
        assertEquals("REJECTED", rejected.outcome());
        assertEquals("UNSUPPORTED_CURRENCY", rejected.rejectionReason());
    }

    @Test
    void rejectsInvalidIdentityAndMoneyBeforeConversion() {
        for (String id : new String[]{"0", "01", "+1", "9223372036854775808", "1\n"}) {
            assertMalformed(RECORDED.replace("\"messageId\":\"9223372036854775807\"",
                    "\"messageId\":\"" + id.replace("\n", "\\n") + "\""));
        }
        assertMalformed(RECORDED.replace("\"messageId\":\"9223372036854775807\"", "\"messageId\":9223372036854775807"));
        for (String amount : new String[]{"-1.00", "01.00", "1.0", "100000000000000000.00"}) {
            assertMalformed(RECORDED.replace("99999999999999999.99", amount));
        }
        assertMalformed(RECORDED.replace("\"amount\":\"99999999999999999.99\"", "\"amount\":1.00"));
    }

    @Test
    void rejectsInvalidDatesAndOrdering() {
        for (String timestamp : new String[]{"2026-02-30T12:00:01Z", "2026-10-06T12:00:60Z",
                "2026-10-06T12:60:01Z", "2026-10-06T12:00:01.1234567Z",
                "2026-10-06T12:00:01+00:00", "2026-10-06t12:00:01z"}) {
            assertMalformed(RECORDED.replace("2026-10-06T12:00:01.000001Z", timestamp));
        }
        assertMalformed(RECORDED.replace("\"decidedAt\":\"2026-10-06T12:00:01Z\"",
                "\"decidedAt\":\"2026-10-06T12:00:02Z\""));
    }

    @Test
    void rejectsWrongOutcomeFieldsAndUnexpectedProperties() {
        assertMalformed(RECORDED.replace("\"RECORDED\"", "\"REJECTED\""));
        assertMalformed(RECORDED.replace("\"outcome\":\"RECORDED\"", "\"outcome\":\"RECORDED\",\"rejectionReason\":\"UNSUPPORTED_CURRENCY\""));
        assertMalformed(RECORDED.replace("\"currency\":\"USD\"", "\"currency\":\"usd\""));
        assertMalformed(RECORDED.replace("\"schemaVersion\":1", "\"schemaVersion\":2"));
        assertMalformed(RECORDED.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"extra\":true"));
        assertMalformed(RECORDED.replace("\"type\":\"LedgerRecorded\"", "\"type\":\"LedgerRecorded\",\"type\":\"LedgerRecorded\""));
        assertMalformed(RECORDED.replace("LedgerRecorded", "LedgerRejected").replace("\"RECORDED\"", "\"REJECTED\""));
    }

    @Test
    void rejectsAbsentAndOversizedBodies() {
        assertThrows(MalformedResultException.class, () -> LedgerResult.parse(null));
        assertThrows(MalformedResultException.class, () -> LedgerResult.parse(new byte[1_048_577]));
    }

    private static LedgerResult parse(String json) {
        return LedgerResult.parse(json.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertMalformed(String json) {
        assertThrows(MalformedResultException.class, () -> parse(json));
    }
}
