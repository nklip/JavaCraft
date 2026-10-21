package dev.nklip.javacraft.shardshop.order.persistence;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nklip.javacraft.shardshop.common.jdbc.DatabasePhases;
import dev.nklip.javacraft.shardshop.order.stock.StockFailure;
import dev.nklip.javacraft.shardshop.order.stock.StockStore.Saga;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;

import static dev.nklip.javacraft.shardshop.order.ordering.OrderModel.*;
import static dev.nklip.javacraft.shardshop.order.stock.StockFailure.Reason.*;

/** One bounded READ COMMITTED transaction. No transaction spans order and catalog shards. */
final class JdbcStockSession {
    private static final String OWNED = "saga_id = ? AND order_id = ? AND stock_lease_version = ? "
            + "AND stock_lease_until > clock_timestamp()";
    private final Connection connection;
    private final DatabasePhases.Deadline deadline;
    private final Clock clock;

    JdbcStockSession(Connection connection, DatabasePhases.Deadline deadline, Clock clock) {
        this.connection = connection;
        this.deadline = deadline;
        this.clock = clock;
    }

    private PreparedStatement prepare(String sql) throws SQLException {
        DatabasePhases.refreshTimeouts(connection, deadline);
        return connection.prepareStatement(sql);
    }

    Optional<Saga> claim(Duration lease) throws SQLException {
        try (PreparedStatement statement = prepare("WITH candidate AS ("
                + "SELECT s.saga_id FROM ordering.order_sagas s "
                + "WHERE (s.status = 'PENDING_STOCK' OR (s.status = 'CANCELLED' AND s.stock_released_at IS NULL)) "
                + "AND s.next_stock_attempt_at <= clock_timestamp() "
                + "AND (s.stock_lease_until IS NULL OR s.stock_lease_until <= clock_timestamp()) "
                + "AND NOT EXISTS (SELECT 1 FROM ordering.orphan_results q "
                + "WHERE q.order_id = s.order_id AND q.resolved_at IS NULL) "
                + "ORDER BY s.next_stock_attempt_at, s.saga_id LIMIT 1 FOR UPDATE OF s SKIP LOCKED), "
                + "claimed AS (UPDATE ordering.order_sagas s SET stock_lease_version = s.stock_lease_version + 1, "
                + "stock_lease_until = clock_timestamp() + (? * INTERVAL '1 millisecond') "
                + "FROM candidate c WHERE s.saga_id = c.saga_id RETURNING s.*) "
                + "SELECT s.saga_id, s.command_id, s.stock_lease_version, s.stock_failures, "
                + "s.cancellation_reason, o.buyer_id, o.order_id, o.request_fingerprint, o.currency, "
                + "o.total_amount, o.status, o.currency_policy_version FROM claimed s JOIN ordering.orders o ON o.order_id = s.order_id")) {
            statement.setLong(1, positiveMillis(lease));
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                Order order = new Order(rows.getLong("buyer_id"), rows.getLong("order_id"),
                        rows.getString("request_fingerprint"), rows.getString("currency"),
                        rows.getBigDecimal("total_amount"), rows.getString("status"),
                        rows.getString("cancellation_reason"));
                return Optional.of(new Saga(rows.getLong("saga_id"), rows.getLong("command_id"), order,
                        items(order.orderId()), rows.getLong("stock_lease_version"), rows.getInt("stock_failures"),
                        rows.getInt("currency_policy_version")));
            }
        }
    }

    private List<ItemSnapshot> items(long orderId) throws SQLException {
        try (PreparedStatement statement = prepare("SELECT item_position, seller_id, product_id, quantity, "
                + "product_name, unit_price, unit_cost, currency FROM ordering.order_items "
                + "WHERE order_id = ? ORDER BY item_position")) {
            statement.setLong(1, orderId);
            try (ResultSet rows = statement.executeQuery()) {
                List<ItemSnapshot> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(new ItemSnapshot(rows.getInt("item_position"), rows.getLong("seller_id"),
                            rows.getLong("product_id"), rows.getInt("quantity"), rows.getString("product_name"),
                            rows.getBigDecimal("unit_price"), rows.getBigDecimal("unit_cost"),
                            rows.getString("currency")));
                }
                return List.copyOf(result);
            }
        }
    }

    boolean renew(Saga saga, Duration lease) throws SQLException {
        try (PreparedStatement statement = prepare("UPDATE ordering.order_sagas SET "
                + "stock_lease_until = clock_timestamp() + (? * INTERVAL '1 millisecond') WHERE " + OWNED
                + " AND (status = 'PENDING_STOCK' OR (status = 'CANCELLED' AND stock_released_at IS NULL))")) {
            statement.setLong(1, positiveMillis(lease));
            ownership(statement, 2, saga);
            return statement.executeUpdate() == 1;
        }
    }

    void retry(Saga saga, Duration delay) throws SQLException {
        try (PreparedStatement statement = prepare("UPDATE ordering.order_sagas SET stock_lease_until = NULL, "
                + "stock_failures = LEAST(stock_failures::bigint + 1, 2147483647)::integer, "
                + "next_stock_attempt_at = clock_timestamp() + (? * INTERVAL '1 millisecond') WHERE " + OWNED
                + " AND (status = 'PENDING_STOCK' OR (status = 'CANCELLED' AND stock_released_at IS NULL))")) {
            statement.setLong(1, nonnegativeMillis(delay));
            ownership(statement, 2, saga);
            statement.executeUpdate();
        }
    }

    boolean cancel(Saga saga) throws SQLException {
        try (PreparedStatement statement = prepare("UPDATE ordering.order_sagas SET status = 'CANCELLED', "
                + "cancellation_reason = 'OUT_OF_STOCK', updated_at = clock_timestamp() WHERE " + OWNED
                + " AND status = 'PENDING_STOCK'")) {
            ownership(statement, 1, saga);
            if (statement.executeUpdate() != 1) {
                return false;
            }
        }
        orderStatus(saga, "CANCELLED");
        return true;
    }

    boolean released(Saga saga) throws SQLException {
        try (PreparedStatement statement = prepare("UPDATE ordering.order_sagas SET "
                + "stock_released_at = clock_timestamp(), stock_lease_until = NULL, updated_at = clock_timestamp() "
                + "WHERE " + OWNED + " AND status = 'CANCELLED' AND stock_released_at IS NULL")) {
            ownership(statement, 1, saga);
            return statement.executeUpdate() == 1;
        }
    }

    boolean pendingLedger(Saga saga, LongSupplier ids) throws SQLException {
        try (PreparedStatement statement = prepare("UPDATE ordering.order_sagas SET status = 'PENDING_LEDGER', "
                + "stock_lease_until = NULL, updated_at = clock_timestamp() WHERE " + OWNED
                + " AND status = 'PENDING_STOCK'")) {
            ownership(statement, 1, saga);
            if (statement.executeUpdate() != 1) {
                return false;
            }
        }
        orderStatus(saga, "PENDING_LEDGER");
        // Only the worker that won the guarded transition may allocate transport IDs.
        long messageId = ids.getAsLong();
        long resultId = ids.getAsLong();
        if (messageId <= 0 || resultId <= 0 || messageId == resultId) {
            throw new StockFailure(UNAVAILABLE);
        }
        String envelope = envelope(saga, messageId, resultId);
        try (PreparedStatement statement = prepare("INSERT INTO ordering.order_result_ids "
                + "(message_id, result_message_id, saga_id) VALUES (?, ?, ?)")) {
            messageIdentity(statement, saga, messageId, resultId);
            requireOne(statement.executeUpdate());
        }
        try (PreparedStatement statement = prepare("INSERT INTO ordering.order_outbox "
                + "(message_id, result_message_id, saga_id, envelope) VALUES (?, ?, ?, ?::jsonb)")) {
            messageIdentity(statement, saga, messageId, resultId);
            statement.setString(4, envelope);
            requireOne(statement.executeUpdate());
        }
        return true;
    }

    private void orderStatus(Saga saga, String status) throws SQLException {
        try (PreparedStatement statement = prepare("UPDATE ordering.orders SET status = ?, "
                + "updated_at = clock_timestamp() WHERE order_id = ? AND buyer_id = ? AND status = 'PENDING_STOCK'")) {
            statement.setString(1, status);
            statement.setLong(2, saga.order().orderId());
            statement.setLong(3, saga.order().buyerId());
            requireOne(statement.executeUpdate());
        }
    }

    private void ownership(PreparedStatement statement, int index, Saga saga) throws SQLException {
        statement.setLong(index, saga.sagaId());
        statement.setLong(index + 1, saga.order().orderId());
        statement.setLong(index + 2, saga.leaseVersion());
    }

    private void messageIdentity(PreparedStatement statement, Saga saga, long messageId, long resultId)
            throws SQLException {
        statement.setLong(1, messageId);
        statement.setLong(2, resultId);
        statement.setLong(3, saga.sagaId());
    }

    private String envelope(Saga saga, long messageId, long resultId) {
        ObjectNode envelope = JsonNodeFactory.instance.objectNode();
        envelope.put("type", "RecordOrder");
        envelope.put("schemaVersion", saga.currencyPolicyVersion());
        envelope.put("messageId", Long.toString(messageId));
        envelope.put("commandId", Long.toString(saga.commandId()));
        envelope.put("sagaId", Long.toString(saga.sagaId()));
        envelope.put("orderId", Long.toString(saga.order().orderId()));
        envelope.put("buyerId", Long.toString(saga.order().buyerId()));
        envelope.put("requestFingerprint", saga.order().fingerprint());
        envelope.put("occurredAt", clock.instant().truncatedTo(ChronoUnit.MICROS).toString());
        envelope.put("resultMessageId", Long.toString(resultId));
        ObjectNode snapshot = envelope.putObject("snapshot");
        snapshot.put("amount", saga.order().totalAmount().setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString());
        snapshot.put("currency", saga.order().currency());
        var items = snapshot.putArray("items");
        saga.items().stream().sorted(Comparator.comparingInt(ItemSnapshot::position)).forEach(item -> {
            ObjectNode value = items.addObject();
            value.put("itemPosition", item.position());
            value.put("sellerId", Long.toString(item.sellerId()));
            value.put("productId", Long.toString(item.productId()));
            value.put("quantity", item.quantity());
            value.put("productName", item.productName());
            value.put("unitPrice", item.unitPrice().setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString());
            value.put("currency", item.currency());
        });
        return envelope.toString();
    }

    void reserve(long orderId, List<ItemSnapshot> items) throws SQLException {
        guard(orderId, false);
        for (ItemSnapshot item : sorted(items)) {
            boolean inserted;
            try (PreparedStatement statement = prepare("INSERT INTO catalog.stock_reservations "
                    + "(order_id, product_id, quantity) VALUES (?, ?, ?) "
                    + "ON CONFLICT (order_id, product_id) DO NOTHING")) {
                statement.setLong(1, orderId);
                statement.setLong(2, item.productId());
                statement.setInt(3, item.quantity());
                inserted = statement.executeUpdate() == 1;
            }
            if (inserted) {
                decrement(item);
            } else {
                if (!reservation(orderId, item, true)) {
                    throw new StockFailure(RESERVATION_CONFLICT);
                }
            }
        }
    }

    private void decrement(ItemSnapshot item) throws SQLException {
        try (PreparedStatement statement = prepare("UPDATE catalog.products SET stock = stock - ? "
                + "WHERE product_id = ? AND seller_id = ? AND stock >= ?")) {
            statement.setInt(1, item.quantity());
            statement.setLong(2, item.productId());
            statement.setLong(3, item.sellerId());
            statement.setInt(4, item.quantity());
            if (statement.executeUpdate() == 1) {
                return;
            }
        }
        // An absent/mismatched product is not proof of stock exhaustion.
        try (PreparedStatement statement = prepare("SELECT seller_id FROM catalog.products WHERE product_id = ?")) {
            statement.setLong(1, item.productId());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new StockFailure(UNAVAILABLE);
                }
                if (rows.getLong("seller_id") != item.sellerId()) {
                    throw new StockFailure(RESERVATION_CONFLICT);
                }
            }
        }
        throw new StockFailure(OUT_OF_STOCK);
    }

    void release(long orderId, List<ItemSnapshot> items) throws SQLException {
        guard(orderId, true);
        for (ItemSnapshot item : sorted(items)) {
            if (!reservation(orderId, item, false)) {
                continue;
            }
            try (PreparedStatement statement = prepare("UPDATE catalog.stock_reservations "
                    + "SET released_at = clock_timestamp() WHERE order_id = ? AND product_id = ? "
                    + "AND released_at IS NULL RETURNING quantity")) {
                statement.setLong(1, orderId);
                statement.setLong(2, item.productId());
                try (ResultSet rows = statement.executeQuery()) {
                    if (rows.next()) {
                        restore(item, rows.getInt("quantity"));
                    }
                }
            }
        }
    }

    /** Reserve and release both lock this permanent row before touching product locks. */
    private void guard(long orderId, boolean close) throws SQLException {
        try (PreparedStatement statement = prepare("INSERT INTO catalog.stock_reservation_guards(order_id) "
                + "VALUES (?) ON CONFLICT (order_id) DO NOTHING")) {
            statement.setLong(1, orderId);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = prepare("SELECT closed FROM catalog.stock_reservation_guards "
                + "WHERE order_id = ? FOR UPDATE")) {
            statement.setLong(1, orderId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new StockFailure(UNAVAILABLE);
                }
                if (!close && rows.getBoolean("closed")) {
                    throw new StockFailure(RESERVATION_CONFLICT);
                }
            }
        }
        if (close) {
            try (PreparedStatement statement = prepare("UPDATE catalog.stock_reservation_guards "
                    + "SET closed = TRUE WHERE order_id = ?")) {
                statement.setLong(1, orderId);
                requireOne(statement.executeUpdate());
            }
        }
    }

    private boolean reservation(long orderId, ItemSnapshot item, boolean requireActive) throws SQLException {
        try (PreparedStatement statement = prepare("SELECT r.quantity, r.released_at, p.seller_id "
                + "FROM catalog.stock_reservations r JOIN catalog.products p ON p.product_id = r.product_id "
                + "WHERE r.order_id = ? AND r.product_id = ?")) {
            statement.setLong(1, orderId);
            statement.setLong(2, item.productId());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return false;
                }
                if (rows.getInt("quantity") != item.quantity() || rows.getLong("seller_id") != item.sellerId()
                        || (requireActive && rows.getTimestamp("released_at") != null)) {
                    throw new StockFailure(RESERVATION_CONFLICT);
                }
                return true;
            }
        }
    }

    private void restore(ItemSnapshot item, int quantity) throws SQLException {
        try (PreparedStatement statement = prepare("UPDATE catalog.products SET stock = stock + ? "
                + "WHERE product_id = ? AND seller_id = ? AND stock <= initial_stock - ?")) {
            statement.setInt(1, quantity);
            statement.setLong(2, item.productId());
            statement.setLong(3, item.sellerId());
            statement.setInt(4, quantity);
            requireOne(statement.executeUpdate());
        }
    }

    private List<ItemSnapshot> sorted(List<ItemSnapshot> items) {
        return items.stream().sorted(Comparator.comparingLong(ItemSnapshot::productId)).toList();
    }

    private long positiveMillis(Duration duration) {
        long millis = nonnegativeMillis(duration);
        if (millis == 0) {
            throw new IllegalArgumentException("Stock lease must be at least one millisecond");
        }
        return millis;
    }

    private long nonnegativeMillis(Duration duration) {
        if (duration.isNegative()) {
            throw new IllegalArgumentException("Stock duration must be nonnegative");
        }
        return duration.toMillis();
    }

    private void requireOne(int count) throws SQLException {
        if (count != 1) {
            throw new SQLException("Unexpected stock write count");
        }
    }
}
