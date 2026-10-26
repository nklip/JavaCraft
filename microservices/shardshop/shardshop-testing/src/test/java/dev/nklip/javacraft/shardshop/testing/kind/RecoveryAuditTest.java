package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.io.TempDir;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import static org.junit.jupiter.api.Assertions.*;

class RecoveryAuditTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void matchingPendingOrderNeedsItsOriginalIdentityAllocationAndImmutableItems() throws Exception {
        ObjectNode evidence = evidence();
        RecoveryAudit.validateEvidence(evidence);
        assertNull(RecoveryAudit.mismatch(evidence, pending(evidence)));
        assertEquals("ORDER_ABSENT", RecoveryAudit.mismatch(evidence, null));
        ObjectNode changed = pending(evidence);
        changed.put("allocated", false);
        assertEquals("ALLOCATION_ABSENT", RecoveryAudit.mismatch(evidence, changed));
        for (String field : List.of("orderId", "buyerId", "sagaId", "commandId", "requestFingerprint", "currency")) {
            changed = pending(evidence);
            changed.put(field, "changed");
            assertEquals("IDENTITY_MISMATCH", RecoveryAudit.mismatch(evidence, changed), field);
        }
        changed = pending(evidence);
        ((ObjectNode) changed.path("items").get(0)).put("quantity", 2);
        assertEquals("SNAPSHOT_MISMATCH", RecoveryAudit.mismatch(evidence, changed));
    }

    @Test
    void terminalAgreementRequiresOriginalSavedDecisionRatherThanTransportTimes() throws Exception {
        ObjectNode evidence = evidence();
        ObjectNode state = pending(evidence);
        state.put("status", "CONFIRMED").put("sagaStatus", "CONFIRMED");
        state.set("result", evidence.path("bindings").get(0).path("envelope").deepCopy());
        assertNull(RecoveryAudit.mismatch(evidence, state));
        ((ObjectNode) state.path("result")).put("messageId", "999").put("occurredAt", "2026-10-27T12:00:00Z");
        assertNull(RecoveryAudit.mismatch(evidence, state));
        ((ObjectNode) state.path("result")).put("decidedAt", "2026-10-27T12:00:00Z");
        assertEquals("SAVED_OUTCOME_MISMATCH", RecoveryAudit.mismatch(evidence, state));
        state.put("status", "CANCELLED").put("sagaStatus", "CANCELLED");
        assertEquals("OUTCOME_MISMATCH", RecoveryAudit.mismatch(evidence, state));
    }

    @Test
    void inconsistentSagaAndUnexpectedSavedPendingOutcomeRemainUnresolved() throws Exception {
        ObjectNode evidence = evidence();
        ObjectNode state = pending(evidence);
        state.put("sagaStatus", "CONFIRMED");
        assertEquals("ORDER_SAGA_STATE_MISMATCH", RecoveryAudit.mismatch(evidence, state));
        state = pending(evidence);
        state.set("result", evidence.path("result").deepCopy());
        assertEquals("SAVED_OUTCOME_MISMATCH", RecoveryAudit.mismatch(evidence, state));
        state = pending(evidence);
        state.put("status", "PENDING_STOCK").put("sagaStatus", "PENDING_STOCK");
        assertEquals("OUTCOME_MISMATCH", RecoveryAudit.mismatch(evidence, state));
    }

    @Test
    void evidenceRejectsMissingBindingsChangedOriginalEnvelopeAndInventedTimestamps() throws Exception {
        ObjectNode valid = evidence();
        RecoveryAudit.validateEvidence(valid);
        ObjectNode changed = valid.deepCopy();
        changed.putArray("bindings");
        assertThrows(IOException.class, () -> RecoveryAudit.validateEvidence(changed));
        ObjectNode wrongBinding = valid.deepCopy();
        ((ObjectNode) wrongBinding.path("bindings").get(0)).put("resultMessageId", "999");
        assertThrows(IOException.class, () -> RecoveryAudit.validateEvidence(wrongBinding));
        ObjectNode wrongEnvelope = valid.deepCopy();
        ((ObjectNode) wrongEnvelope.path("bindings").get(0).path("envelope")).put("sagaId", "999");
        assertThrows(IOException.class, () -> RecoveryAudit.validateEvidence(wrongEnvelope));
        ObjectNode wrongTime = valid.deepCopy();
        ((ObjectNode) wrongTime.path("bindings").get(0).path("envelope")).remove("occurredAt");
        assertThrows(IOException.class, () -> RecoveryAudit.validateEvidence(wrongTime));
        ObjectNode wrongAmount = valid.deepCopy();
        wrongAmount.put("amount", "99.00");
        assertThrows(IOException.class, () -> RecoveryAudit.validateEvidence(wrongAmount));
    }

    @Test
    void rejectedEvidencePreservesOriginalReasonAndCanonicalPayloadIsOrderIndependent() throws Exception {
        ObjectNode evidence = evidence();
        evidence.put("outcome", "REJECTED").put("reason", "UNSUPPORTED_CURRENCY");
        ((ObjectNode) evidence.path("result")).put("type", "LedgerRejected").put("outcome", "REJECTED")
                .put("rejectionReason", "UNSUPPORTED_CURRENCY");
        ObjectNode envelope = ((ObjectNode) evidence.path("result")).deepCopy().put("messageId", "42")
                .put("occurredAt", "2026-10-26T12:00:01Z");
        ((ObjectNode) evidence.path("bindings").get(0)).set("envelope", envelope);
        RecoveryAudit.validateEvidence(evidence);
        ObjectNode state = pending(evidence);
        state.put("status", "CANCELLED").put("sagaStatus", "CANCELLED").put("reason", "UNSUPPORTED_CURRENCY");
        state.set("result", envelope);
        assertNull(RecoveryAudit.mismatch(evidence, state));
        state.put("reason", "OTHER_REASON");
        assertEquals("OUTCOME_MISMATCH", RecoveryAudit.mismatch(evidence, state));
        assertEquals("{\"a\":[{\"x\":1,\"z\":2}],\"b\":3}",
                RecoveryAudit.canonical(JSON.readTree("{\"b\":3,\"a\":[{\"z\":2,\"x\":1}]}")).toString());
    }

    @Test
    void publicationAttemptMayAdvanceWhileEveryPermanentEnvelopeAndIdentityMustStayExact() throws Exception {
        ObjectNode exported = evidence();
        ObjectNode current = exported.deepCopy();
        ((ObjectNode) current.path("bindings").get(0)).put("publicationAttempt", 2);
        assertTrue(RecoveryAudit.samePermanentEvidence(exported, current));
        ((ObjectNode) current.path("bindings").get(0)).put("publicationAttempt", 0);
        assertFalse(RecoveryAudit.samePermanentEvidence(exported, current));
        current = exported.deepCopy();
        ((ObjectNode) current.path("bindings").get(0)).put("publicationAttempt", 2);
        ((ObjectNode) current.path("bindings").get(0).path("envelope")).put("occurredAt", "2026-10-27T12:00:00Z");
        assertFalse(RecoveryAudit.samePermanentEvidence(exported, current));
        current = exported.deepCopy();
        ((ObjectNode) current.path("bindings").get(0)).put("resultMessageId", "999");
        assertFalse(RecoveryAudit.samePermanentEvidence(exported, current));
    }

    @Test
    void canonicalEvidenceRetainsNumericSnapshotPrecision() throws Exception {
        var input = new java.io.ByteArrayInputStream("{\"amount\":12345678901234567.89}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("12345678901234567.89", RecoveryAudit.canonical(RecoveryAudit.readLine(input)).path("amount").decimalValue().toPlainString());
    }

    @Test
    void auditCompletesAllReservationsWithinTenPhysicalConnections(@TempDir Path temporary) throws Exception {
        Path export=emptyExport(temporary.toRealPath());
        BudgetAccess access=new BudgetAccess(10);
        RecoveryAudit.Report report;
        try { report=RecoveryAudit.audit(access,export); }
        catch(Exception failed) {
            throw new AssertionError("Audit could not complete 60 reservations: physical acquisitions="+access.opened
                    +", permitted="+access.budget+", budget exhausted="+access.exhausted);
        }
        assertEquals(60,report.orphanReservations());
        assertEquals(0,report.decisions());
        assertTrue(access.opened<=10,"Audit connections must not grow with reservation count");
        for(Connection connection:access.connections) verify(connection).close();
    }

    @Test
    void auditClosesEveryAcquiredConnectionAfterAcquisitionFailure(@TempDir Path temporary) throws Exception {
        Path export=emptyExport(temporary.toRealPath());
        BudgetAccess access=new BudgetAccess(3);
        assertThrows(Exception.class,()->RecoveryAudit.audit(access,export));
        for(Connection connection:access.connections) verify(connection).close();
    }

    @Test
    void auditClosesRemainingSessionsWhenOneCloseFails(@TempDir Path temporary) throws Exception {
        Path export=emptyExport(temporary.toRealPath());
        BudgetAccess access=new BudgetAccess(10);access.failFirstClose=true;
        assertThrows(Exception.class,()->RecoveryAudit.audit(access,export));
        assertFalse(access.exhausted);
        assertEquals(7,access.connections.size());
        for(Connection connection:access.connections) verify(connection).close();
    }

    @Test
    void auditOwnsAndClosesSessionWhosePrimaryPreflightFails(@TempDir Path temporary) throws Exception {
        Path export=emptyExport(temporary.toRealPath());
        BudgetAccess access=new BudgetAccess(10);access.primary=false;
        assertThrows(Exception.class,()->RecoveryAudit.audit(access,export));
        assertEquals(1,access.connections.size());
        verify(access.connections.getFirst()).close();
    }

    private static Path emptyExport(Path root) throws Exception {
        Path directory=root.resolve("ledger-export");BackupArtifacts artifacts=BackupArtifacts.create(directory);
        Files.write(artifacts.newFile("ledger.jsonl"),new byte[0]);
        artifacts.seal(JSON.createObjectNode().put("exportType","ledger-recovery-v1").put("decisionCount",0));
        return directory;
    }

    /** A finite physical connection budget models startup capacity without asserting an incidental SQL sequence. */
    private static final class BudgetAccess implements RecoveryAccess {
        private final int budget;
        private int opened;
        private boolean exhausted;
        private boolean failFirstClose;
        private boolean primary=true;
        private final List<Connection> connections=new ArrayList<>();
        BudgetAccess(int budget) {this.budget=budget;}
        @Override public List<String> shards() {return List.of("shard-a","shard-b","shard-c");}
        @Override public Connection open(String database) throws Exception {
            if(++opened>budget) { exhausted=true;throw new SQLException("Physical recovery connection budget exhausted"); }
            Connection connection=mock(Connection.class);
            if(connections.isEmpty() && failFirstClose) doThrow(new SQLException("Injected close failure")).when(connection).close();
            connections.add(connection);
            when(connection.createStatement()).thenAnswer(call->{
                Statement statement=mock(Statement.class);
                when(statement.executeQuery(anyString())).thenAnswer(query->rows(query.getArgument(0)));
                return statement;
            });
            when(connection.prepareStatement(anyString())).thenAnswer(call->{
                PreparedStatement statement=mock(PreparedStatement.class);
                when(statement.executeQuery()).thenAnswer(query->rows(call.getArgument(0)));
                return statement;
            });
            return connection;
        }
        private ResultSet rows(String sql) throws SQLException {
            ResultSet rows=mock(ResultSet.class);AtomicInteger position=new AtomicInteger();
            if(sql.contains("FROM catalog.stock_reservations")) {
                when(rows.next()).thenAnswer(call->position.incrementAndGet()<=20);
                when(rows.getLong(1)).thenAnswer(call->100L+position.get());
                when(rows.getLong(2)).thenAnswer(call->200L+position.get());
                when(rows.getInt(3)).thenReturn(1);when(rows.getBoolean(4)).thenReturn(false);
            } else if(sql.contains("pg_is_in_recovery()")) {
                when(rows.next()).thenAnswer(call->position.incrementAndGet()==1);when(rows.getString(1)).thenReturn(primary?"false|off":"true|off");
            } else if(sql.contains("count(*) FROM ledger.ledger_operations")) {
                when(rows.next()).thenAnswer(call->position.incrementAndGet()==1);when(rows.getString(1)).thenReturn("0");
            } else when(rows.next()).thenReturn(false);
            return rows;
        }
    }

    private static ObjectNode pending(ObjectNode evidence) {
        ObjectNode state = evidence.deepCopy();
        state.put("allocated", true).put("status", "PENDING_LEDGER").put("sagaStatus", "PENDING_LEDGER");
        state.putNull("reason").putNull("result");
        state.set("items", evidence.path("snapshot").path("items").deepCopy());
        return state;
    }

    private static ObjectNode evidence() {
        ObjectNode result = JSON.createObjectNode().put("type", "LedgerRecorded").put("schemaVersion", 1)
                .put("orderId", "10").put("buyerId", "11").put("sagaId", "12").put("commandId", "13")
                .put("requestFingerprint", "a".repeat(64)).put("amount", "5.00").put("currency", "USD")
                .put("outcome", "RECORDED").put("decidedAt", "2026-10-26T12:00:00Z");
        ObjectNode record = result.deepCopy();
        record.remove(List.of("type", "schemaVersion"));
        record.put("resultSchemaVersion", 1).putNull("reason");
        record.set("result", result);
        ObjectNode snapshot = record.putObject("snapshot").put("amount", "5.00").put("currency", "USD");
        snapshot.putArray("items").addObject().put("itemPosition", 0).put("sellerId", "20").put("productId", "21")
                .put("quantity", 1).put("productName", "Saved name").put("unitPrice", "5.00").put("currency", "USD");
        ObjectNode binding = record.putArray("bindings").addObject().put("messageId", "41").put("resultMessageId", "42")
                .put("createdAt", "2026-10-26T12:00:01Z").put("publicationAttempt", 1);
        binding.set("envelope", result.deepCopy().put("messageId", "42").put("occurredAt", "2026-10-26T12:00:01Z"));
        ObjectNode command = JSON.createObjectNode().put("type", "RecordOrder").put("schemaVersion", 2)
                .put("messageId", "41").put("resultMessageId", "42").put("orderId", "10").put("buyerId", "11")
                .put("sagaId", "12").put("commandId", "13").put("requestFingerprint", "a".repeat(64))
                .put("occurredAt", "2026-10-26T11:59:59Z");
        command.set("snapshot", snapshot.deepCopy());
        binding.set("commandEnvelope", command);
        return record;
    }
}
