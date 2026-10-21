package dev.nklip.javacraft.shardshop.order.persistence;

import dev.nklip.javacraft.shardshop.common.jdbc.DatabasePhases;
import dev.nklip.javacraft.shardshop.order.ordering.OrderFailure;
import dev.nklip.javacraft.shardshop.order.ordering.OrderStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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

    List<ListedOrder> orders(String region, Long productId, String productName,
                             SortDirection sort, int limit, OrderCursor after) {
        // Bound orders before joining their items; filtering an item join could duplicate or truncate orders.
        String direction = sort.name();
        String sql = "WITH page AS (SELECT o.order_id, o.buyer_id, b.name AS buyer_name, o.created_at, o.status, "
                + "s.cancellation_reason, o.total_amount, o.currency FROM ordering.orders o "
                + "JOIN ordering.buyers b ON b.buyer_id = o.buyer_id "
                + "LEFT JOIN ordering.order_sagas s ON s.order_id = o.order_id WHERE b.region = ?"
                + (productId == null ? "" : " AND EXISTS (SELECT 1 FROM ordering.order_items matched "
                + "WHERE matched.order_id = o.order_id AND matched.product_id = ?)")
                + (productName == null ? "" : " AND EXISTS (SELECT 1 FROM ordering.order_items matched "
                + "WHERE matched.order_id = o.order_id AND strpos(lower(matched.product_name), lower(?)) > 0)")
                + (after == null ? "" : " AND (o.created_at, o.order_id) "
                + (sort == SortDirection.ASC ? ">" : "<") + " (?, ?)")
                + " ORDER BY o.created_at " + direction + ", o.order_id " + direction + " LIMIT ?) "
                + "SELECT p.*, i.product_id, i.product_name, i.quantity FROM page p "
                + "LEFT JOIN ordering.order_items i ON i.order_id = p.order_id "
                + "ORDER BY p.created_at " + direction + ", p.order_id " + direction + ", i.item_position ASC";
        try (PreparedStatement statement = prepare(sql)) {
            int parameter = 1;
            statement.setString(parameter++, region);
            if (productId != null) {
                statement.setLong(parameter++, productId);
            }
            if (productName != null) {
                statement.setString(parameter++, productName);
            }
            if (after != null) {
                statement.setObject(parameter++, after.createdAt().atOffset(ZoneOffset.UTC));
                statement.setLong(parameter++, after.orderId());
            }
            statement.setInt(parameter, limit);
            List<ListedOrder> result = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                ListedOrder current = null;
                List<ListedItem> items = new ArrayList<>();
                while (rows.next()) {
                    long orderId = rows.getLong("order_id");
                    if (current == null || current.orderId() != orderId) {
                        if (current != null) {
                            result.add(withItems(current, items));
                        }
                        current = new ListedOrder(orderId, rows.getLong("buyer_id"), rows.getString("buyer_name"),
                                rows.getObject("created_at", OffsetDateTime.class).toInstant(),
                                rows.getString("status"), rows.getString("cancellation_reason"),
                                rows.getBigDecimal("total_amount"), rows.getString("currency"), List.of());
                        items.clear();
                    }
                    long product = rows.getLong("product_id");
                    if (!rows.wasNull()) {
                        items.add(new ListedItem(product, rows.getString("product_name"), rows.getInt("quantity")));
                    }
                }
                if (current != null) {
                    result.add(withItems(current, items));
                }
            }
            return List.copyOf(result);
        } catch (SQLException failure) {
            throw new OrderFailure(unavailable);
        }
    }

    private ListedOrder withItems(ListedOrder order, List<ListedItem> items) {
        return new ListedOrder(order.orderId(), order.buyerId(), order.buyerName(), order.createdAt(), order.status(),
                order.cancellationReason(), order.total(), order.currency(), items);
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
