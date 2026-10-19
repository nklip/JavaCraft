package dev.nklip.javacraft.shardshop.order.persistence;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nklip.javacraft.shardshop.common.JsonRequests;
import dev.nklip.javacraft.shardshop.common.jdbc.DatabasePhases;
import dev.nklip.javacraft.shardshop.order.ordering.OrderModel.ItemSnapshot;
import dev.nklip.javacraft.shardshop.order.result.LedgerResult;
import dev.nklip.javacraft.shardshop.order.result.ResultFailure;
import dev.nklip.javacraft.shardshop.order.result.ResultStore;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Buyer-shard IO only. The caller owns the transaction and its absolute deadline. */
final class JdbcResultSession implements ResultStore.Session {
    private final Connection connection;
    private final DatabasePhases.Deadline deadline;

    JdbcResultSession(Connection connection, DatabasePhases.Deadline deadline) {
        this.connection = connection;
        this.deadline = deadline;
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
                    throw new SQLException("Order result lock failed");
                }
            }
        } catch (SQLException failure) {
            throw new ResultFailure(failure);
        }
    }

    @Override
    public Optional<ResultStore.State> state(long orderId) {
        // Stock coordination locks the saga before the order; use the same ordering.
        try (PreparedStatement statement = prepare("SELECT saga_id FROM ordering.order_sagas "
                + "WHERE order_id=? FOR UPDATE")) {
            statement.setLong(1, orderId);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    rows.getLong(1);
                }
            }
        } catch (SQLException failure) {
            throw new ResultFailure(failure);
        }
        try (PreparedStatement statement = prepare("SELECT o.order_id, o.buyer_id, o.request_fingerprint, "
                + "o.currency, o.total_amount, o.status AS order_status, s.saga_id, s.command_id, "
                + "s.status AS saga_status, s.cancellation_reason, s.ledger_result::text "
                + "FROM ordering.orders o LEFT JOIN ordering.order_sagas s ON s.order_id=o.order_id "
                + "WHERE o.order_id=?")) {
            statement.setLong(1, orderId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                return Optional.of(new ResultStore.State(rows.getLong("order_id"), rows.getLong("buyer_id"),
                        rows.getString("request_fingerprint"), rows.getString("currency"),
                        rows.getBigDecimal("total_amount"), rows.getString("order_status"), rows.getLong("saga_id"),
                        rows.getLong("command_id"), rows.getString("saga_status"),
                        rows.getString("cancellation_reason"), json(rows.getString("ledger_result"))));
            }
        } catch (SQLException failure) {
            throw new ResultFailure(failure);
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
            throw new ResultFailure(failure);
        }
    }

    @Override
    public Optional<ResultStore.Binding> binding(long messageId) {
        try (PreparedStatement statement = prepare("SELECT saga_id, received_envelope::text "
                + "FROM ordering.order_result_ids WHERE result_message_id=?")) {
            statement.setLong(1, messageId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(new ResultStore.Binding(rows.getLong(1), json(rows.getString(2))))
                        : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new ResultFailure(failure);
        }
    }

    @Override
    public List<ItemSnapshot> items(long orderId) {
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
        } catch (SQLException failure) {
            throw new ResultFailure(failure);
        }
    }

    @Override
    public void quarantine(LedgerResult result, String reason, ObjectNode expected, boolean orphan) {
        String table = orphan ? "ordering.orphan_results" : "ordering.conflicting_results";
        String identities = orphan ? "" : ", expected_identity, received_identity";
        String values = orphan ? "" : ", ?::jsonb, ?::jsonb";
        try (PreparedStatement statement = prepare("INSERT INTO " + table
                + " (message_id, payload_fingerprint, command_id, saga_id, order_id, buyer_id, "
                + "request_fingerprint, outcome, envelope, reason" + identities + ") "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?" + values + ") "
                + "ON CONFLICT (message_id, payload_fingerprint) DO UPDATE SET "
                + "last_seen_at=GREATEST(clock_timestamp(), " + table + ".last_seen_at), "
                + "delivery_count=CASE WHEN " + table + ".delivery_count=9223372036854775807 THEN "
                + table + ".delivery_count ELSE " + table + ".delivery_count+1 END")) {
            statement.setLong(1, result.messageId());
            statement.setString(2, result.payloadFingerprint());
            statement.setLong(3, result.commandId());
            statement.setLong(4, result.sagaId());
            statement.setLong(5, result.orderId());
            statement.setLong(6, result.buyerId());
            statement.setString(7, result.requestFingerprint());
            statement.setString(8, result.outcome());
            statement.setString(9, result.envelope().toString());
            statement.setString(10, reason);
            if (!orphan) {
                statement.setString(11, expected.toString());
                statement.setString(12, result.envelope().toString());
            }
            requireOne(statement.executeUpdate());
        } catch (SQLException failure) {
            throw new ResultFailure(failure);
        }
    }

    @Override
    public void apply(ResultStore.State state, LedgerResult result, List<ResultStore.Credit> credits) {
        String status = "RECORDED".equals(result.outcome()) ? "CONFIRMED" : "CANCELLED";
        try (PreparedStatement statement = prepare("UPDATE ordering.order_sagas SET status=?, "
                + "cancellation_reason=?, ledger_result=?::jsonb, updated_at=GREATEST(clock_timestamp(), created_at), "
                + "stock_lease_until=NULL, stock_lease_version=stock_lease_version+1, "
                + "next_stock_attempt_at=clock_timestamp() WHERE saga_id=? AND order_id=? "
                + "AND status='PENDING_LEDGER' AND ledger_result IS NULL")) {
            statement.setString(1, status);
            statement.setString(2, result.rejectionReason());
            statement.setString(3, result.envelope().toString());
            statement.setLong(4, state.sagaId());
            statement.setLong(5, state.orderId());
            requireOne(statement.executeUpdate());
        } catch (SQLException failure) {
            throw new ResultFailure(failure);
        }
        try (PreparedStatement statement = prepare("UPDATE ordering.orders SET status=?, "
                + "updated_at=GREATEST(clock_timestamp(), created_at) WHERE order_id=? AND status='PENDING_LEDGER'")) {
            statement.setString(1, status);
            statement.setLong(2, state.orderId());
            requireOne(statement.executeUpdate());
        } catch (SQLException failure) {
            throw new ResultFailure(failure);
        }
        if (!credits.isEmpty()) {
            insertCredits(state.orderId(), credits);
        }
    }

    private void insertCredits(long orderId, List<ResultStore.Credit> credits) {
        try (PreparedStatement statement = prepare("INSERT INTO ordering.profit_intents "
                + "(order_id, item_position, seller_id, product_id, currency, amount) VALUES (?, ?, ?, ?, ?, ?)")) {
            for (var credit : credits) {
                statement.setLong(1, orderId);
                statement.setInt(2, credit.item().position());
                statement.setLong(3, credit.item().sellerId());
                statement.setLong(4, credit.item().productId());
                statement.setString(5, credit.item().currency());
                statement.setBigDecimal(6, credit.amount());
                statement.addBatch();
            }
            for (int count : statement.executeBatch()) {
                requireOne(count);
            }
        } catch (SQLException failure) {
            throw new ResultFailure(failure);
        }
    }

    @Override
    public void receive(LedgerResult result) {
        try (PreparedStatement statement = prepare("UPDATE ordering.order_result_ids SET received_envelope=?::jsonb "
                + "WHERE result_message_id=? AND saga_id=? AND received_envelope IS NULL")) {
            statement.setString(1, result.envelope().toString());
            statement.setLong(2, result.messageId());
            statement.setLong(3, result.sagaId());
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new ResultFailure(failure);
        }
        try (PreparedStatement statement = prepare("INSERT INTO ordering.order_inbox(message_id, saga_id, envelope) "
                + "VALUES (?, ?, ?::jsonb) ON CONFLICT (message_id) DO NOTHING")) {
            statement.setLong(1, result.messageId());
            statement.setLong(2, result.sagaId());
            statement.setString(3, result.envelope().toString());
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new ResultFailure(failure);
        }
    }

    private static ObjectNode json(String value) {
        return value == null ? null : (ObjectNode) LedgerResult.canonical(
                JsonRequests.parse(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static void requireOne(int count) throws SQLException {
        if (count != 1) {
            throw new SQLException("Unexpected order result write count");
        }
    }
}
