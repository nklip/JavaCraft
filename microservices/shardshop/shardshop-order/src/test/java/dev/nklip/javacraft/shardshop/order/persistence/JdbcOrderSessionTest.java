package dev.nklip.javacraft.shardshop.order.persistence;

import dev.nklip.javacraft.shardshop.common.jdbc.DatabasePhases;
import dev.nklip.javacraft.shardshop.order.ordering.OrderFailure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static dev.nklip.javacraft.shardshop.order.ordering.OrderModel.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JdbcOrderSessionTest {
    private final Connection connection = mock();
    private final PreparedStatement timeouts = mock();
    private final PreparedStatement statement = mock();
    private final ResultSet rows = mock();
    private JdbcOrderSession session;

    @BeforeEach
    void setup() throws SQLException {
        ResultSet configured = mock();
        when(configured.next()).thenReturn(true);
        when(timeouts.executeQuery()).thenReturn(configured);
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            return sql.startsWith("SELECT set_config") ? timeouts : statement;
        });
        when(statement.executeQuery()).thenReturn(rows);
        when(statement.executeUpdate()).thenReturn(1);
        session = new JdbcOrderSession(connection, new DatabasePhases.Deadline(() -> 0, 2_000_000_000L),
                OrderFailure.Reason.ORDER_STORE_UNAVAILABLE);
    }

    @Test
    void eachStatementRefreshesTheRemainingTransactionAndLockTimeout() throws SQLException {
        when(rows.next()).thenReturn(true);
        session.lock(4321);
        verify(connection).setNetworkTimeout(any(), eq(2000));
        verify(timeouts).setString(1, "2000");
        verify(timeouts).setString(2, "2000");
        verify(connection).prepareStatement("SELECT pg_advisory_xact_lock(?)");
        verify(statement).setLong(1, 4321);
    }

    @ParameterizedTest
    @EnumSource(SortDirection.class)
    void filteredKeysetBoundsOrdersBeforeJoiningAllItemsAndRetainsMicrosecondCursor(SortDirection sort) throws SQLException {
        Instant timestamp = Instant.parse("2026-10-21T10:20:30.123456Z");
        var after = new OrderCursor(timestamp, 9007199254740993L);
        assertEquals(List.of(), session.orders("US", Long.MAX_VALUE, null, sort, 26, after));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection, times(2)).prepareStatement(sql.capture());
        String listing = sql.getAllValues().getLast();
        assertTrue(listing.contains("JOIN ordering.buyers b ON b.buyer_id = o.buyer_id"));
        assertTrue(listing.contains("WHERE b.region = ? AND EXISTS (SELECT 1 FROM ordering.order_items matched "
                + "WHERE matched.order_id = o.order_id AND matched.product_id = ?)"));
        assertTrue(listing.contains("AND (o.created_at, o.order_id) " + (sort == SortDirection.ASC ? ">" : "<") + " (?, ?)"));
        assertTrue(listing.contains("ORDER BY o.created_at " + sort + ", o.order_id " + sort + " LIMIT ?)"));
        assertTrue(listing.endsWith("ORDER BY p.created_at " + sort + ", p.order_id " + sort + ", i.item_position ASC"));
        verify(statement).setString(1, "US");
        verify(statement).setLong(2, Long.MAX_VALUE);
        verify(statement).setObject(3, timestamp.atOffset(ZoneOffset.UTC));
        verify(statement).setLong(4, 9007199254740993L);
        verify(statement).setInt(5, 26);
    }

    @Test
    void unfilteredFirstPageHasNoProductOrCursorCondition() throws SQLException {
        assertEquals(List.of(), session.orders("EU", null, null, SortDirection.DESC, 101, null));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection, times(2)).prepareStatement(sql.capture());
        String listing = sql.getAllValues().getLast();
        assertFalse(listing.contains("matched"));
        assertFalse(listing.contains("AND (o.created_at, o.order_id)"));
        verify(statement).setString(1, "EU");
        verify(statement).setInt(2, 101);
    }

    @ParameterizedTest
    @EnumSource(SortDirection.class)
    void nameFilterUsesBoundLiteralSubstringInsideExistsBeforeItemJoin(SortDirection sort) throws SQLException {
        String name = "MiXeD %_' OR TRUE -- 🚀";
        Instant timestamp = Instant.parse("2026-10-21T10:20:30.123456Z");
        assertEquals(List.of(), session.orders("US", null, name, sort, 26, new OrderCursor(timestamp, 41)));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection, times(2)).prepareStatement(sql.capture());
        String listing = sql.getAllValues().getLast();
        assertTrue(listing.contains("WHERE b.region = ? AND EXISTS (SELECT 1 FROM ordering.order_items matched "
                + "WHERE matched.order_id = o.order_id AND strpos(lower(matched.product_name), lower(?)) > 0)"));
        assertTrue(listing.contains("b.name AS buyer_name"));
        assertTrue(listing.contains("SELECT p.*, i.product_id, i.product_name, i.quantity"));
        assertFalse(listing.contains(name));
        assertFalse(listing.contains("ILIKE"));
        assertFalse(listing.contains("matched.product_id = ?"));
        verify(statement).setString(1, "US");
        verify(statement).setString(2, name);
        verify(statement).setObject(3, timestamp.atOffset(ZoneOffset.UTC));
        verify(statement).setLong(4, 41);
        verify(statement).setInt(5, 26);
    }

    @Test
    void reconstructsEachOrderOnceWithAllItemsInStoredPositionOrder() throws SQLException {
        OffsetDateTime timestamp = OffsetDateTime.parse("2026-10-21T10:20:30.123456Z");
        when(rows.next()).thenReturn(true, true, true, false);
        when(rows.getLong("order_id")).thenReturn(10L, 10L, 20L);
        when(rows.getLong("buyer_id")).thenReturn(31L);
        when(rows.getString("buyer_name")).thenReturn("Stored Buyer Name");
        when(rows.getLong("product_id")).thenReturn(21L, 22L, 21L);
        when(rows.getString("product_name")).thenReturn("Saved second product", "Saved first product", "Saved second product");
        when(rows.getInt("quantity")).thenReturn(2, 3, 1);
        when(rows.getObject("created_at", OffsetDateTime.class)).thenReturn(timestamp);
        when(rows.getString("status")).thenReturn("CANCELLED");
        when(rows.getString("cancellation_reason")).thenReturn("OUT_OF_STOCK");
        when(rows.getString("currency")).thenReturn("USD");
        when(rows.getBigDecimal("total_amount")).thenReturn(new BigDecimal("39.90"));

        List<ListedOrder> result = session.orders("US", 21L, null, SortDirection.ASC, 26, null);

        assertEquals(List.of(
                new ListedOrder(10, 31, "Stored Buyer Name", timestamp.toInstant(), "CANCELLED", "OUT_OF_STOCK", new BigDecimal("39.90"), "USD",
                        List.of(new ListedItem(21, "Saved second product", 2), new ListedItem(22, "Saved first product", 3))),
                new ListedOrder(20, 31, "Stored Buyer Name", timestamp.toInstant(), "CANCELLED", "OUT_OF_STOCK", new BigDecimal("39.90"), "USD",
                        List.of(new ListedItem(21, "Saved second product", 1)))), result);
    }

    @Test
    void listingSqlFailureReturnsOnlyTheStableStoreFailure() throws SQLException {
        when(statement.executeQuery()).thenThrow(new SQLException("sensitive payload"));
        OrderFailure failure = assertThrows(OrderFailure.class,
                () -> session.orders("US", null, null, SortDirection.DESC, 26, null));
        assertEquals(OrderFailure.Reason.ORDER_STORE_UNAVAILABLE, failure.reason());
        assertNull(failure.getCause());
    }

    @Test
    void durableBuyerCreationStoresTheFullExactProfile() throws SQLException {
        var profile = new BuyerCreation(" Ada ", "Lovelace", "ada@example.com", "+12025550100",
                new Address("1 Street", "Town", "123", "US"), "US");
        session.insertBuyer(31, "buyers.US.1", profile);
        verify(statement).setLong(1, 31);
        verify(statement).setString(2, " Ada  Lovelace");
        verify(statement).setString(4, "buyers.US.1");
        verify(statement).setString(5, " Ada ");
        verify(statement).setString(7, "ada@example.com");
        verify(statement).setString(9, "1 Street");
        verify(statement).setString(12, "US");
        verify(statement).executeUpdate();
    }

    @Test
    void absentBuyerIsEmptyAndIncompleteLegacyBuyerFailsClosed() throws SQLException {
        assertTrue(session.buyer(31).isEmpty());
        when(rows.next()).thenReturn(true);
        assertEquals(OrderFailure.Reason.ORDER_STORE_UNAVAILABLE,
                assertThrows(OrderFailure.class, () -> session.buyer(31)).reason());
    }

    @Test
    void buyerLookupRestoresEveryPersistedProfileField() throws SQLException {
        when(rows.next()).thenReturn(true);
        when(rows.getLong("buyer_id")).thenReturn(31L);
        when(rows.getString(anyString())).thenAnswer(invocation -> switch ((String) invocation.getArgument(0)) {
            case "first_name" -> "Ada";
            case "surname" -> "Lovelace";
            case "email" -> "ada@example.com";
            case "phone" -> "+12025550100";
            case "address_line1" -> "1 Street";
            case "address_city" -> "Town";
            case "address_postal_code" -> "123";
            case "address_country_code", "region" -> "US";
            default -> null;
        });
        assertEquals(new Buyer(31, new BuyerCreation("Ada", "Lovelace", "ada@example.com", "+12025550100",
                new Address("1 Street", "Town", "123", "US"), "US")),
                session.buyerByKey("US", "buyers.US.1").orElseThrow());
        verify(statement).setString(1, "US");
        verify(statement).setString(2, "buyers.US.1");
    }

    @Test
    void allocationUsesDurableBuyerRunOrdinalCoordinates() throws SQLException {
        var allocation = new Allocation(31, 41, "run", 0);
        session.insertAllocation(allocation);
        verify(statement).setLong(1, 41);
        verify(statement).setLong(2, 31);
        verify(statement).setString(3, "run");
        verify(statement).setLong(4, 0);
        when(rows.next()).thenReturn(true);
        when(rows.getLong("order_id")).thenReturn(41L);
        assertEquals(allocation, session.allocation(31, "run", 0).orElseThrow());
        assertTrue(session.allocatedTo(41, 31));
        assertTrue(session.quarantined(41));
        verify(connection).prepareStatement(contains("resolved_at IS NULL"));
    }

    @Test
    void acceptanceWritesSnapshotsIncludingUnitCostAndSagaWithoutOutbox() throws SQLException {
        PreparedStatement order = mock();
        PreparedStatement item = mock();
        PreparedStatement saga = mock();
        when(connection.prepareStatement(startsWith("INSERT INTO ordering.orders("))).thenReturn(order);
        when(connection.prepareStatement(startsWith("INSERT INTO ordering.order_items("))).thenReturn(item);
        when(connection.prepareStatement(startsWith("INSERT INTO ordering.order_sagas("))).thenReturn(saga);
        when(order.executeUpdate()).thenReturn(1);
        when(item.executeBatch()).thenReturn(new int[]{1});
        when(saga.executeUpdate()).thenReturn(1);
        session.insertOrder(new Order(31, 41, "fp", "USD", new BigDecimal("39.90"), "PENDING_STOCK", null),
                List.of(new ItemSnapshot(0, 51, 61, 2, "Product", new BigDecimal("19.95"),
                        new BigDecimal("12.50"), "USD")), 71, 81);
        verify(order).setBigDecimal(5, new BigDecimal("39.90"));
        verify(item).setBigDecimal(7, new BigDecimal("19.95"));
        verify(item).setBigDecimal(8, new BigDecimal("12.50"));
        verify(item).setString(9, "USD");
        verify(item).addBatch();
        verify(item).executeBatch();
        verify(saga).setLong(1, 71);
        verify(saga).setLong(3, 81);
        verify(connection, never()).prepareStatement(contains("outbox"));
        verify(connection, never()).commit();
    }

    @Test
    void incompleteItemBatchFailsBeforeSagaCreation() throws SQLException {
        PreparedStatement order = mock();
        PreparedStatement item = mock();
        when(connection.prepareStatement(startsWith("INSERT INTO ordering.orders("))).thenReturn(order);
        when(connection.prepareStatement(startsWith("INSERT INTO ordering.order_items("))).thenReturn(item);
        when(order.executeUpdate()).thenReturn(1);
        for (int[] counts : List.of(new int[]{}, new int[]{0}, new int[]{java.sql.Statement.EXECUTE_FAILED})) {
            when(item.executeBatch()).thenReturn(counts);
            assertEquals(OrderFailure.Reason.ORDER_STORE_UNAVAILABLE, assertThrows(OrderFailure.class,
                    () -> session.insertOrder(new Order(31, 41, "fp", "USD", new BigDecimal("39.90"),
                                    "PENDING_STOCK", null),
                            List.of(new ItemSnapshot(0, 51, 61, 2, "Product", new BigDecimal("19.95"),
                                    new BigDecimal("12.50"), "USD")), 71, 81)).reason());
        }
        verify(connection, never()).prepareStatement(startsWith("INSERT INTO ordering.order_sagas("));
    }

    @Test
    void unexpectedWriteCountAndSqlExceptionsBecomeSafeUnavailableFailures() throws SQLException {
        when(statement.executeUpdate()).thenReturn(0);
        assertEquals(OrderFailure.Reason.ORDER_STORE_UNAVAILABLE, assertThrows(OrderFailure.class,
                () -> session.insertAllocation(new Allocation(31, 41, "run", 0))).reason());
        when(statement.executeQuery()).thenThrow(new SQLException("password=secret"));
        OrderFailure failure = assertThrows(OrderFailure.class, () -> session.quarantined(41));
        assertEquals("ORDER_STORE_UNAVAILABLE", failure.getMessage());
        assertNull(failure.getCause());
    }

    @Test
    void catalogLookupIncludesImmutableSellerCountryForNewOrderPolicy() throws SQLException {
        when(rows.next()).thenReturn(true);
        when(rows.getString("name")).thenReturn("Product");
        when(rows.getBigDecimal("price")).thenReturn(new BigDecimal("19.95"));
        when(rows.getBigDecimal("unit_cost")).thenReturn(new BigDecimal("12.50"));
        when(rows.getString("currency")).thenReturn("EUR");
        when(rows.getString("region")).thenReturn("EU");
        when(rows.getString("country_code")).thenReturn("DE");
        assertEquals(new Product(51, 61, "Product", new BigDecimal("19.95"), new BigDecimal("12.50"), "EUR", "EU", "DE"),
                session.product(51, 61).orElseThrow());
        verify(statement).setLong(1, 51);
        verify(statement).setLong(2, 61);
        verify(connection).prepareStatement("SELECT p.name, p.price, p.unit_cost, p.currency, s.region, s.country_code "
                + "FROM catalog.products p JOIN catalog.sellers s ON s.seller_id = p.seller_id "
                + "WHERE p.seller_id = ? AND p.product_id = ?");
    }
}
