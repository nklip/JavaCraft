package dev.nklip.javacraft.shardshop.order.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nklip.javacraft.shardshop.common.jdbc.DatabasePhases;
import dev.nklip.javacraft.shardshop.order.stock.StockFailure;
import dev.nklip.javacraft.shardshop.order.stock.StockStore.Saga;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

import static dev.nklip.javacraft.shardshop.order.ordering.OrderModel.*;
import static dev.nklip.javacraft.shardshop.order.stock.StockFailure.Reason.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JdbcStockSessionTest {
    private static final ItemSnapshot ITEM = new ItemSnapshot(0, 51, 61, 2, "Product \"one\"",
            new BigDecimal("19.95"), new BigDecimal("12.50"), "USD");
    private static final Saga SAGA = new Saga(71, 81,
            new Order(31, 41, "a".repeat(64), "USD", new BigDecimal("39.90"), "PENDING_STOCK", null),
            List.of(ITEM), 7, 2);
    private final Connection connection = mock();
    private final PreparedStatement guardInsert = mock();
    private final PreparedStatement guardRead = mock();
    private final PreparedStatement guardClose = mock();
    private final PreparedStatement reservationInsert = mock();
    private final PreparedStatement reservationRead = mock();
    private final PreparedStatement release = mock();
    private final PreparedStatement decrement = mock();
    private final PreparedStatement restore = mock();
    private final PreparedStatement productRead = mock();
    private final ResultSet guardRows = mock();
    private final ResultSet reservationRows = mock();
    private final ResultSet releaseRows = mock();
    private final ResultSet productRows = mock();
    private JdbcStockSession session;

    @BeforeEach
    void setup() throws SQLException {
        PreparedStatement timeouts = mock();
        ResultSet configured = mock();
        when(configured.next()).thenReturn(true);
        when(timeouts.executeQuery()).thenReturn(configured);
        when(connection.prepareStatement(startsWith("SELECT set_config"))).thenReturn(timeouts);
        when(connection.prepareStatement(startsWith("INSERT INTO catalog.stock_reservation_guards"))).thenReturn(guardInsert);
        when(connection.prepareStatement(startsWith("SELECT closed"))).thenReturn(guardRead);
        when(connection.prepareStatement(startsWith("UPDATE catalog.stock_reservation_guards"))).thenReturn(guardClose);
        when(connection.prepareStatement(startsWith("INSERT INTO catalog.stock_reservations "))).thenReturn(reservationInsert);
        when(connection.prepareStatement(startsWith("SELECT r.quantity"))).thenReturn(reservationRead);
        when(connection.prepareStatement(startsWith("UPDATE catalog.stock_reservations "))).thenReturn(release);
        when(connection.prepareStatement(startsWith("UPDATE catalog.products SET stock = stock -"))).thenReturn(decrement);
        when(connection.prepareStatement(startsWith("UPDATE catalog.products SET stock = stock +"))).thenReturn(restore);
        when(connection.prepareStatement(startsWith("SELECT seller_id FROM catalog.products"))).thenReturn(productRead);
        when(guardRead.executeQuery()).thenReturn(guardRows);
        when(guardRows.next()).thenReturn(true);
        when(guardClose.executeUpdate()).thenReturn(1);
        when(reservationRead.executeQuery()).thenReturn(reservationRows);
        when(reservationRows.getInt("quantity")).thenReturn(2);
        when(reservationRows.getLong("seller_id")).thenReturn(51L);
        when(release.executeQuery()).thenReturn(releaseRows);
        when(productRead.executeQuery()).thenReturn(productRows);
        when(productRows.getLong("seller_id")).thenReturn(51L);
        when(reservationInsert.executeUpdate()).thenReturn(1);
        when(decrement.executeUpdate()).thenReturn(1);
        when(restore.executeUpdate()).thenReturn(1);
        session = new JdbcStockSession(connection, new DatabasePhases.Deadline(() -> 0, 2_000_000_000L),
                Clock.fixed(Instant.parse("2026-10-16T01:02:03.123456789Z"), ZoneOffset.UTC));
    }

    @Test
    void immutableSagaCannotBeChangedThroughItsInputList() {
        List<ItemSnapshot> source = new ArrayList<>(List.of(ITEM));
        Saga saga = new Saga(71, 81, SAGA.order(), source, 7, 2);
        source.clear();
        assertEquals(List.of(ITEM), saga.items());
        assertThrows(UnsupportedOperationException.class, () -> saga.items().clear());
    }

    @Test
    void newReservationsDecreaseStockInProductOrder() throws SQLException {
        ItemSnapshot later = new ItemSnapshot(1, 51, 91, 3, "later", ITEM.unitPrice(), ITEM.unitCost(), "USD");
        session.reserve(41, List.of(later, ITEM));
        var ordered = inOrder(guardInsert, guardRead, reservationInsert, decrement);
        ordered.verify(guardInsert).executeUpdate();
        ordered.verify(guardRead).executeQuery();
        ordered.verify(reservationInsert).setLong(2, 61);
        ordered.verify(reservationInsert).executeUpdate();
        ordered.verify(decrement).setLong(2, 61);
        ordered.verify(decrement).executeUpdate();
        ordered.verify(reservationInsert).setLong(2, 91);
        ordered.verify(reservationInsert).executeUpdate();
        ordered.verify(decrement).setLong(2, 91);
        ordered.verify(decrement).executeUpdate();
        verify(connection, times(2)).prepareStatement(contains("ON CONFLICT (order_id, product_id) DO NOTHING"));
    }

    @Test
    void retryOfIdenticalActiveReservationDoesNotDecreaseStockAgain() throws SQLException {
        when(reservationInsert.executeUpdate()).thenReturn(0);
        when(reservationRows.next()).thenReturn(true);
        session.reserve(41, List.of(ITEM));
        verify(decrement, never()).executeUpdate();
    }

    @Test
    void releasedDifferentQuantityOrForeignSellerReservationsRefuseReplay() throws SQLException {
        when(reservationInsert.executeUpdate()).thenReturn(0);
        when(reservationRows.next()).thenReturn(true);
        when(reservationRows.getTimestamp("released_at")).thenReturn(Timestamp.from(Instant.EPOCH));
        assertConflict(() -> session.reserve(41, List.of(ITEM)));
        when(reservationRows.getTimestamp("released_at")).thenReturn(null);
        when(reservationRows.getInt("quantity")).thenReturn(3);
        assertConflict(() -> session.reserve(41, List.of(ITEM)));
        when(reservationRows.getInt("quantity")).thenReturn(2);
        when(reservationRows.getLong("seller_id")).thenReturn(99L);
        assertConflict(() -> session.reserve(41, List.of(ITEM)));
        verify(decrement, never()).executeUpdate();
    }

    @Test
    void onlyKnownProductsOfTheExpectedSellerCanBeReportedOutOfStock() throws SQLException {
        when(decrement.executeUpdate()).thenReturn(0);
        when(productRows.next()).thenReturn(true);
        assertEquals(OUT_OF_STOCK, assertThrows(StockFailure.class,
                () -> session.reserve(41, List.of(ITEM))).reason());
        when(productRows.getLong("seller_id")).thenReturn(99L);
        assertConflict(() -> session.reserve(41, List.of(ITEM)));
        when(productRows.next()).thenReturn(false);
        assertEquals(UNAVAILABLE, assertThrows(StockFailure.class,
                () -> session.reserve(41, List.of(ITEM))).reason());
    }

    @Test
    void aClosedGuardFencesEvenAWorkerThatHasNotInsertedItsFirstReservation() throws SQLException {
        when(guardRows.getBoolean("closed")).thenReturn(true);
        assertConflict(() -> session.reserve(41, List.of(ITEM)));
        verify(reservationInsert, never()).executeUpdate();
        verify(decrement, never()).executeUpdate();
    }

    @Test
    void releaseClosesGuardEvenWhenNoReservationsExist() throws SQLException {
        session.release(41, List.of(ITEM));
        var ordered = inOrder(guardInsert, guardRead, guardClose, reservationRead);
        ordered.verify(guardInsert).executeUpdate();
        ordered.verify(guardRead).executeQuery();
        ordered.verify(guardClose).executeUpdate();
        ordered.verify(reservationRead).executeQuery();
        verify(restore, never()).executeUpdate();
    }

    @Test
    void onlyAnActiveToReleasedTransitionRestoresItsReturnedQuantity() throws SQLException {
        when(reservationRows.next()).thenReturn(true);
        when(releaseRows.next()).thenReturn(true, false);
        when(releaseRows.getInt("quantity")).thenReturn(2);
        session.release(41, List.of(ITEM));
        session.release(41, List.of(ITEM));
        verify(restore).setInt(1, 2);
        verify(restore).executeUpdate();
        verify(connection, times(2)).prepareStatement(contains("AND released_at IS NULL RETURNING quantity"));
    }

    @Test
    void foreignOrChangedReleaseSnapshotCannotRestoreStock() throws SQLException {
        when(reservationRows.next()).thenReturn(true);
        when(reservationRows.getInt("quantity")).thenReturn(3);
        assertConflict(() -> session.release(41, List.of(ITEM)));
        verify(release, never()).executeQuery();
        verify(restore, never()).executeUpdate();
    }

    @Test
    void staleLeaseCannotAllocateIdsOrEmitAnEnvelope() throws SQLException {
        PreparedStatement transition = mock();
        when(connection.prepareStatement(startsWith("UPDATE ordering.order_sagas"))).thenReturn(transition);
        LongSupplier ids = mock();
        assertFalse(session.pendingLedger(SAGA, ids));
        verifyNoInteractions(ids);
        verify(connection, never()).prepareStatement(startsWith("INSERT INTO ordering.order_outbox"));
        verify(transition).setLong(1, 71);
        verify(transition).setLong(2, 41);
        verify(transition).setLong(3, 7);
        verify(connection).prepareStatement(contains("stock_lease_until > clock_timestamp()"));
    }

    @Test
    void successfulTransitionReservesIdsAndEmitsCompleteOrderedSchemaEnvelope() throws Exception {
        PreparedStatement transition = mock();
        PreparedStatement order = mock();
        PreparedStatement identity = mock();
        PreparedStatement outbox = mock();
        when(connection.prepareStatement(startsWith("UPDATE ordering.order_sagas"))).thenReturn(transition);
        when(connection.prepareStatement(startsWith("UPDATE ordering.orders"))).thenReturn(order);
        when(connection.prepareStatement(startsWith("INSERT INTO ordering.order_result_ids"))).thenReturn(identity);
        when(connection.prepareStatement(startsWith("INSERT INTO ordering.order_outbox"))).thenReturn(outbox);
        for (PreparedStatement statement : List.of(transition, order, identity, outbox)) {
            when(statement.executeUpdate()).thenReturn(1);
        }
        LongSupplier ids = mock();
        when(ids.getAsLong()).thenReturn(9007199254740993L, 9007199254740994L);
        assertTrue(session.pendingLedger(SAGA, ids));
        var ordered = inOrder(transition, order, ids, identity, outbox);
        ordered.verify(transition).executeUpdate();
        ordered.verify(order).executeUpdate();
        ordered.verify(ids, times(2)).getAsLong();
        ordered.verify(identity).executeUpdate();
        ordered.verify(outbox).executeUpdate();
        ArgumentCaptor<String> json = ArgumentCaptor.captor();
        verify(outbox).setString(eq(4), json.capture());
        JsonNode envelope = new ObjectMapper().readTree(json.getValue());
        assertEquals("RecordOrder", envelope.path("type").textValue());
        assertEquals(1, envelope.path("schemaVersion").intValue());
        assertEquals("9007199254740993", envelope.path("messageId").textValue());
        assertEquals("9007199254740994", envelope.path("resultMessageId").textValue());
        assertEquals("81", envelope.path("commandId").textValue());
        assertEquals("71", envelope.path("sagaId").textValue());
        assertEquals("41", envelope.path("orderId").textValue());
        assertEquals("31", envelope.path("buyerId").textValue());
        assertEquals("a".repeat(64), envelope.path("requestFingerprint").textValue());
        assertEquals("2026-10-16T01:02:03.123456Z", envelope.path("occurredAt").textValue());
        JsonNode snapshot = envelope.path("snapshot");
        assertEquals("39.90", snapshot.path("amount").textValue());
        assertEquals("USD", snapshot.path("currency").textValue());
        JsonNode item = snapshot.path("items").get(0);
        assertEquals(0, item.path("itemPosition").intValue());
        assertEquals("51", item.path("sellerId").textValue());
        assertEquals("61", item.path("productId").textValue());
        assertEquals(2, item.path("quantity").intValue());
        assertEquals("Product \"one\"", item.path("productName").textValue());
        assertEquals("19.95", item.path("unitPrice").textValue());
        assertEquals("USD", item.path("currency").textValue());
        assertFalse(item.has("unitCost"));
        verify(connection, never()).commit();
    }

    @Test
    void claimRestoresLeaseAndImmutableSnapshotsInRequestOrder() throws SQLException {
        PreparedStatement claim = mock();
        PreparedStatement items = mock();
        ResultSet claimed = mock();
        ResultSet snapshots = mock();
        when(connection.prepareStatement(startsWith("WITH candidate AS"))).thenReturn(claim);
        when(connection.prepareStatement(startsWith("SELECT item_position"))).thenReturn(items);
        when(claim.executeQuery()).thenReturn(claimed);
        when(items.executeQuery()).thenReturn(snapshots);
        when(claimed.next()).thenReturn(true);
        when(claimed.getLong("saga_id")).thenReturn(71L);
        when(claimed.getLong("command_id")).thenReturn(81L);
        when(claimed.getLong("stock_lease_version")).thenReturn(7L);
        when(claimed.getInt("stock_failures")).thenReturn(2);
        when(claimed.getLong("buyer_id")).thenReturn(31L);
        when(claimed.getLong("order_id")).thenReturn(41L);
        when(claimed.getString("request_fingerprint")).thenReturn("a".repeat(64));
        when(claimed.getString("currency")).thenReturn("USD");
        when(claimed.getString("status")).thenReturn("PENDING_STOCK");
        when(claimed.getBigDecimal("total_amount")).thenReturn(new BigDecimal("39.90"));
        when(snapshots.next()).thenReturn(true, false);
        when(snapshots.getLong("seller_id")).thenReturn(51L);
        when(snapshots.getLong("product_id")).thenReturn(61L);
        when(snapshots.getInt("quantity")).thenReturn(2);
        when(snapshots.getString("product_name")).thenReturn(ITEM.productName());
        when(snapshots.getBigDecimal("unit_price")).thenReturn(ITEM.unitPrice());
        when(snapshots.getBigDecimal("unit_cost")).thenReturn(ITEM.unitCost());
        when(snapshots.getString("currency")).thenReturn("USD");
        assertEquals(SAGA, session.claim(Duration.ofSeconds(30)).orElseThrow());
        verify(claim).setLong(1, 30000);
        verify(connection).prepareStatement(contains("FOR UPDATE OF s SKIP LOCKED"));
        verify(connection).prepareStatement(contains("q.resolved_at IS NULL"));
        verify(connection).prepareStatement(contains("ORDER BY item_position"));
    }

    @Test
    void renewRetryCancelAndReleaseRequireCurrentUnexpiredOwnership() throws SQLException {
        PreparedStatement transition = mock();
        PreparedStatement order = mock();
        when(connection.prepareStatement(startsWith("UPDATE ordering.order_sagas"))).thenReturn(transition);
        when(connection.prepareStatement(startsWith("UPDATE ordering.orders"))).thenReturn(order);
        when(transition.executeUpdate()).thenReturn(0);
        assertFalse(session.renew(SAGA, Duration.ofSeconds(30)));
        assertFalse(session.cancel(SAGA));
        assertFalse(session.released(SAGA));
        session.retry(SAGA, Duration.ofSeconds(4));
        verifyNoInteractions(order);
        ArgumentCaptor<String> sql = ArgumentCaptor.captor();
        verify(connection, atLeastOnce()).prepareStatement(sql.capture());
        List<String> changes = sql.getAllValues().stream().filter(value -> value.startsWith("UPDATE ordering.order_sagas")).toList();
        assertEquals(4, changes.size());
        assertTrue(changes.stream().allMatch(value -> value.contains("stock_lease_version = ?")
                && value.contains("stock_lease_until > clock_timestamp()")));
        assertTrue(changes.stream().anyMatch(value -> value.contains("stock_failures::bigint + 1")));
        assertTrue(changes.stream().noneMatch(value -> value.contains("reconciliation_attempts")));
    }

    private void assertConflict(org.junit.jupiter.api.function.Executable work) {
        StockFailure failure = assertThrows(StockFailure.class, work);
        assertEquals(RESERVATION_CONFLICT, failure.reason());
        assertEquals("RESERVATION_CONFLICT", failure.getMessage());
        assertNull(failure.getCause());
    }
}
