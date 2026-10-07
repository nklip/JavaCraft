package dev.nklip.javacraft.shardshop.product.persistence;

import dev.nklip.javacraft.shardshop.product.catalog.CatalogFailure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static dev.nklip.javacraft.shardshop.product.catalog.CatalogModel.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JdbcCatalogSessionTest {
    private Connection connection;
    private PreparedStatement statement;
    private PreparedStatement timeout;
    private ResultSet rows;
    private ResultSet timeoutRows;
    private JdbcCatalogSession session;

    @BeforeEach
    void prepare() throws SQLException {
        connection = mock();
        statement = mock();
        timeout = mock();
        rows = mock();
        timeoutRows = mock();
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(connection.prepareStatement(contains("set_config"))).thenReturn(timeout);
        when(statement.executeQuery()).thenReturn(rows);
        when(timeout.executeQuery()).thenReturn(timeoutRows);
        when(timeoutRows.next()).thenReturn(true);
        session = new JdbcCatalogSession(connection, new JdbcCatalogStore.Deadline(() -> 0, 3_000_000_000L),
                CatalogFailure.Reason.UNAVAILABLE);
    }

    @Test
    void missingLookupsStayEmpty() {
        assertTrue(session.seller(42).isEmpty());
        assertTrue(session.sellerByKey("EU", "company").isEmpty());
        assertTrue(session.product(42, 43).isEmpty());
        assertTrue(session.productByKey(42, "product").isEmpty());
    }

    @Test
    void sellerLookupsReadCurrentPerCurrencyProfit() throws SQLException {
        PreparedStatement profits = mock();
        ResultSet amounts = mock();
        when(connection.prepareStatement(contains("FROM catalog.seller_profits"))).thenReturn(profits);
        when(profits.executeQuery()).thenReturn(amounts);
        when(rows.next()).thenReturn(true);
        when(rows.getLong("seller_id")).thenReturn(42L);
        when(rows.getString("company_name")).thenReturn("Company");
        when(rows.getString("region")).thenReturn("EU");
        when(amounts.next()).thenReturn(true, true, true, false);
        when(amounts.getString("currency")).thenReturn("EUR", "JPY", "USD");
        when(amounts.getBigDecimal("amount")).thenReturn(new BigDecimal("0.00"),
                new BigDecimal("-12.50"), new BigDecimal("20.00"));

        Seller seller = session.sellerByKey("EU", "seller-key").orElseThrow();

        assertEquals(new Seller(42L, "Company", "EU",
                Map.of("EUR", "0.00", "JPY", "-12.50", "USD", "20.00")), seller);
        verify(statement).setString(1, "EU");
        verify(statement).setString(2, "seller-key");
        verify(profits).setLong(1, 42L);
        when(amounts.next()).thenReturn(false);
        assertEquals(Map.of(), session.seller(42L).orElseThrow().profitsEarned());
        verify(statement).setLong(1, 42L);
    }

    @Test
    void productLookupsReturnCurrentStockAndOriginalCreationFields() throws SQLException {
        when(rows.next()).thenReturn(true);
        when(rows.getLong("seller_id")).thenReturn(42L);
        when(rows.getLong("product_id")).thenReturn(43L);
        when(rows.getString("name")).thenReturn("Name");
        when(rows.getString("description")).thenReturn("Description");
        when(rows.getBigDecimal("price")).thenReturn(new BigDecimal("19.95"));
        when(rows.getBigDecimal("unit_cost")).thenReturn(new BigDecimal("12.50"));
        when(rows.getString("currency")).thenReturn("EUR");
        when(rows.getInt("initial_stock")).thenReturn(100);
        when(rows.getInt("stock")).thenReturn(72);
        Product expected = new Product(42L, 43L, creation(), 72);

        assertEquals(expected, session.productByKey(42L, "product-key").orElseThrow());
        assertEquals(expected, session.product(42L, 43L).orElseThrow());
        verify(statement, times(2)).setLong(1, 42L);
        verify(statement).setString(2, "product-key");
        verify(statement).setLong(2, 43L);
    }

    @Test
    void writesUseBoundValuesAndZeroProfitDefaults() throws SQLException {
        PreparedStatement profits = mock();
        when(connection.prepareStatement(contains("INSERT INTO catalog.seller_profits"))).thenReturn(profits);
        when(statement.executeUpdate()).thenReturn(1);
        when(profits.executeUpdate()).thenReturn(2);

        session.insertSeller(42L, "seller-key", new SellerCreation("Company", "EU"));

        verify(statement).setLong(1, 42L);
        verify(statement).setString(2, "Company");
        verify(statement).setString(3, "EU");
        verify(statement).setString(4, "seller-key");
        verify(profits).setLong(1, 42L);
        verify(profits).setLong(2, 42L);
        verify(profits).executeUpdate();

        session.insertProduct(43L, 42L, "product-key", creation());

        verify(statement).setLong(1, 43L);
        verify(statement).setLong(2, 42L);
        verify(statement).setString(3, "Name");
        verify(statement).setString(4, "Description");
        verify(statement).setBigDecimal(5, new BigDecimal("19.95"));
        verify(statement).setBigDecimal(6, new BigDecimal("12.50"));
        verify(statement).setString(7, "EUR");
        verify(statement).setInt(8, 100);
        verify(statement).setInt(9, 100);
        verify(statement).setString(10, "product-key");
        verify(statement, times(2)).executeUpdate();
    }

    @Test
    void creationRejectsIncompleteWrites() throws SQLException {
        CatalogFailure sellerFailure = assertThrows(CatalogFailure.class,
                () -> session.insertSeller(42, "key", new SellerCreation("Company", "EU")));
        assertEquals(CatalogFailure.Reason.UNAVAILABLE, sellerFailure.reason());
        CatalogFailure productFailure = assertThrows(CatalogFailure.class,
                () -> session.insertProduct(43, 42, "key", creation()));
        assertEquals(CatalogFailure.Reason.UNAVAILABLE, productFailure.reason());
        when(statement.executeUpdate()).thenReturn(1);
        CatalogFailure profitsFailure = assertThrows(CatalogFailure.class,
                () -> session.insertSeller(42, "key", new SellerCreation("Company", "EU")));
        assertEquals(CatalogFailure.Reason.UNAVAILABLE, profitsFailure.reason());
    }

    @Test
    void advisoryLockRequiresACompletedQuery() throws SQLException {
        when(rows.next()).thenReturn(true);
        session.lock(1234L);
        verify(statement).setLong(1, 1234L);
        verify(rows).next();
        when(rows.next()).thenReturn(false);
        CatalogFailure failure = assertThrows(CatalogFailure.class, () -> session.lock(1234L));
        assertEquals(CatalogFailure.Reason.UNAVAILABLE, failure.reason());
    }

    @Test
    void allDatabaseFailuresAreSanitized() throws SQLException {
        when(connection.prepareStatement(anyString())).thenThrow(new SQLException("password=secret"));
        List<Consumer<JdbcCatalogSession>> operations = List.of(value -> value.lock(1),
                value -> value.seller(1), value -> value.sellerByKey("EU", "key"),
                value -> value.product(1, 2), value -> value.productByKey(1, "key"),
                value -> value.insertSeller(1, "key", new SellerCreation("Company", "EU")),
                value -> value.insertProduct(2, 1, "key", creation()));
        for (Consumer<JdbcCatalogSession> operation : operations) {
            CatalogFailure failure = assertThrows(CatalogFailure.class, () -> operation.accept(session));
            assertEquals(CatalogFailure.Reason.UNAVAILABLE, failure.reason());
            assertEquals("UNAVAILABLE", failure.getMessage());
            assertNull(failure.getCause());
        }
    }

    @Test
    void everyQueryRefreshesTimeoutsFromTheRemainingBudget() throws SQLException {
        AtomicLong time = new AtomicLong();
        JdbcCatalogSession shrinking = new JdbcCatalogSession(connection,
                new JdbcCatalogStore.Deadline(() -> time.getAndAdd(100_000_000L), 3_000_000_000L),
                CatalogFailure.Reason.REPLICA_UNAVAILABLE);
        assertTrue(shrinking.seller(1).isEmpty());
        assertTrue(shrinking.seller(2).isEmpty());
        verify(connection).setNetworkTimeout(any(), eq(3000));
        verify(connection).setNetworkTimeout(any(), eq(2900));
        verify(timeout).setString(1, "3000");
        verify(timeout).setString(2, "2900");
        when(timeoutRows.next()).thenReturn(false);
        CatalogFailure failure = assertThrows(CatalogFailure.class, () -> shrinking.seller(3));
        assertEquals(CatalogFailure.Reason.REPLICA_UNAVAILABLE, failure.reason());
    }

    @Test
    void rowDecodingFailuresCloseResultsAndReturnUnavailable() throws SQLException {
        when(rows.next()).thenThrow(new SQLException("connection lost while reading"));
        CatalogFailure sellerFailure = assertThrows(CatalogFailure.class, () -> session.seller(1));
        CatalogFailure productFailure = assertThrows(CatalogFailure.class, () -> session.product(1, 2));
        assertEquals(CatalogFailure.Reason.UNAVAILABLE, sellerFailure.reason());
        assertEquals(CatalogFailure.Reason.UNAVAILABLE, productFailure.reason());
        verify(rows, times(2)).close();
    }

    private static ProductCreation creation() {
        return new ProductCreation("Name", "Description", "19.95", "12.50", "EUR", 100);
    }
}
