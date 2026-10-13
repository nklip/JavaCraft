package dev.nklip.javacraft.shardshop.ledger;

import dev.nklip.javacraft.shardshop.common.contract.ContractDocument;
import dev.nklip.javacraft.shardshop.common.contract.ExampleIds;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static dev.nklip.javacraft.shardshop.common.contract.ContractDocument.without;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** The ledger result message schema and its two examples: alternative decisions for one example command. */
class LedgerResultContractTest {
    private static final Path CONTRACTS = Path.of("src/main/resources/contracts");
    private static final ContractDocument SCHEMA = ContractDocument.messageSchema(
            CONTRACTS.resolve("ledger-result.schema.json"));
    private static final JsonObject RECORDED = (JsonObject) ContractDocument.read(
            CONTRACTS.resolve("examples/ledger-recorded.json"));
    private static final JsonObject REJECTED = (JsonObject) ContractDocument.read(
            CONTRACTS.resolve("examples/ledger-rejected.json"));
    private static final String DEFS = "#/$defs/";
    private static final String LONG_MAX = "9223372036854775807";

    @Test
    void isAValidSelfContainedJsonSchema() {
        assertEquals(List.of(), SCHEMA.structureErrors());
    }

    @Test
    void bothExampleResultsMatchTheSchema() {
        assertEquals(List.of(), SCHEMA.errors("#", RECORDED), "LedgerRecorded example");
        assertEquals(List.of(), SCHEMA.errors("#", REJECTED), "LedgerRejected example");
    }

    @Test
    void theTypeOutcomeAndRejectionReasonAgree() {
        assertEquals(List.of(), SCHEMA.unexpected("#", List.of(RECORDED, REJECTED),
                List.of(RECORDED.copy().put("rejectionReason", "UNSUPPORTED_CURRENCY"),
                        without(REJECTED, "rejectionReason"), RECORDED.copy().put("outcome", "REJECTED"),
                        REJECTED.copy().put("type", "LedgerRecorded"), RECORDED.copy().put("extra", 1))));
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
    void bothResultsAnswerOneCommandWithOneAmount() {
        for (String key : List.of("messageId", "commandId", "sagaId", "orderId", "buyerId", "requestFingerprint",
                "amount", "currency")) {
            assertEquals(RECORDED.getString(key), REJECTED.getString(key), key);
        }
    }

    @Test
    void eachResultIsCreatedAfterItsDecisionAndItsIds() {
        for (JsonObject result : List.of(RECORDED, REJECTED)) {
            Instant occurredAt = Instant.parse(result.getString("occurredAt"));
            assertFalse(Instant.parse(result.getString("decidedAt")).isAfter(occurredAt),
                    result.getString("type") + " is decided before it occurs");
            for (String key : result.fieldNames()) {
                if (key.endsWith("Id")) {
                    assertFalse(ExampleIds.time(result.getString(key)).isAfter(occurredAt),
                            result.getString("type") + " " + key + " was generated before occurredAt");
                }
            }
        }
    }

    @Test
    void exampleIdsFollowTheServiceIssuedIdRules() {
        assertEquals(List.of(), ExampleIds.violations(List.of(RECORDED, REJECTED), Set.of("buyerId")));
    }
}
