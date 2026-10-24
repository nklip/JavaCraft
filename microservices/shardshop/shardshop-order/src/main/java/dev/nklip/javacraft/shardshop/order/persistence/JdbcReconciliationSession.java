package dev.nklip.javacraft.shardshop.order.persistence;

import dev.nklip.javacraft.shardshop.common.jdbc.DatabasePhases;
import dev.nklip.javacraft.shardshop.order.ordering.OrderModel.ItemSnapshot;
import dev.nklip.javacraft.shardshop.order.ordering.OrderModel.Order;
import dev.nklip.javacraft.shardshop.order.reconciliation.ReconciliationFailure;
import dev.nklip.javacraft.shardshop.order.reconciliation.ReconciliationStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** One buyer-shard transaction; the caller takes the advisory lock before loading a saga. */
final class JdbcReconciliationSession implements ReconciliationStore.Session {
    private final Connection connection;
    private final DatabasePhases.Deadline deadline;

    JdbcReconciliationSession(Connection connection, DatabasePhases.Deadline deadline) {
        this.connection = connection;
        this.deadline = deadline;
    }

    private PreparedStatement prepare(String sql) throws SQLException {
        DatabasePhases.refreshTimeouts(connection, deadline);
        return connection.prepareStatement(sql);
    }

    List<ReconciliationStore.Candidate> pending(Instant now, long afterSagaId, int limit) throws SQLException {
        try (PreparedStatement statement = prepare("SELECT saga_id, order_id FROM ordering.order_sagas "
                + "WHERE status='PENDING_LEDGER' AND reconciliation_attempts<5 "
                + "AND next_reconciliation_at<=? AND saga_id>? ORDER BY saga_id LIMIT ?")) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setLong(2, afterSagaId);
            statement.setInt(3, limit);
            try (ResultSet rows = statement.executeQuery()) {
                List<ReconciliationStore.Candidate> candidates = new ArrayList<>();
                while (rows.next()) {
                    candidates.add(new ReconciliationStore.Candidate(rows.getLong("saga_id"), rows.getLong("order_id")));
                }
                return List.copyOf(candidates);
            }
        }
    }

    long exhausted() throws SQLException {
        try (PreparedStatement statement = prepare("SELECT count(*) FROM ordering.order_sagas "
                + "WHERE status='PENDING_LEDGER' AND reconciliation_attempts=5")) {
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("Order reconciliation count failed");
                }
                return rows.getLong(1);
            }
        }
    }

    @Override
    public void lock(long key) {
        try (PreparedStatement statement = prepare("SELECT pg_advisory_xact_lock(?)")) {
            statement.setLong(1, key);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("Order reconciliation lock failed");
                }
            }
        } catch (SQLException failure) {
            throw new ReconciliationFailure(failure);
        }
    }

    @Override
    public Optional<ReconciliationStore.Saga> saga(long sagaId) {
        try (PreparedStatement statement = prepare("SELECT s.saga_id, s.command_id, s.status AS saga_status, "
                + "s.reconciliation_attempts, s.next_reconciliation_at, o.buyer_id, o.order_id, "
                + "o.request_fingerprint, o.currency, o.total_amount, o.status AS order_status, "
                + "o.currency_policy_version FROM ordering.order_sagas s "
                + "JOIN ordering.orders o ON o.order_id=s.order_id WHERE s.saga_id=? FOR UPDATE OF s")) {
            statement.setLong(1, sagaId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                Order order = new Order(rows.getLong("buyer_id"), rows.getLong("order_id"),
                        rows.getString("request_fingerprint"), rows.getString("currency"),
                        rows.getBigDecimal("total_amount"), rows.getString("order_status"), null);
                return Optional.of(new ReconciliationStore.Saga(rows.getLong("saga_id"), rows.getLong("command_id"),
                        order, items(order.orderId()), rows.getInt("currency_policy_version"),
                        rows.getString("saga_status"), rows.getInt("reconciliation_attempts"),
                        rows.getTimestamp("next_reconciliation_at").toInstant()));
            }
        } catch (SQLException failure) {
            throw new ReconciliationFailure(failure);
        }
    }

    private List<ItemSnapshot> items(long orderId) throws SQLException {
        try (PreparedStatement statement = prepare("SELECT item_position, seller_id, product_id, quantity, "
                + "product_name, unit_price, unit_cost, currency FROM ordering.order_items "
                + "WHERE order_id=? ORDER BY item_position")) {
            statement.setLong(1, orderId);
            try (ResultSet rows = statement.executeQuery()) {
                List<ItemSnapshot> items = new ArrayList<>();
                while (rows.next()) {
                    items.add(new ItemSnapshot(rows.getInt("item_position"), rows.getLong("seller_id"),
                            rows.getLong("product_id"), rows.getInt("quantity"), rows.getString("product_name"),
                            rows.getBigDecimal("unit_price"), rows.getBigDecimal("unit_cost"),
                            rows.getString("currency")));
                }
                return List.copyOf(items);
            }
        }
    }

    @Override
    public boolean orphaned(long orderId) {
        try (PreparedStatement statement = prepare("SELECT 1 FROM ordering.orphan_results "
                + "WHERE order_id=? AND resolved_at IS NULL LIMIT 1")) {
            statement.setLong(1, orderId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        } catch (SQLException failure) {
            throw new ReconciliationFailure(failure);
        }
    }

    @Override
    public boolean unpublished(long sagaId) {
        try (PreparedStatement statement = prepare("SELECT 1 FROM ordering.order_outbox "
                + "WHERE saga_id=? AND published_at IS NULL LIMIT 1")) {
            statement.setLong(1, sagaId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        } catch (SQLException failure) {
            throw new ReconciliationFailure(failure);
        }
    }

    @Override
    public void enqueue(ReconciliationStore.Saga saga, long messageId, long resultMessageId, Instant occurredAt,
                        Instant nextReconciliationAt) {
        try (PreparedStatement statement = prepare("UPDATE ordering.order_sagas SET "
                + "reconciliation_attempts=reconciliation_attempts+1, next_reconciliation_at=?, "
                + "updated_at=GREATEST(clock_timestamp(), created_at) "
                + "WHERE saga_id=? AND order_id=? AND command_id=? AND status='PENDING_LEDGER' "
                + "AND reconciliation_attempts=? AND reconciliation_attempts<5 "
                + "AND next_reconciliation_at<=? "
                + "AND EXISTS (SELECT 1 FROM ordering.orders o "
                + "WHERE o.order_id=ordering.order_sagas.order_id AND o.status='PENDING_LEDGER') "
                + "AND NOT EXISTS (SELECT 1 FROM ordering.order_outbox o "
                + "WHERE o.saga_id=ordering.order_sagas.saga_id AND o.published_at IS NULL)")) {
            statement.setTimestamp(1, Timestamp.from(nextReconciliationAt));
            statement.setLong(2, saga.sagaId());
            statement.setLong(3, saga.order().orderId());
            statement.setLong(4, saga.commandId());
            statement.setInt(5, saga.attempts());
            statement.setTimestamp(6, Timestamp.from(occurredAt));
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Order reconciliation is no longer eligible");
            }
            JdbcOrderCommand.insert(connection, deadline, saga.sagaId(), saga.commandId(), saga.order(), saga.items(),
                    saga.currencyPolicyVersion(), messageId, resultMessageId, occurredAt);
        } catch (SQLException failure) {
            throw new ReconciliationFailure(failure);
        }
    }
}
