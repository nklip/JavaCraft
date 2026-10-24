package dev.nklip.javacraft.shardshop.order.persistence;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nklip.javacraft.shardshop.common.jdbc.DatabasePhases;
import dev.nklip.javacraft.shardshop.order.ordering.OrderModel.ItemSnapshot;
import dev.nklip.javacraft.shardshop.order.ordering.OrderModel.Order;

import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

/** The initial command and every replay share the same immutable business envelope. */
final class JdbcOrderCommand {
    private JdbcOrderCommand() { }

    static void insert(Connection connection, DatabasePhases.Deadline deadline, long sagaId, long commandId,
                       Order order, List<ItemSnapshot> items, int currencyPolicyVersion,
                       long messageId, long resultMessageId, Instant occurredAt) throws SQLException {
        if (messageId <= 0 || resultMessageId <= 0 || messageId == resultMessageId) {
            throw new SQLException("Invalid order command transport IDs");
        }
        String envelope = envelope(sagaId, commandId, order, items, currencyPolicyVersion,
                messageId, resultMessageId, occurredAt);
        try (PreparedStatement statement = prepare(connection, deadline, "INSERT INTO ordering.order_result_ids "
                + "(message_id, result_message_id, saga_id) VALUES (?, ?, ?)")) {
            identity(statement, sagaId, messageId, resultMessageId);
            requireOne(statement.executeUpdate());
        }
        try (PreparedStatement statement = prepare(connection, deadline, "INSERT INTO ordering.order_outbox "
                + "(message_id, result_message_id, saga_id, envelope) VALUES (?, ?, ?, ?::jsonb)")) {
            identity(statement, sagaId, messageId, resultMessageId);
            statement.setString(4, envelope);
            requireOne(statement.executeUpdate());
        }
    }

    static String envelope(long sagaId, long commandId, Order order, List<ItemSnapshot> items,
                           int currencyPolicyVersion, long messageId, long resultMessageId, Instant occurredAt) {
        ObjectNode envelope = JsonNodeFactory.instance.objectNode();
        envelope.put("type", "RecordOrder");
        envelope.put("schemaVersion", currencyPolicyVersion);
        envelope.put("messageId", Long.toString(messageId));
        envelope.put("commandId", Long.toString(commandId));
        envelope.put("sagaId", Long.toString(sagaId));
        envelope.put("orderId", Long.toString(order.orderId()));
        envelope.put("buyerId", Long.toString(order.buyerId()));
        envelope.put("requestFingerprint", order.fingerprint());
        envelope.put("occurredAt", occurredAt.truncatedTo(ChronoUnit.MICROS).toString());
        envelope.put("resultMessageId", Long.toString(resultMessageId));
        ObjectNode snapshot = envelope.putObject("snapshot");
        snapshot.put("amount", order.totalAmount().setScale(2, RoundingMode.UNNECESSARY).toPlainString());
        snapshot.put("currency", order.currency());
        var snapshotItems = snapshot.putArray("items");
        items.stream().sorted(Comparator.comparingInt(ItemSnapshot::position)).forEach(item -> {
            ObjectNode value = snapshotItems.addObject();
            value.put("itemPosition", item.position());
            value.put("sellerId", Long.toString(item.sellerId()));
            value.put("productId", Long.toString(item.productId()));
            value.put("quantity", item.quantity());
            value.put("productName", item.productName());
            value.put("unitPrice", item.unitPrice().setScale(2, RoundingMode.UNNECESSARY).toPlainString());
            value.put("currency", item.currency());
        });
        return envelope.toString();
    }

    private static PreparedStatement prepare(Connection connection, DatabasePhases.Deadline deadline, String sql)
            throws SQLException {
        DatabasePhases.refreshTimeouts(connection, deadline);
        return connection.prepareStatement(sql);
    }

    private static void identity(PreparedStatement statement, long sagaId, long messageId, long resultMessageId)
            throws SQLException {
        statement.setLong(1, messageId);
        statement.setLong(2, resultMessageId);
        statement.setLong(3, sagaId);
    }

    private static void requireOne(int count) throws SQLException {
        if (count != 1) {
            throw new SQLException("Unexpected order command write count");
        }
    }
}
