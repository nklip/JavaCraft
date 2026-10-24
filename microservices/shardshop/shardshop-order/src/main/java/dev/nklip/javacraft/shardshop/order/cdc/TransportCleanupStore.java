package dev.nklip.javacraft.shardshop.order.cdc;

import dev.nklip.javacraft.shardshop.common.jdbc.Endpoint;
import dev.nklip.javacraft.shardshop.common.jdbc.DatabasePhases;
import dev.nklip.javacraft.shardshop.order.persistence.JdbcOrderStore;
import dev.nklip.javacraft.shardshop.sharding.Shard;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Bounded application-role SQL for transport rows, using one order database phase per operation. */
final class TransportCleanupStore {
    record Fence(List<Long> messageIds, long walPosition) {
        Fence { messageIds = List.copyOf(messageIds); }
    }

    private final CdcStore cdc;
    private final ConnectionFactory connections;
    private final JdbcOrderStore orderStore;
    private final Shard shard;

    interface ConnectionFactory {
        Connection open() throws SQLException;
    }

    TransportCleanupStore(Endpoint endpoint, JdbcOrderStore orderStore, Shard shard) {
        Objects.requireNonNull(endpoint);
        this.cdc = new CdcStore(endpoint, endpoint);
        this.connections = null;
        this.orderStore = Objects.requireNonNull(orderStore);
        this.shard = Objects.requireNonNull(shard);
    }

    TransportCleanupStore(Endpoint endpoint, ConnectionFactory connections) {
        Objects.requireNonNull(endpoint);
        this.cdc = new CdcStore(endpoint, endpoint);
        this.connections = Objects.requireNonNull(connections);
        this.orderStore = null;
        this.shard = null;
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection connection, DatabasePhases.Deadline deadline) throws SQLException;
    }

    private <T> T run(boolean readOnly, SqlWork<T> work) throws SQLException {
        if (orderStore != null) {
            return orderStore.transportCleanupPhase(shard, readOnly, work::run);
        }
        try (Connection connection = connections.open()) {
            return work.run(connection, null);
        }
    }

    int deleteInbox(long retentionMicros, int limit) throws SQLException {
        // Recheck age in the DELETE so a concurrently changed row cannot slip through.
        String sql = "WITH old_rows AS (SELECT message_id FROM ordering.order_inbox "
                + "WHERE received_at < statement_timestamp() - (? * interval '1 microsecond') "
                + "ORDER BY received_at, message_id LIMIT ?) "
                + "DELETE FROM ordering.order_inbox i USING old_rows o WHERE i.message_id = o.message_id "
                + "AND i.received_at < statement_timestamp() - (? * interval '1 microsecond')";
        return run(false, (connection, deadline) -> {
            try (PreparedStatement statement = prepare(connection, deadline, sql)) {
                statement.setLong(1, retentionMicros);
                statement.setInt(2, limit);
                statement.setLong(3, retentionMicros);
                return statement.executeUpdate();
            }
        });
    }

    Fence capture(long retentionMicros, int limit) throws SQLException {
        String sql = "SELECT message_id FROM ordering.order_outbox "
                + "WHERE published_at IS NOT NULL "
                + "AND published_at < statement_timestamp() - (? * interval '1 microsecond') "
                + "ORDER BY published_at, message_id LIMIT ?";
        return run(true, (connection, deadline) -> {
            List<Long> ids = new ArrayList<>(limit);
            try (PreparedStatement statement = prepare(connection, deadline, sql)) {
                statement.setLong(1, retentionMicros);
                statement.setInt(2, limit);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        ids.add(rows.getLong(1));
                    }
                }
            }
            if (ids.isEmpty()) {
                return null;
            }
            // This is after the candidate SELECT. Every selected insert was committed before this WAL position.
            try (PreparedStatement statement = prepare(connection, deadline,
                    "SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), '0/0')::bigint");
                 ResultSet rows = statement.executeQuery()) {
                rows.next();
                long position = rows.getLong(1);
                if (position <= 0) {
                    throw new SQLException("Cannot capture cleanup WAL barrier");
                }
                return new Fence(ids, position);
            }
        });
    }

    /** Null means no usable durable streaming checkpoint. Corrupt or foreign data fails closed. */
    Long durableCheckpoint(String identity) throws SQLException {
        return run(true, (connection, deadline) -> {
            boolean initialized;
            try (PreparedStatement statement = prepare(connection, deadline,
                    "SELECT EXISTS (SELECT 1 FROM ordering.cdc_reader_state WHERE reader_name = ?)")) {
                statement.setString(1, identity);
                try (ResultSet rows = statement.executeQuery()) {
                    rows.next();
                    initialized = rows.getBoolean(1);
                }
            }
            if (!initialized) {
                return null;
            }
            // Debezium's JDBC store atomically deletes old offsets and inserts one row per partition.
            // Two rows mean the checkpoint is ambiguous; a bounded read is enough to reject it.
            try (PreparedStatement statement = prepare(connection, deadline,
                    "SELECT offset_key, offset_val FROM ordering.debezium_offset_storage LIMIT 2");
                 ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                String key = rows.getString(1);
                String value = rows.getString(2);
                if (rows.next()) {
                    throw new CdcStore.RecoveryRequiredException("cleanup offset is ambiguous");
                }
                return cdc.validateStreamingOffset(key, value, identity);
            }
        });
    }

    int deleteOutbox(Fence fence, long retentionMicros) throws SQLException {
        String sql = "DELETE FROM ordering.order_outbox WHERE message_id = ANY (?) "
                + "AND published_at IS NOT NULL "
                + "AND published_at < statement_timestamp() - (? * interval '1 microsecond')";
        return run(false, (connection, deadline) -> {
            try (PreparedStatement statement = prepare(connection, deadline, sql)) {
                Array ids = connection.createArrayOf("bigint", fence.messageIds().toArray(Long[]::new));
                try {
                    statement.setArray(1, ids);
                    statement.setLong(2, retentionMicros);
                    return statement.executeUpdate();
                } finally {
                    ids.free();
                }
            }
        });
    }

    private static PreparedStatement prepare(Connection connection, DatabasePhases.Deadline deadline, String sql)
            throws SQLException {
        if (deadline != null) {
            DatabasePhases.refreshTimeouts(connection, deadline);
        }
        PreparedStatement statement = connection.prepareStatement(sql);
        if (deadline == null) {
            statement.setQueryTimeout(3);
        }
        return statement;
    }
}
