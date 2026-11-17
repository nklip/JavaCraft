package dev.nklip.javacraft.shardshop.testing.cluster;

import dev.nklip.javacraft.shardshop.testing.support.CatalogDatabase;
import dev.nklip.javacraft.shardshop.testing.support.ShardShopFiles;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/** Executes the accounting query shared by cluster, restore, repair and retained-upgrade checks. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProfitInvariantIT {
    private CatalogDatabase postgres;
    private String database;

    @BeforeAll
    void createCatalog() throws SQLException {
        postgres = CatalogDatabase.instance();
        database = postgres.createDatabase();
        postgres.migrate(database, ShardShopFiles.resolve("database/shard/catalog"),
                Map.of("shardRegion", "US", "shardIndex", "0", "shardCount", "1"));
    }

    @AfterAll
    void dropCatalog() throws SQLException {
        if (database != null) postgres.dropDatabase(database);
    }

    @Test
    void acceptsEmptyCatalogAndExactSignedZeroAndCurrencyTotals() throws SQLException {
        try (Connection connection = postgres.superuser(database)) {
            connection.setAutoCommit(false);
            try {
                assertEquals(0, violations(connection));
                seed(connection);
                assertEquals(0, violations(connection),
                        "Signed product totals and zero seller balances without credits are valid");
            } finally {
                connection.rollback();
            }
        }
    }

    @TestFactory
    Stream<DynamicTest> rejectsIncorrectProductTotalsWithoutChangingSellersOrCredits() {
        return Stream.of(
                dynamicTest("changed amount", () -> rejects(
                        "UPDATE catalog.product_profits SET amount = amount + 1 WHERE product_id = 11")),
                dynamicTest("offsetting errors in products of one seller", () -> rejects(
                        "UPDATE catalog.product_profits SET amount = CASE product_id WHEN 11 THEN 0 ELSE -3.50 END "
                                + "WHERE product_id IN (11,12)")),
                dynamicTest("missing signed total", () -> rejects(
                        "DELETE FROM catalog.product_profits WHERE product_id = 11")),
                dynamicTest("missing zero total", () -> rejects(
                        "DELETE FROM catalog.product_profits WHERE product_id = 12")),
                dynamicTest("extra zero total without credits", () -> rejects(
                        "INSERT INTO catalog.product_profits VALUES (14, 'USD', 0)")),
                dynamicTest("extra currency total", () -> rejects(
                        "INSERT INTO catalog.product_profits VALUES (11, 'EUR', 0)")),
                dynamicTest("total in the wrong currency", () -> rejects(
                        "UPDATE catalog.product_profits SET currency = 'EUR' WHERE product_id = 11")));
    }

    @Test
    void stillRejectsIncorrectSellerTotals() throws SQLException {
        rejects("UPDATE catalog.seller_profits SET amount = amount + 1 WHERE seller_id = 1 AND currency = 'USD'");
    }

    private void rejects(String corruption) throws SQLException {
        try (Connection connection = postgres.superuser(database)) {
            connection.setAutoCommit(false);
            try {
                seed(connection);
                assertEquals(0, violations(connection), "The starting accounting state must be consistent");
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate(corruption);
                }
                assertTrue(violations(connection) > 0, "Accounting verification must reject: " + corruption);
            } finally {
                connection.rollback();
            }
        }
    }

    private static long violations(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(Saga.profitViolations())) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private static void seed(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO catalog.sellers (seller_id, name) VALUES (1, 'Credited seller'), (2, 'Uncredited seller');
                    INSERT INTO catalog.products (product_id, seller_id, name, price, currency, initial_stock, stock)
                    VALUES (11, 1, 'Loss', 1.00, 'USD', 1, 1), (12, 1, 'Zero', 1.00, 'USD', 1, 1),
                           (13, 1, 'Euro', 1.00, 'EUR', 1, 1), (14, 2, 'Uncredited', 1.00, 'USD', 1, 1);
                    INSERT INTO catalog.profit_credits (order_id, product_id, seller_id, currency, amount, product_total_applied)
                    VALUES (101, 11, 1, 'USD', 10.00, true), (102, 11, 1, 'USD', -13.50, true),
                           (103, 12, 1, 'USD', 0.00, true), (104, 13, 1, 'EUR', 6.50, true);
                    INSERT INTO catalog.seller_profits (seller_id, currency, amount)
                    VALUES (1, 'USD', -3.50), (1, 'EUR', 6.50), (2, 'USD', 0.00), (2, 'EUR', 0.00);
                    INSERT INTO catalog.product_profits (product_id, currency, amount)
                    VALUES (11, 'USD', -3.50), (12, 'USD', 0.00), (13, 'EUR', 6.50);
                    """);
        }
    }
}
