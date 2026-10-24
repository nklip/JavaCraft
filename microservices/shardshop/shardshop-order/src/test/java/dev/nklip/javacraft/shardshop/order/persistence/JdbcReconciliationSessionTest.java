package dev.nklip.javacraft.shardshop.order.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nklip.javacraft.shardshop.common.jdbc.DatabasePhases;
import dev.nklip.javacraft.shardshop.order.ordering.OrderModel.ItemSnapshot;
import dev.nklip.javacraft.shardshop.order.ordering.OrderModel.Order;
import dev.nklip.javacraft.shardshop.order.reconciliation.ReconciliationFailure;
import dev.nklip.javacraft.shardshop.order.reconciliation.ReconciliationStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JdbcReconciliationSessionTest {
    private static final Instant NOW = Instant.parse("2026-10-24T01:02:03.123456789Z");
    private static final Order ORDER = new Order(31, 41, "a".repeat(64), "USD", new BigDecimal("39.90"),
            "PENDING_LEDGER", null);
    private static final ItemSnapshot ITEM = new ItemSnapshot(0, 51, 61, 2, "Product \"one\"",
            new BigDecimal("19.95"), new BigDecimal("12.50"), "USD");
    private static final ReconciliationStore.Saga SAGA = new ReconciliationStore.Saga(71, 81, ORDER,
            List.of(ITEM), 2, "PENDING_LEDGER", 2, NOW.minusSeconds(1));
    private final Connection connection = mock();
    private final PreparedStatement candidates = mock();
    private final PreparedStatement count = mock();
    private final PreparedStatement saga = mock();
    private final PreparedStatement items = mock();
    private final PreparedStatement update = mock();
    private final PreparedStatement identity = mock();
    private final PreparedStatement outbox = mock();
    private final ResultSet candidateRows = mock();
    private final ResultSet sagaRows = mock();
    private final ResultSet itemRows = mock();
    private JdbcReconciliationSession session;

    @BeforeEach
    void setup() throws SQLException {
        PreparedStatement timeouts = mock();
        ResultSet configured = mock();
        when(configured.next()).thenReturn(true);
        when(timeouts.executeQuery()).thenReturn(configured);
        when(connection.prepareStatement(startsWith("SELECT set_config"))).thenReturn(timeouts);
        when(connection.prepareStatement(startsWith("SELECT saga_id, order_id FROM ordering.order_sagas")))
                .thenReturn(candidates);
        when(connection.prepareStatement(startsWith("SELECT count(*) FROM ordering.order_sagas"))).thenReturn(count);
        when(connection.prepareStatement(startsWith("SELECT s.saga_id, s.command_id"))).thenReturn(saga);
        when(connection.prepareStatement(startsWith("SELECT item_position, seller_id"))).thenReturn(items);
        when(connection.prepareStatement(startsWith("UPDATE ordering.order_sagas"))).thenReturn(update);
        when(connection.prepareStatement(startsWith("INSERT INTO ordering.order_result_ids"))).thenReturn(identity);
        when(connection.prepareStatement(startsWith("INSERT INTO ordering.order_outbox"))).thenReturn(outbox);
        when(candidates.executeQuery()).thenReturn(candidateRows);
        when(saga.executeQuery()).thenReturn(sagaRows);
        when(items.executeQuery()).thenReturn(itemRows);
        session = new JdbcReconciliationSession(connection,
                new DatabasePhases.Deadline(() -> 0, 2_000_000_000L));
    }

    @Test
    void candidatesUseDueTimeAndSagaKeysetAndIncludeUnpublishedSagas() throws SQLException {
        when(candidateRows.next()).thenReturn(true, true, false);
        when(candidateRows.getLong("saga_id")).thenReturn(72L, 74L);
        when(candidateRows.getLong("order_id")).thenReturn(42L, 44L);

        assertEquals(List.of(new ReconciliationStore.Candidate(72, 42),
                new ReconciliationStore.Candidate(74, 44)), session.pending(NOW, 71, 2));
        verify(candidates).setTimestamp(1, Timestamp.from(NOW));
        verify(candidates).setLong(2, 71);
        verify(candidates).setInt(3, 2);
        ArgumentCaptor<String> sql = ArgumentCaptor.captor();
        verify(connection, atLeastOnce()).prepareStatement(sql.capture());
        String query = sql.getAllValues().stream().filter(value -> value.startsWith("SELECT saga_id, order_id"))
                .findFirst().orElseThrow();
        assertTrue(query.contains("reconciliation_attempts<5"));
        assertTrue(query.contains("ORDER BY saga_id LIMIT ?"));
        assertFalse(query.contains("order_outbox"));
    }

    @Test
    void exhaustedCountIncludesOnlyPendingSagasAtFiveAttempts() throws SQLException {
        ResultSet rows = mock();
        when(count.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true);
        when(rows.getLong(1)).thenReturn(7L);
        assertEquals(7, session.exhausted());
        verify(connection).prepareStatement(contains("status='PENDING_LEDGER' AND reconciliation_attempts=5"));
    }

    @Test
    void sagaLocksBeforeReadingTheSavedOrderAndItems() throws SQLException {
        when(sagaRows.next()).thenReturn(true);
        when(sagaRows.getLong("saga_id")).thenReturn(71L);
        when(sagaRows.getLong("command_id")).thenReturn(81L);
        when(sagaRows.getLong("buyer_id")).thenReturn(31L);
        when(sagaRows.getLong("order_id")).thenReturn(41L);
        when(sagaRows.getString("request_fingerprint")).thenReturn(ORDER.fingerprint());
        when(sagaRows.getString("currency")).thenReturn("USD");
        when(sagaRows.getBigDecimal("total_amount")).thenReturn(ORDER.totalAmount());
        when(sagaRows.getString("order_status")).thenReturn("PENDING_LEDGER");
        when(sagaRows.getString("saga_status")).thenReturn("PENDING_LEDGER");
        when(sagaRows.getInt("currency_policy_version")).thenReturn(2);
        when(sagaRows.getInt("reconciliation_attempts")).thenReturn(2);
        when(sagaRows.getTimestamp("next_reconciliation_at")).thenReturn(Timestamp.from(SAGA.nextReconciliationAt()));
        when(itemRows.next()).thenReturn(true, false);
        when(itemRows.getInt("item_position")).thenReturn(0);
        when(itemRows.getLong("seller_id")).thenReturn(51L);
        when(itemRows.getLong("product_id")).thenReturn(61L);
        when(itemRows.getInt("quantity")).thenReturn(2);
        when(itemRows.getString("product_name")).thenReturn(ITEM.productName());
        when(itemRows.getBigDecimal("unit_price")).thenReturn(ITEM.unitPrice());
        when(itemRows.getBigDecimal("unit_cost")).thenReturn(ITEM.unitCost());
        when(itemRows.getString("currency")).thenReturn("USD");

        assertEquals(SAGA, session.saga(71).orElseThrow());
        verify(connection).prepareStatement(contains("WHERE s.saga_id=? FOR UPDATE OF s"));
        var ordered = inOrder(saga, items);
        ordered.verify(saga).executeQuery();
        ordered.verify(items).executeQuery();
    }

    @Test
    void lostEligibilityNeverInsertsAnotherCommand() throws SQLException {
        ReconciliationFailure failure = assertThrows(ReconciliationFailure.class,
                () -> session.enqueue(SAGA, 91, 92, NOW, NOW.plusSeconds(240)));
        assertInstanceOf(SQLException.class, failure.getCause());
        verify(connection).prepareStatement(contains("reconciliation_attempts<5"));
        verify(connection).prepareStatement(contains("published_at IS NULL"));
        verify(update).setInt(5, 2);
        verify(update).setTimestamp(6, Timestamp.from(NOW));
        verify(identity, never()).executeUpdate();
        verify(outbox, never()).executeUpdate();
    }

    @Test
    void replayRetainsBusinessIdentityAndSnapshotWhileAllocatingTransportIds() throws Exception {
        when(update.executeUpdate()).thenReturn(1);
        when(identity.executeUpdate()).thenReturn(1);
        when(outbox.executeUpdate()).thenReturn(1);

        session.enqueue(SAGA, 9007199254740993L, 9007199254740994L, NOW, NOW.plusSeconds(240));

        var ordered = inOrder(update, identity, outbox);
        ordered.verify(update).executeUpdate();
        ordered.verify(identity).executeUpdate();
        ordered.verify(outbox).executeUpdate();
        verify(identity).setLong(1, 9007199254740993L);
        verify(identity).setLong(2, 9007199254740994L);
        verify(identity).setLong(3, 71L);
        ArgumentCaptor<String> json = ArgumentCaptor.captor();
        verify(outbox).setString(eq(4), json.capture());
        JsonNode envelope = new ObjectMapper().readTree(json.getValue());
        assertEquals(2, envelope.path("schemaVersion").intValue());
        assertEquals("9007199254740993", envelope.path("messageId").textValue());
        assertEquals("9007199254740994", envelope.path("resultMessageId").textValue());
        assertEquals("81", envelope.path("commandId").textValue());
        assertEquals("71", envelope.path("sagaId").textValue());
        assertEquals("41", envelope.path("orderId").textValue());
        assertEquals("31", envelope.path("buyerId").textValue());
        assertEquals(ORDER.fingerprint(), envelope.path("requestFingerprint").textValue());
        assertEquals("2026-10-24T01:02:03.123456Z", envelope.path("occurredAt").textValue());
        assertEquals("39.90", envelope.path("snapshot").path("amount").textValue());
        assertEquals("USD", envelope.path("snapshot").path("currency").textValue());
        JsonNode item = envelope.path("snapshot").path("items").get(0);
        assertEquals("Product \"one\"", item.path("productName").textValue());
        assertEquals("19.95", item.path("unitPrice").textValue());
        assertFalse(item.has("unitCost"));
        verify(connection, never()).commit();
    }

    @Test
    void failedPermanentIdentityOrOutboxInsertRaisesTransactionFailure() throws SQLException {
        when(update.executeUpdate()).thenReturn(1);
        when(identity.executeUpdate()).thenReturn(1);
        when(outbox.executeUpdate()).thenThrow(new SQLException("duplicate transport ID"));
        assertThrows(ReconciliationFailure.class,
                () -> session.enqueue(SAGA, 91, 92, NOW, NOW.plusSeconds(240)));
        verify(identity).executeUpdate();
        verify(outbox).executeUpdate();
    }
}
