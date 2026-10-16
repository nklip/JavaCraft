package dev.nklip.javacraft.shardshop.order.persistence;

import dev.nklip.javacraft.shardshop.common.jdbc.DatabasePhases;
import dev.nklip.javacraft.shardshop.order.ordering.OrderFailure;
import dev.nklip.javacraft.shardshop.order.ordering.OrderStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static dev.nklip.javacraft.shardshop.order.ordering.OrderModel.*;

/** All operations use the caller's connection, transaction and absolute deadline. */
final class JdbcOrderSession implements OrderStore.Session {
    private static final String BUYER_COLUMNS = "buyer_id, first_name, surname, email, phone, address_line1, "
            + "address_city, address_postal_code, address_country_code, region";
    private final Connection connection;
    private final DatabasePhases.Deadline deadline;
    private final OrderFailure.Reason unavailable;

    JdbcOrderSession(Connection connection, DatabasePhases.Deadline deadline, OrderFailure.Reason unavailable) {
        this.connection = connection;
        this.deadline = deadline;
        this.unavailable = unavailable;
    }

    private PreparedStatement prepare(String sql) throws SQLException {
        DatabasePhases.refreshTimeouts(connection, deadline);
        return connection.prepareStatement(sql);
    }

    @Override
    public void lock(long key) {
        try (PreparedStatement statement = prepare("SELECT pg_advisory_xact_lock(?)")) {
            statement.setLong(1, key);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("Database lock failed");
                }
            }
        } catch (SQLException failure) {
            throw new OrderFailure(unavailable);
        }
    }

    @Override
    public Optional<Buyer> buyerByKey(String region, String key) {
        try (PreparedStatement statement = prepare("SELECT " + BUYER_COLUMNS
                + " FROM ordering.buyers WHERE region = ? AND creation_key = ?")) {
            statement.setString(1, region);
            statement.setString(2, key);
            return buyerResult(statement);
        } catch (SQLException failure) {
            throw new OrderFailure(unavailable);
        }
    }

    @Override
    public Optional<Buyer> buyer(long buyerId) {
        try (PreparedStatement statement = prepare("SELECT " + BUYER_COLUMNS
                + " FROM ordering.buyers WHERE buyer_id = ?")) {
            statement.setLong(1, buyerId);
            return buyerResult(statement);
        } catch (SQLException failure) {
            throw new OrderFailure(unavailable);
        }
    }

    private Optional<Buyer> buyerResult(PreparedStatement statement) throws SQLException {
        try (ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) {
                return Optional.empty();
            }
            String firstName = required(rows, "first_name");
            BuyerCreation creation = new BuyerCreation(firstName, required(rows, "surname"),
                    required(rows, "email"), required(rows, "phone"),
                    new Address(required(rows, "address_line1"), required(rows, "address_city"),
                            required(rows, "address_postal_code"), required(rows, "address_country_code")),
                    required(rows, "region"));
            return Optional.of(new Buyer(rows.getLong("buyer_id"), creation));
        }
    }

    private String required(ResultSet rows, String column) throws SQLException {
        String value = rows.getString(column);
        // A pre-API buyer has no truthful person profile. Fail closed until explicitly repaired.
        if (value == null) {
            throw new SQLException("Legacy buyer profile requires reconciliation");
        }
        return value;
    }

    @Override
    public void insertBuyer(long buyerId, String key, BuyerCreation creation) {
        try (PreparedStatement statement = prepare("INSERT INTO ordering.buyers(buyer_id, name, region, "
                + "creation_key, first_name, surname, email, phone, address_line1, address_city, "
                + "address_postal_code, address_country_code) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setLong(1, buyerId);
            statement.setString(2, creation.firstName() + " " + creation.surname());
            statement.setString(3, creation.region());
            statement.setString(4, key);
            statement.setString(5, creation.firstName());
            statement.setString(6, creation.surname());
            statement.setString(7, creation.email());
            statement.setString(8, creation.phone());
            statement.setString(9, creation.address().line1());
            statement.setString(10, creation.address().city());
            statement.setString(11, creation.address().postalCode());
            statement.setString(12, creation.address().countryCode());
            requireRows(statement.executeUpdate());
        } catch (SQLException failure) {
            throw new OrderFailure(unavailable);
        }
    }

    @Override
    public Optional<Allocation> allocation(long buyerId, String runName, long requestOrdinal) {
        try (PreparedStatement statement = prepare("SELECT order_id FROM ordering.order_allocations "
                + "WHERE buyer_id = ? AND run_name = ? AND request_ordinal = ?")) {
            statement.setLong(1, buyerId);
            statement.setString(2, runName);
            statement.setLong(3, requestOrdinal);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(new Allocation(buyerId, rows.getLong("order_id"),
                        runName, requestOrdinal)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new OrderFailure(unavailable);
        }
    }

    @Override
    public boolean allocatedTo(long orderId, long buyerId) {
        try (PreparedStatement statement = prepare("SELECT 1 FROM ordering.order_allocations "
                + "WHERE order_id = ? AND buyer_id = ?")) {
            statement.setLong(1, orderId);
            statement.setLong(2, buyerId);
            return exists(statement);
        } catch (SQLException failure) {
            throw new OrderFailure(unavailable);
        }
    }

    @Override
    public void insertAllocation(Allocation allocation) {
        try (PreparedStatement statement = prepare("INSERT INTO ordering.order_allocations "
                + "(order_id, buyer_id, run_name, request_ordinal) VALUES (?, ?, ?, ?)")) {
            statement.setLong(1, allocation.orderId());
            statement.setLong(2, allocation.buyerId());
            statement.setString(3, allocation.runName());
            statement.setLong(4, allocation.requestOrdinal());
            requireRows(statement.executeUpdate());
        } catch (SQLException failure) {
            throw new OrderFailure(unavailable);
        }
    }

    @Override
    public boolean quarantined(long orderId) {
        try (PreparedStatement statement = prepare("SELECT 1 FROM ordering.orphan_results "
                + "WHERE order_id = ? AND resolved_at IS NULL LIMIT 1")) {
            statement.setLong(1, orderId);
            return exists(statement);
        } catch (SQLException failure) {
            throw new OrderFailure(unavailable);
        }
    }

    private boolean exists(PreparedStatement statement) throws SQLException {
        try (ResultSet rows = statement.executeQuery()) {
            return rows.next();
        }
    }

    @Override
    public Optional<Order> order(long buyerId, long orderId) {
        try (PreparedStatement statement = prepare("SELECT o.request_fingerprint, o.currency, o.total_amount, "
                + "o.status, s.cancellation_reason FROM ordering.orders o "
                + "JOIN ordering.order_sagas s ON s.order_id = o.order_id WHERE o.buyer_id = ? AND o.order_id = ?")) {
            statement.setLong(1, buyerId);
            statement.setLong(2, orderId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(new Order(buyerId, orderId, rows.getString("request_fingerprint"),
                        rows.getString("currency"), rows.getBigDecimal("total_amount"), rows.getString("status"),
                        rows.getString("cancellation_reason"))) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new OrderFailure(unavailable);
        }
    }

    @Override
    public void insertOrder(Order order, List<ItemSnapshot> items, long sagaId, long commandId) {
        try {
            try (PreparedStatement statement = prepare("INSERT INTO ordering.orders(order_id, buyer_id, "
                    + "request_fingerprint, currency, total_amount, status) VALUES (?, ?, ?, ?, ?, 'PENDING_STOCK')")) {
                statement.setLong(1, order.orderId());
                statement.setLong(2, order.buyerId());
                statement.setString(3, order.fingerprint());
                statement.setString(4, order.currency());
                statement.setBigDecimal(5, order.totalAmount());
                requireRows(statement.executeUpdate());
            }
            for (ItemSnapshot item : items) {
                try (PreparedStatement statement = prepare("INSERT INTO ordering.order_items(order_id, "
                        + "item_position, seller_id, product_id, quantity, product_name, unit_price, unit_cost, currency) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    statement.setLong(1, order.orderId());
                    statement.setInt(2, item.position());
                    statement.setLong(3, item.sellerId());
                    statement.setLong(4, item.productId());
                    statement.setInt(5, item.quantity());
                    statement.setString(6, item.productName());
                    statement.setBigDecimal(7, item.unitPrice());
                    statement.setBigDecimal(8, item.unitCost());
                    statement.setString(9, item.currency());
                    requireRows(statement.executeUpdate());
                }
            }
            try (PreparedStatement statement = prepare("INSERT INTO ordering.order_sagas(saga_id, order_id, "
                    + "command_id, status) VALUES (?, ?, ?, 'PENDING_STOCK')")) {
                statement.setLong(1, sagaId);
                statement.setLong(2, order.orderId());
                statement.setLong(3, commandId);
                requireRows(statement.executeUpdate());
            }
        } catch (SQLException failure) {
            throw new OrderFailure(unavailable);
        }
    }

    Optional<Product> product(long sellerId, long productId) {
        try (PreparedStatement statement = prepare("SELECT name, price, unit_cost, currency "
                + "FROM catalog.products WHERE seller_id = ? AND product_id = ?")) {
            statement.setLong(1, sellerId);
            statement.setLong(2, productId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(new Product(sellerId, productId, rows.getString("name"),
                        rows.getBigDecimal("price"), rows.getBigDecimal("unit_cost"), rows.getString("currency")))
                        : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new OrderFailure(unavailable);
        }
    }

    private void requireRows(int count) throws SQLException {
        if (count != 1) {
            throw new SQLException("Unexpected order write count");
        }
    }
}
