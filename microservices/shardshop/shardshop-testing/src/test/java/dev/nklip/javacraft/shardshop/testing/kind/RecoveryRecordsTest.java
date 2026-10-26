package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RecoveryRecordsTest {
    @Test void completeOriginalEvidencePreservesUnknownCostWithoutInventingZero() throws Exception {
        ObjectNode record=record();
        RecoveryRecords.validate(record);
        assertTrue(record.path("rows").path("order_items").get(0).path("unit_cost").isNull());
        ((ObjectNode)record.path("rows").path("order_items").get(0)).remove("unit_cost");
        assertThrows(IOException.class,()->RecoveryRecords.validate(record));
    }
    @Test void missingAllocationOrForeignSagaIsRejectedBeforeRecovery() throws Exception {
        ObjectNode missing=record();((ArrayNode)missing.path("rows").path("order_allocations")).removeAll();
        assertThrows(IOException.class,()->RecoveryRecords.validate(missing));
        ObjectNode foreign=record();((ObjectNode)foreign.path("rows").path("order_sagas").get(0)).put("order_id",99);
        assertThrows(IOException.class,()->RecoveryRecords.validate(foreign));
    }
    @Test void privateArtifactIntegrityRejectsModifiedOriginalHistory(@TempDir Path root) throws Exception {
        Path directory=root.toRealPath().resolve("record");ObjectNode record=record();
        BackupArtifacts bundle=BackupArtifacts.create(directory);bundle.writeJson("original-order.json",record);
        bundle.seal(RecoveryRecords.JSON.createObjectNode().put("purpose","original-order-recovery").put("orderId","1").put("recordSha256",RecoveryRecords.digest(record)));
        assertEquals(1,RecoveryRecords.load(directory).orderId());
        Files.writeString(directory.resolve("original-order.json"),"{}");
        assertThrows(IOException.class,()->RecoveryRecords.load(directory));
    }
    @Test void monetaryEvidenceRetainsAllNumericNineteenTwoDigits() throws Exception {
        JsonNode value=RecoveryRecords.JSON.readTree("{\"amount\":12345678901234567.89}");
        assertEquals(new java.math.BigDecimal("12345678901234567.89"),value.path("amount").decimalValue());
    }
    @Test void canonicalIncidentFingerprintIgnoresObjectOrderAndIntegerSpelling() throws Exception {
        JsonNode first=RecoveryRecords.JSON.readTree("{\"a\":1.0,\"b\":[2,3]}");
        JsonNode second=RecoveryRecords.JSON.readTree("{\"b\":[2.00,3],\"a\":1}");
        assertEquals(RecoveryRecords.digest(RecoveryRecords.canonical(first)),RecoveryRecords.digest(RecoveryRecords.canonical(second)));
    }
    static ObjectNode record() {
        ObjectNode document=RecoveryRecords.JSON.createObjectNode().put("formatVersion",1).put("orderId","1").put("shard","shard-a");
        document.putArray("shards").add("shard-a");ObjectNode rows=document.putObject("rows"),schemas=document.putObject("schemas");
        for(String table:RecoveryRecords.TABLES) rows.putArray(table);
        rows.withArray("buyers").addObject().put("buyer_id",2);
        rows.withArray("order_allocations").addObject().put("order_id",1).put("buyer_id",2).put("run_name","original-run").put("request_ordinal",5);
        rows.withArray("orders").addObject().put("order_id",1).put("buyer_id",2).put("status","PENDING_LEDGER").put("currency","USD")
                .put("total_amount","10.00").put("request_fingerprint","f".repeat(64)).put("currency_policy_version",2).put("created_at","2026-10-26T00:00:00Z");
        rows.withArray("order_sagas").addObject().put("saga_id",3).put("order_id",1).put("command_id",4).put("status","PENDING_LEDGER")
                .put("created_at","2026-10-26T00:00:00Z").putNull("ledger_result");
        rows.withArray("order_items").addObject().put("order_id",1).put("item_position",0).put("seller_id",5).put("product_id",6)
                .put("quantity",2).put("unit_price","5.00").putNull("unit_cost").put("product_name","Original product").put("currency","USD");
        for(String table:RecoveryRecords.TABLES) {
            ArrayNode columns=schemas.putArray(table);
            if(rows.path(table).isEmpty()) columns.addObject().put("name","unused");
            else rows.path(table).get(0).fieldNames().forEachRemaining(name->columns.addObject().put("name",name));
        }
        return document;
    }
}
