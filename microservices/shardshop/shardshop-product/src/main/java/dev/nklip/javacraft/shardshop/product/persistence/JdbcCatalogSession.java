package dev.nklip.javacraft.shardshop.product.persistence;

import dev.nklip.javacraft.shardshop.product.catalog.CatalogFailure;
import dev.nklip.javacraft.shardshop.product.catalog.CatalogStore;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Optional;

import static dev.nklip.javacraft.shardshop.product.catalog.CatalogModel.*;

/** JDBC operations belonging to one connection, transaction and deadline. */
final class JdbcCatalogSession implements CatalogStore.Session {
    private static final String SELLER_COLUMNS = "seller_id, company_name, region";
    private static final String PRODUCT_COLUMNS = "seller_id, product_id, name, description, price, unit_cost, "
            + "currency, initial_stock, stock";

    private final Connection connection;
    private final JdbcCatalogStore.Deadline deadline;
    private final CatalogFailure.Reason unavailable;

    JdbcCatalogSession(Connection connection, JdbcCatalogStore.Deadline deadline,
                       CatalogFailure.Reason unavailable) {
        this.connection = connection;
        this.deadline = deadline;
        this.unavailable = unavailable;
    }

    void refreshTimeouts() throws SQLException {
        int milliseconds = deadline.remainingMillis();
        connection.setNetworkTimeout(Runnable::run, milliseconds);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('statement_timeout', ?, true), set_config('lock_timeout', ?, true)")) {
            statement.setString(1, Integer.toString(milliseconds));
            statement.setString(2, Integer.toString(milliseconds));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Database timeout configuration failed");
                }
            }
        }
    }

    private PreparedStatement prepare(String sql) throws SQLException {
        refreshTimeouts();
        return connection.prepareStatement(sql);
    }

    @Override
    public void lock(long key) {
        try (PreparedStatement statement = prepare("SELECT pg_advisory_xact_lock(?)")) {
            statement.setLong(1, key);
            try (ResultSet result = statement.executeQuery()) {
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
        try (ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                return Optional.empty();
            }
            long sellerId = result.getLong("seller_id");
            String companyName = result.getString("company_name");
            String region = result.getString("region");
            return Optional.of(new Seller(sellerId, companyName, region, profits(sellerId)));
        }
    }

    private LinkedHashMap<String, String> profits(long sellerId) throws SQLException {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        try (PreparedStatement statement = prepare(
                "SELECT currency, amount FROM catalog.seller_profits WHERE seller_id = ? ORDER BY currency")) {
            statement.setLong(1, sellerId);
            try (ResultSet rows = statement.executeQuery()) {
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
                + " FROM catalog.products WHERE seller_id = ? AND creation_key = ?")) {
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
                + " FROM catalog.products WHERE seller_id = ? AND product_id = ?")) {
            statement.setLong(1, sellerId);
            statement.setLong(2, productId);
            return productResult(statement);
        } catch (SQLException failure) {
            throw new CatalogFailure(unavailable);
        }
    }

    private Optional<Product> productResult(PreparedStatement statement) throws SQLException {
        try (ResultSet result = statement.executeQuery()) {
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
                    creation, result.getInt("stock")));
        }
    }

    @Override
    public void insertSeller(long id, String key, SellerCreation creation) {
        try (PreparedStatement statement = prepare(
                "INSERT INTO catalog.sellers(seller_id, name, region, creation_key) VALUES (?, ?, ?, ?)")) {
            statement.setLong(1, id);
            statement.setString(2, creation.companyName());
            statement.setString(3, creation.region());
            statement.setString(4, key);
            requireRows(statement.executeUpdate(), 1);
            try (PreparedStatement profits = prepare(
                    "INSERT INTO catalog.seller_profits(seller_id, currency) VALUES (?, 'USD'), (?, 'EUR')")) {
                profits.setLong(1, id);
                profits.setLong(2, id);
                requireRows(profits.executeUpdate(), 2);
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
