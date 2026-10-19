package dev.nklip.javacraft.shardshop.order.persistence;

import dev.nklip.javacraft.shardshop.common.jdbc.DatabasePhases;
import dev.nklip.javacraft.shardshop.order.profit.ProfitFailure;
import dev.nklip.javacraft.shardshop.order.profit.ProfitStore.PendingCredit;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** One bounded transaction per home read/write or seller credit. */
final class JdbcProfitSession {
    private final Connection connection;
    private final DatabasePhases.Deadline deadline;

    JdbcProfitSession(Connection connection, DatabasePhases.Deadline deadline) {
        this.connection = connection;
        this.deadline = deadline;
    }

    private PreparedStatement prepare(String sql) throws SQLException {
        DatabasePhases.refreshTimeouts(connection, deadline);
        return connection.prepareStatement(sql);
    }

    List<PendingCredit> pendingProfit(int limit) throws SQLException {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Profit batch size must be between 1 and 100");
        }
        try (PreparedStatement statement = prepare("SELECT order_id, item_position, seller_id, product_id, "
                + "currency, amount, failures FROM ordering.profit_intents "
                + "WHERE applied_at IS NULL AND blocked_reason IS NULL "
                + "AND next_attempt_at <= clock_timestamp() "
                + "ORDER BY next_attempt_at, order_id, item_position LIMIT ?")) {
            statement.setInt(1, limit);
            try (ResultSet rows = statement.executeQuery()) {
                List<PendingCredit> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(new PendingCredit(rows.getLong("order_id"), rows.getInt("item_position"),
                            rows.getLong("seller_id"), rows.getLong("product_id"), rows.getString("currency"),
                            rows.getBigDecimal("amount"), rows.getInt("failures")));
                }
                return List.copyOf(result);
            }
        }
    }

    void creditProfit(PendingCredit credit) throws SQLException {
        // ON CONFLICT serializes concurrent workers on the permanent credit key.
        // Only the winner changes the balance, within this same transaction.
        try (PreparedStatement statement = prepare("INSERT INTO catalog.profit_credits "
                + "(order_id, product_id, seller_id, currency, amount) VALUES (?, ?, ?, ?, ?) "
                + "ON CONFLICT (order_id, product_id) DO NOTHING RETURNING order_id")) {
            statement.setLong(1, credit.orderId());
            statement.setLong(2, credit.productId());
            statement.setLong(3, credit.sellerId());
            statement.setString(4, credit.currency());
            statement.setBigDecimal(5, credit.amount());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    verifyCredit(credit);
                    return;
                }
            }
        }
        try (PreparedStatement statement = prepare("INSERT INTO catalog.seller_profits (seller_id, currency) "
                + "VALUES (?, ?) ON CONFLICT (seller_id, currency) DO NOTHING")) {
            statement.setLong(1, credit.sellerId());
            statement.setString(2, credit.currency());
            statement.executeUpdate();
        }
        try (PreparedStatement statement = prepare("UPDATE catalog.seller_profits SET amount = amount + ? "
                + "WHERE seller_id = ? AND currency = ?")) {
            statement.setBigDecimal(1, credit.amount());
            statement.setLong(2, credit.sellerId());
            statement.setString(3, credit.currency());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Profit balance was not updated");
            }
        }
    }

    private void verifyCredit(PendingCredit credit) throws SQLException {
        try (PreparedStatement statement = prepare("SELECT seller_id, currency, amount "
                + "FROM catalog.profit_credits WHERE order_id = ? AND product_id = ?")) {
            statement.setLong(1, credit.orderId());
            statement.setLong(2, credit.productId());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || rows.getLong("seller_id") != credit.sellerId()
                        || !rows.getString("currency").equals(credit.currency())
                        || rows.getBigDecimal("amount").compareTo(credit.amount()) != 0) {
                    throw new ProfitFailure(ProfitFailure.Reason.CREDIT_CONFLICT);
                }
            }
        }
    }

    void markProfitApplied(PendingCredit credit) throws SQLException {
        try (PreparedStatement statement = prepare("UPDATE ordering.profit_intents "
                + "SET applied_at = GREATEST(clock_timestamp(), created_at) "
                + "WHERE order_id = ? AND item_position = ? "
                + "AND seller_id = ? AND product_id = ? AND currency = ? AND amount = ? "
                + "AND applied_at IS NULL AND blocked_reason IS NULL")) {
            identity(statement, credit);
            statement.executeUpdate();
        }
    }

    void retryProfit(PendingCredit credit, Duration delay) throws SQLException {
        if (delay.isNegative() || delay.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("Profit retry must be between zero and 30 seconds");
        }
        try (PreparedStatement statement = prepare("UPDATE ordering.profit_intents "
                + "SET failures = LEAST(failures::bigint + 1, 2147483647)::integer, "
                + "next_attempt_at = clock_timestamp() + (? * INTERVAL '1 millisecond') "
                + "WHERE order_id = ? AND item_position = ? AND seller_id = ? AND product_id = ? "
                + "AND currency = ? AND amount = ? AND applied_at IS NULL AND blocked_reason IS NULL")) {
            statement.setLong(1, delay.toMillis());
            identity(statement, credit, 2);
            statement.executeUpdate();
        }
    }

    void blockProfit(PendingCredit credit, ProfitFailure.Reason reason) throws SQLException {
        if (reason == ProfitFailure.Reason.UNAVAILABLE) {
            throw new IllegalArgumentException("Transient profit failure cannot block an intent");
        }
        try (PreparedStatement statement = prepare("UPDATE ordering.profit_intents "
                + "SET blocked_reason = ? WHERE order_id = ? AND item_position = ? "
                + "AND seller_id = ? AND product_id = ? AND currency = ? AND amount = ? "
                + "AND applied_at IS NULL")) {
            statement.setString(1, reason.name());
            identity(statement, credit, 2);
            statement.executeUpdate();
        }
    }

    private void identity(PreparedStatement statement, PendingCredit credit) throws SQLException {
        identity(statement, credit, 1);
    }

    private void identity(PreparedStatement statement, PendingCredit credit, int first) throws SQLException {
        statement.setLong(first, credit.orderId());
        statement.setInt(first + 1, credit.itemPosition());
        statement.setLong(first + 2, credit.sellerId());
        statement.setLong(first + 3, credit.productId());
        statement.setString(first + 4, credit.currency());
        statement.setBigDecimal(first + 5, credit.amount());
    }
}
