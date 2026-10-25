package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class KindUpgradeWitnessTest {
    private static ObjectNode witness() {
        ObjectNode result = (ObjectNode) KindLab.parse("""
                {"cluster":"shardshop-test-upgrade", "routing":{"version":"inventory"},
                 "systems":{"shard-a":"system|oid"}, "identities":{"pvc/data":"volume-uid","credential/order-app":"credential-hash"},
                 "shardshop-generator-identity":{"registryUid":"registry-uid"},
                 "shardshop-migration-topology":{"version":"inventory"},
                 "shardshop-snowflake-generators":{"highWaterMark":"10"},"rows":{}}
                """);
        ObjectNode rows = (ObjectNode) result.path("rows");
        for (String table : List.of("shard-a/ordering.orders", "ledger-db/ledger.ledger_operations",
                "shard-a/ordering.flyway_schema_history")) rows.putArray(table).add("a".repeat(64));
        return result;
    }

    @Test
    void normalGateOrdersAndNewMigrationHistoryMayBeAddedWithoutReplacingOldRows() {
        ObjectNode before = witness();
        ObjectNode after = before.deepCopy();
        ((ObjectNode) after.path("shardshop-snowflake-generators")).put("highWaterMark", "12");
        ((com.fasterxml.jackson.databind.node.ArrayNode) after.path("rows").path("shard-a/ordering.orders")).add("b".repeat(64));
        ((com.fasterxml.jackson.databind.node.ArrayNode) after.path("rows").path("shard-a/ordering.flyway_schema_history")).add("c".repeat(64));
        assertDoesNotThrow(() -> KindUpgradeWitness.validateRetained(before, after));
    }

    @Test
    void recreatedStorageOrRotatedExistingCredentialsCannotPass() {
        for (String key : List.of("pvc/data", "credential/order-app")) {
            ObjectNode before = witness();
            ObjectNode after = before.deepCopy();
            ((ObjectNode) after.path("identities")).put(key, "replacement");
            assertThrows(IOException.class, () -> KindUpgradeWitness.validateRetained(before, after), key);
        }
    }

    @Test
    void businessRowsAndAppliedVersionedHistoryCannotDisappearOrChange() {
        for (String table : List.of("shard-a/ordering.orders", "ledger-db/ledger.ledger_operations",
                "shard-a/ordering.flyway_schema_history")) {
            ObjectNode before = witness();
            ObjectNode after = before.deepCopy();
            ((ObjectNode) after.path("rows")).putArray(table).add("b".repeat(64));
            assertThrows(IOException.class, () -> KindUpgradeWitness.validateRetained(before, after), table);
            ((ObjectNode) after.path("rows")).remove(table);
            assertThrows(IOException.class, () -> KindUpgradeWitness.validateRetained(before, after), table);
        }
    }

    @Test
    void allocatorCannotRegressOrChangeItsRegistryBinding() {
        for (String value : List.of("9", "-1", "1024", "", "010")) {
            ObjectNode before = witness();
            ObjectNode after = before.deepCopy();
            ((ObjectNode) after.path("shardshop-snowflake-generators")).put("highWaterMark", value);
            assertThrows(IOException.class, () -> KindUpgradeWitness.validateRetained(before, after), value);
        }
        ObjectNode before = witness();
        ObjectNode after = before.deepCopy();
        ((ObjectNode) after.path("shardshop-generator-identity")).put("registryUid", "replacement");
        assertThrows(IOException.class, () -> KindUpgradeWitness.validateRetained(before, after));
    }

    @Test
    void emptyFreshLabCannotBePresentedAsRetainedUpgradeEvidence() {
        ObjectNode empty = witness();
        ((ObjectNode) empty.path("rows")).putArray("ledger-db/ledger.ledger_operations");
        assertThrows(IOException.class, () -> KindUpgradeWitness.validateBaseline(empty));
        ObjectNode production = witness().put("cluster", "shardshop");
        assertThrows(IOException.class, () -> KindUpgradeWitness.validateBaseline(production));
    }
}
