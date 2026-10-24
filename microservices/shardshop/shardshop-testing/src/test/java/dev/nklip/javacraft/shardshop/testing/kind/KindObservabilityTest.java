package dev.nklip.javacraft.shardshop.testing.kind;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KindObservabilityTest {
    @Test
    void parserRejectsMissingNonFiniteAndAmbiguousOwnedSeries() throws Exception {
        var one = KindObservability.parse("""
                # HELP shardshop_ledger_pending_results Pending rows
                unrelated_metric{kind="histogram"} 2 # {trace_id="ignored"} 1
                shardshop_ledger_pending_results 4
                """);
        assertEquals(4, KindObservability.value(one, "shardshop_ledger_pending_results", Map.of()));
        assertThrows(IOException.class, () -> KindObservability.value(one, "shardshop_ledger_snapshot_success", Map.of()));
        for (String invalid : List.of("NaN", "+Inf", "-Inf")) {
            var samples = KindObservability.parse("shardshop_ledger_pending_results " + invalid);
            assertThrows(IOException.class, () -> KindObservability.value(samples, "shardshop_ledger_pending_results", Map.of()));
        }
        var duplicates = KindObservability.parse("""
                shardshop_ledger_pending_results 4
                shardshop_ledger_pending_results 5
                """);
        assertThrows(IOException.class, () -> KindObservability.value(duplicates,
                "shardshop_ledger_pending_results", Map.of()));
    }

    @Test
    void ledgerValidationRequiresSuccessfulFreshCompleteSnapshot() throws Exception {
        String complete = """
                shardshop_ledger_pool_active 0
                shardshop_ledger_pool_available 1
                shardshop_ledger_pool_awaiting 0
                shardshop_ledger_pool_max 4
                shardshop_ledger_snapshot_success 1
                shardshop_ledger_snapshot_age_seconds 9
                shardshop_ledger_pending_results 0
                shardshop_ledger_oldest_pending_age_seconds 0
                shardshop_ledger_conflicting_commands 2
                """;
        assertDoesNotThrow(() -> KindObservability.validateLedger(KindObservability.parse(complete)));
        assertThrows(IOException.class, () -> KindObservability.validateLedger(KindObservability.parse(
                complete.replace("snapshot_age_seconds 9", "snapshot_age_seconds 31"))));
        assertThrows(IOException.class, () -> KindObservability.validateLedger(KindObservability.parse(
                complete.replace("snapshot_success 1", "snapshot_success 0"))));
        assertThrows(IOException.class, () -> KindObservability.validateLedger(KindObservability.parse(
                complete.replace("pending_results 0", "pending_results NaN"))));
    }

    @Test
    void queueValidationRequiresCountersAndEffectiveCapsForMainAndParkingQueues() throws Exception {
        String complete = """
                [
                  {"name":"ledger.record-order","messages_ready":1,"messages_unacknowledged":0,"message_bytes_ready":16,"message_bytes_unacknowledged":0,"effective_policy_definition":{"overflow":"reject-publish","max-length":1000,"max-length-bytes":16777216}},
                  {"name":"order.ledger-results","messages_ready":0,"messages_unacknowledged":1,"message_bytes_ready":0,"message_bytes_unacknowledged":16,"effective_policy_definition":{"overflow":"reject-publish","max-length":1000,"max-length-bytes":16777216}},
                  {"name":"ledger.record-order.dlq","messages_ready":0,"messages_unacknowledged":0,"message_bytes_ready":0,"message_bytes_unacknowledged":0,"effective_policy_definition":{"overflow":"reject-publish","max-length":1000,"max-length-bytes":16777216}},
                  {"name":"order.ledger-results.dlq","messages_ready":0,"messages_unacknowledged":0,"message_bytes_ready":0,"message_bytes_unacknowledged":0,"effective_policy_definition":{"overflow":"reject-publish","max-length":1000,"max-length-bytes":16777216}}
                ]
                """;
        assertDoesNotThrow(() -> KindObservability.validateQueues(KindLab.parse(complete)));
        assertDoesNotThrow(() -> KindObservability.validateQueues(KindLab.parse(complete
                .replace("\"messages_ready\":1", "\"messages_ready\":1001")
                .replace("\"messages_unacknowledged\":0", "\"messages_unacknowledged\":1"))));
        assertThrows(IOException.class, () -> KindObservability.validateQueues(KindLab.parse(
                complete.replace("\"messages_ready\":1", "\"messages_ready\":null"))));
        assertThrows(IOException.class, () -> KindObservability.validateQueues(KindLab.parse(
                complete.replace("\"overflow\":\"reject-publish\"", "\"overflow\":\"drop-head\""))));
        assertThrows(IOException.class, () -> KindObservability.validateQueues(KindLab.parse(
                complete.replace("\"max-length\":1000", "\"max-length\":0"))));
        // RabbitMQ 4.3's CLI lists empty quorum byte fields as "", while its enabled
        // management API returns numeric zeros. Missing bytes must not silently become zero.
        String emptyManagement = complete.replace("\"messages_ready\":1", "\"messages_ready\":0")
                .replace("\"messages_unacknowledged\":1", "\"messages_unacknowledged\":0")
                .replace("\"message_bytes_ready\":16", "\"message_bytes_ready\":0")
                .replace("\"message_bytes_unacknowledged\":16", "\"message_bytes_unacknowledged\":0");
        assertDoesNotThrow(() -> KindObservability.validateQueues(KindLab.parse(emptyManagement)));
        String cliEmptyBytes = emptyManagement
                .replace("\"message_bytes_ready\":0", "\"message_bytes_ready\":\"\"")
                .replace("\"message_bytes_unacknowledged\":0", "\"message_bytes_unacknowledged\":\"\"");
        assertThrows(IOException.class, () -> KindObservability.validateQueues(KindLab.parse(cliEmptyBytes)));
        assertThrows(IOException.class, () -> KindObservability.validateQueues(KindLab.parse(
                complete.replace("\"message_bytes_ready\":16,", ""))));
    }

    @Test
    void replicationRequiresTwoStreamingStandbysAndNumericBacklog() {
        assertDoesNotThrow(() -> KindObservability.validateReplication("2|2048", "shard-a"));
        assertThrows(IOException.class, () -> KindObservability.validateReplication("1|2048", "shard-a"));
        assertThrows(IOException.class, () -> KindObservability.validateReplication("2|NaN", "shard-a"));
    }
}
