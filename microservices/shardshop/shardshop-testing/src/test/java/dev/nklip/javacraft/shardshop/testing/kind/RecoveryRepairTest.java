package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class RecoveryRepairTest {
    @Test void conflictingExistingHistoryCannotBeOverwritten() throws Exception {
        ArrayNode original=RecoveryRecords.JSON.createArrayNode();original.addObject().put("order_id",1).put("buyer_id",2);
        RecoveryRepair.validateTargetRows(original,RecoveryRecords.JSON.createArrayNode());
        RecoveryRepair.validateTargetRows(original,original.deepCopy());
        ArrayNode other=original.deepCopy();((ObjectNode)other.get(0)).put("buyer_id",9);
        assertThrows(IOException.class,()->RecoveryRepair.validateTargetRows(original,other));
    }
    @Test void ledgerOnlyIdentityCannotReplaceOriginalImmutableSnapshot() throws Exception {
        ObjectNode document=RecoveryRecordsTest.record();
        var original=new RecoveryRecords.Original("shard-a",1,document,"original-digest");
        ObjectNode decision=decision();
        RecoveryRepair.validateIdentity(original,decision);
        decision.put("saga_id",99);
        ObjectNode wrongIdentity=decision;
        assertThrows(IOException.class,()->RecoveryRepair.validateIdentity(original,wrongIdentity));
        decision=decision();((ObjectNode)decision.path("snapshot").path("items").get(0)).put("quantity",3);
        ObjectNode wrong=decision;
        assertThrows(IOException.class,()->RecoveryRepair.validateIdentity(original,wrong));
    }
    @Test void confirmedRepairRequiresOriginalCostAndCompletedProfitEvidence() throws Exception {
        ObjectNode document=RecoveryRecordsTest.record();
        ((ObjectNode)document.path("rows").path("orders").get(0)).put("status","CONFIRMED");
        var original=new RecoveryRecords.Original("shard-a",1,document,"original-digest");
        assertThrows(IOException.class,()->RecoveryRepair.validateProfitEvidence(original));
    }
    @Test void authenticEnvelopeMustAgreeWithPermanentOutcomeAndDecisionTime() throws Exception {
        ObjectNode decision=RecoveryRecords.JSON.createObjectNode().put("outcome","RECORDED").put("result_schema_version",1)
                .put("currency","USD").put("amount","10.00").putNull("rejection_reason").put("decided_at","2026-10-26T12:00:00+00:00");
        ObjectNode result=RecoveryRecords.JSON.createObjectNode().put("outcome","RECORDED").put("type","LedgerRecorded").put("schemaVersion",1)
                .put("currency","USD").put("amount","10.00").put("decidedAt","2026-10-26T12:00:00Z");
        RecoveryRepair.validateResultDecision(decision,result);
        result.put("outcome","REJECTED");
        assertThrows(IOException.class,()->RecoveryRepair.validateResultDecision(decision,result));
        result.put("outcome","RECORDED").put("decidedAt","2026-10-26T12:00:01Z");
        assertThrows(IOException.class,()->RecoveryRepair.validateResultDecision(decision,result));
    }
    @Test void releasedReservationRequiresItsPermanentClosedGuard() throws Exception {
        RecoveryRepair.validateReservationFence(true,true,true,false);
        RecoveryRepair.validateReservationFence(true,false,false,true);
        assertThrows(IOException.class,()->RecoveryRepair.validateReservationFence(false,false,true,false));
        assertThrows(IOException.class,()->RecoveryRepair.validateReservationFence(true,false,true,false));
        assertThrows(IOException.class,()->RecoveryRepair.validateReservationFence(true,true,false,true));
    }
    private static ObjectNode decision() {
        ObjectNode decision=RecoveryRecords.JSON.createObjectNode().put("order_id",1).put("buyer_id",2).put("saga_id",3).put("command_id",4)
                .put("request_fingerprint","f".repeat(64)).put("currency","USD").put("amount","10.00");
        var snapshot=decision.putObject("snapshot").put("amount","10.00").put("currency","USD");
        snapshot.putArray("items").addObject().put("itemPosition",0).put("sellerId","5").put("productId","6").put("quantity",2)
                .put("productName","Original product").put("unitPrice","5.00").put("currency","USD");
        return decision;
    }
}
