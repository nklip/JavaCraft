package dev.nklip.javacraft.shardshop.product.persistence;

import dev.nklip.javacraft.shardshop.common.jdbc.DatabasePhases;
import dev.nklip.javacraft.shardshop.common.jdbc.DatabaseTimings;
import dev.nklip.javacraft.shardshop.common.CurrencyPolicy;
import dev.nklip.javacraft.shardshop.product.catalog.CatalogFailure;
import dev.nklip.javacraft.shardshop.product.catalog.CatalogStore;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static dev.nklip.javacraft.shardshop.product.catalog.CatalogModel.*;

/** JDBC operations belonging to one connection, transaction and deadline. */
final class JdbcCatalogSession implements CatalogStore.Session {
    private static final String SELLER_COLUMNS = "seller_id, company_name, region, country_code";
    private static final String PRODUCT_COLUMNS = "p.seller_id, p.product_id, p.name, p.description, p.price, p.unit_cost, "
            + "p.currency, p.initial_stock, p.stock, s.region, s.country_code";

    private final Connection connection;
    private final DatabasePhases.Deadline deadline;
    private final CatalogFailure.Reason unavailable;
    private final DatabaseTimings timings;

    JdbcCatalogSession(Connection connection, DatabasePhases.Deadline deadline,
                       CatalogFailure.Reason unavailable) {
        this(connection, deadline, unavailable, DatabaseTimings.NONE);
    }

    JdbcCatalogSession(Connection connection, DatabasePhases.Deadline deadline,
                       CatalogFailure.Reason unavailable, DatabaseTimings timings) {
        this.connection = connection;
        this.deadline = deadline;
        this.unavailable = unavailable;
        this.timings = timings;
    }

    private PreparedStatement prepare(String sql) throws SQLException {
        return timings.measure(DatabaseTimings.Phase.STATEMENT_SETUP, () -> {
            DatabasePhases.refreshTimeouts(connection, deadline);
            return connection.prepareStatement(sql);
        });
    }

    private ResultSet query(PreparedStatement statement) throws SQLException {
        return timings.measure(DatabaseTimings.Phase.QUERY, statement::executeQuery);
    }

    @Override
    public void lock(long key) {
        try (PreparedStatement statement = prepare("SELECT pg_advisory_xact_lock(?)")) {
            statement.setLong(1, key);
            try (ResultSet result = query(statement)) {
                if (!result.next()) {
                    throw new SQLException("Database lock failed");
                }
            }
        } catch (SQLException failure) {
            throw new CatalogFailure(unavailable);
        }
    }

    @Override
    public Optional<Seller> sellerByKey(String region, String key) {
        try (PreparedStatement statement = prepare("SELECT " + SELLER_COLUMNS
                + " FROM catalog.sellers WHERE region = ? AND creation_key = ?")) {
            statement.setString(1, region);
            statement.setString(2, key);
            return sellerResult(statement);
        } catch (SQLException failure) {
            throw new CatalogFailure(unavailable);
        }
    }

    @Override
    public Optional<Seller> seller(long id) {
        try (PreparedStatement statement = prepare("SELECT " + SELLER_COLUMNS
                + " FROM catalog.sellers WHERE seller_id = ?")) {
            statement.setLong(1, id);
            return sellerResult(statement);
        } catch (SQLException failure) {
            throw new CatalogFailure(unavailable);
        }
    }

    private Optional<Seller> sellerResult(PreparedStatement statement) throws SQLException {
        try (ResultSet result = query(statement)) {
            if (!result.next()) {
                return Optional.empty();
            }
            long sellerId = result.getLong("seller_id");
            String companyName = result.getString("company_name");
            String region = result.getString("region");
            return Optional.of(new Seller(sellerId, companyName, region, profits(sellerId), result.getString("country_code")));
        }
    }

    private LinkedHashMap<String, String> profits(long sellerId) throws SQLException {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        try (PreparedStatement statement = prepare(
                "SELECT currency, amount FROM catalog.seller_profits WHERE seller_id = ? ORDER BY currency")) {
            statement.setLong(1, sellerId);
            try (ResultSet rows = query(statement)) {
                while (rows.next()) {
                    result.put(rows.getString("currency"), rows.getBigDecimal("amount").toPlainString());
                }
            }
        }
        return result;
    }

    @Override
    public Optional<Product> productByKey(long sellerId, String key) {
        try (PreparedStatement statement = prepare("SELECT " + PRODUCT_COLUMNS
                + " FROM catalog.products p JOIN catalog.sellers s ON s.seller_id = p.seller_id WHERE p.seller_id = ? AND p.creation_key = ?")) {
            statement.setLong(1, sellerId);
            statement.setString(2, key);
            return productResult(statement);
        } catch (SQLException failure) {
            throw new CatalogFailure(unavailable);
        }
    }

    @Override
    public Optional<Product> product(long sellerId, long productId) {
        try (PreparedStatement statement = prepare("SELECT " + PRODUCT_COLUMNS
                + " FROM catalog.products p JOIN catalog.sellers s ON s.seller_id = p.seller_id WHERE p.seller_id = ? AND p.product_id = ?")) {
            statement.setLong(1, sellerId);
            statement.setLong(2, productId);
            return productResult(statement);
        } catch (SQLException failure) {
            throw new CatalogFailure(unavailable);
        }
    }

    @Override
    public List<ListedProduct> products(String region, int limit, long after) {
        // Bound the page before aggregating immutable credits; stock reservations are not sales.
        String sql = "WITH page AS (SELECT p.seller_id, p.product_id, s.company_name, p.name, s.region, s.country_code, p.unit_cost, "
                + "p.initial_stock, p.stock, p.price, p.currency FROM catalog.products p "
                + "JOIN catalog.sellers s ON s.seller_id = p.seller_id "
                + "WHERE s.region = ? AND p.product_id > ? ORDER BY p.product_id LIMIT ?) "
                + "SELECT page.*, COALESCE(credits.amount, 0.00) AS profit FROM page "
                + "LEFT JOIN LATERAL (SELECT SUM(c.amount) AS amount FROM catalog.profit_credits c "
                + "WHERE c.product_id = page.product_id AND c.currency = page.currency) credits ON true "
                + "ORDER BY page.product_id";
        try (PreparedStatement statement = prepare(sql)) {
            statement.setString(1, region);
            statement.setLong(2, after);
            statement.setInt(3, limit);
            List<ListedProduct> products = new ArrayList<>();
            try (ResultSet rows = query(statement)) {
                while (rows.next()) {
                    String currency = rows.getString("currency");
                    products.add(new ListedProduct(rows.getLong("seller_id"), rows.getLong("product_id"),
                            rows.getString("company_name"), rows.getString("name"), rows.getString("region"),
                            rows.getInt("initial_stock"), rows.getInt("stock"),
                            rows.getBigDecimal("price").toPlainString(), currency,
                            Map.of(currency, rows.getBigDecimal("profit").setScale(2).toPlainString()),
                            rows.getString("country_code"), rows.getBigDecimal("unit_cost").toPlainString()));
                }
            }
            return List.copyOf(products);
        } catch (SQLException failure) {
            throw new CatalogFailure(unavailable);
        }
    }

    private Optional<Product> productResult(PreparedStatement statement) throws SQLException {
        try (ResultSet result = query(statement)) {
            if (!result.next()) {
                return Optional.empty();
            }
            ProductCreation creation = new ProductCreation(
                    result.getString("name"),
                    result.getString("description"),
                    result.getBigDecimal("price").toPlainString(),
                    result.getBigDecimal("unit_cost").toPlainString(),
                    result.getString("currency"),
                    result.getInt("initial_stock")
            );
            return Optional.of(new Product(result.getLong("seller_id"), result.getLong("product_id"),
                    creation, result.getInt("stock"), result.getString("region"), result.getString("country_code")));
        }
    }

    @Override
    public void insertSeller(long id, String key, SellerCreation creation) {
        try (PreparedStatement statement = prepare(
                "INSERT INTO catalog.sellers(seller_id, name, region, creation_key, country_code) VALUES (?, ?, ?, ?, ?)")) {
            statement.setLong(1, id);
            statement.setString(2, creation.companyName());
            statement.setString(3, creation.region());
            statement.setString(4, key);
            statement.setString(5, creation.countryCode());
            requireRows(statement.executeUpdate(), 1);
            try (PreparedStatement profits = prepare(
                    "INSERT INTO catalog.seller_profits(seller_id, currency) VALUES (?, ?)")) {
                for (String currency : CurrencyPolicy.currencies(creation.region(), creation.countryCode())) {
                    profits.setLong(1, id);
                    profits.setString(2, currency);
                    requireRows(profits.executeUpdate(), 1);
                }
            }
        } catch (SQLException failure) {
            throw new CatalogFailure(unavailable);
        }
    }

    @Override
    public void insertProduct(long id, long sellerId, String key, ProductCreation creation) {
        try (PreparedStatement statement = prepare("INSERT INTO catalog.products(product_id, seller_id, name, "
                + "description, price, unit_cost, currency, initial_stock, stock, creation_key) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setLong(1, id);
            statement.setLong(2, sellerId);
            statement.setString(3, creation.name());
            statement.setString(4, creation.description());
            statement.setBigDecimal(5, new BigDecimal(creation.price()));
            statement.setBigDecimal(6, new BigDecimal(creation.unitCost()));
            statement.setString(7, creation.currency());
            statement.setInt(8, creation.initialStock());
            statement.setInt(9, creation.initialStock());
            statement.setString(10, key);
            requireRows(statement.executeUpdate(), 1);
        } catch (SQLException failure) {
            throw new CatalogFailure(unavailable);
        }
    }

    private static void requireRows(int actual, int expected) throws SQLException {
        if (actual != expected) {
            throw new SQLException("Unexpected catalog write count");
        }
    }
}
