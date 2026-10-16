package dev.nklip.javacraft.shardshop.order.persistence;

import dev.nklip.javacraft.shardshop.common.jdbc.DatabasePhases;
import dev.nklip.javacraft.shardshop.order.ordering.OrderFailure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
        when(item.executeUpdate()).thenReturn(1);
        when(saga.executeUpdate()).thenReturn(1);
        session.insertOrder(new Order(31, 41, "fp", "USD", new BigDecimal("39.90"), "PENDING_STOCK", null),
                List.of(new ItemSnapshot(0, 51, 61, 2, "Product", new BigDecimal("19.95"),
                        new BigDecimal("12.50"), "USD")), 71, 81);
        verify(order).setBigDecimal(5, new BigDecimal("39.90"));
        verify(item).setBigDecimal(7, new BigDecimal("19.95"));
        verify(item).setBigDecimal(8, new BigDecimal("12.50"));
        verify(item).setString(9, "USD");
        verify(saga).setLong(1, 71);
        verify(saga).setLong(3, 81);
        verify(connection, never()).prepareStatement(contains("outbox"));
        verify(connection, never()).commit();
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
    void catalogLookupReturnsImmutableNamePriceCostAndCurrencyOnly() throws SQLException {
        when(rows.next()).thenReturn(true);
        when(rows.getString("name")).thenReturn("Product");
        when(rows.getBigDecimal("price")).thenReturn(new BigDecimal("19.95"));
        when(rows.getBigDecimal("unit_cost")).thenReturn(new BigDecimal("12.50"));
        when(rows.getString("currency")).thenReturn("EUR");
        assertEquals(new Product(51, 61, "Product", new BigDecimal("19.95"), new BigDecimal("12.50"), "EUR"),
                session.product(51, 61).orElseThrow());
        verify(statement).setLong(1, 51);
        verify(statement).setLong(2, 61);
        verify(connection).prepareStatement("SELECT name, price, unit_cost, currency "
                + "FROM catalog.products WHERE seller_id = ? AND product_id = ?");
    }
}
