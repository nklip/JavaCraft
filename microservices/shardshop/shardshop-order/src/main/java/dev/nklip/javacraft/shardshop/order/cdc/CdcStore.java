package dev.nklip.javacraft.shardshop.order.cdc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nklip.javacraft.shardshop.common.jdbc.Endpoint;
import dev.nklip.javacraft.shardshop.common.jdbc.JdbcUrls;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Application-role SQL for ownership, recovery preflight, and publication status. */
final class CdcStore {
    private static final long OWNERSHIP_KEY = 0x736861726473686fL;
    private final Endpoint endpoint;
    private final Endpoint replication;
    private final ObjectMapper json = new ObjectMapper();

    CdcStore(Endpoint endpoint, Endpoint replication) {
        this.endpoint = endpoint;
        this.replication = replication;
    }

    /** The server view of one replication slot; positions are byte offsets from LSN 0/0. */
    record Slot(boolean exists, boolean active, String walStatus, String invalidationReason, String plugin,
                String type, boolean sameDatabase, boolean failover, Long confirmedFlushLsn) {
        static final Slot ABSENT = new Slot(false, false, null, null, null, null, false, false, null);
    }

    Connection own() throws SQLException {
        Connection connection = connect();
        try (PreparedStatement statement = prepare(connection, "SELECT pg_try_advisory_lock(?)")) {
            statement.setLong(1, OWNERSHIP_KEY);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || !rows.getBoolean(1)) {
                    throw new SQLException("CDC shard is already owned");
                }
            }
            return connection;
        } catch (SQLException failure) {
            connection.close();
            throw failure;
        }
    }

    /**
     * Runs before every reader start, so each retry decides from durable state, never from an error message.
     * Returns true when the slot belongs to a first start that never saved a checkpoint and must be recreated.
     */
    boolean checkRecovery(Connection owner, String slotName) throws SQLException {
        Long resume = null;
        try (PreparedStatement statement = prepare(owner,
                "SELECT offset_key, offset_val FROM ordering.debezium_offset_storage")) {
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    long position = validateOffset(rows.getString(1), rows.getString(2), slotName);
                    resume = resume == null ? position : Math.max(resume, position);
                }
            }
        }
        boolean initialized;
        try (PreparedStatement statement = prepare(owner,
                "SELECT EXISTS (SELECT 1 FROM ordering.cdc_reader_state WHERE reader_name = ?)")) {
            statement.setString(1, slotName);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                initialized = rows.getBoolean(1);
            }
        }
        Slot slot = Slot.ABSENT;
        try (PreparedStatement statement = prepare(owner, "SELECT active, wal_status, invalidation_reason, plugin, "
                + "slot_type, database = current_database() AS same_database, failover, "
                + "pg_wal_lsn_diff(confirmed_flush_lsn, '0/0')::bigint AS confirmed_flush "
                + "FROM pg_replication_slots WHERE slot_name = ?")) {
            statement.setString(1, slotName);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    Long confirmed = rows.getLong("confirmed_flush");
                    if (rows.wasNull()) {
                        confirmed = null;
                    }
                    slot = new Slot(true, rows.getBoolean("active"), rows.getString("wal_status"),
                            rows.getString("invalidation_reason"), rows.getString("plugin"),
                            rows.getString("slot_type"), rows.getBoolean("same_database"), rows.getBoolean("failover"),
                            confirmed);
                }
            }
        }
        boolean recreate = uncheckpointed(initialized, resume, slot);
        String reason = recreate ? null : recoveryReason(initialized, resume, slot);
        if (reason != null) {
            throw new RecoveryRequiredException(reason);
        }
        if (slot.active()) {
            throw new SQLException("CDC replication slot already active");
        }
        return recreate;
    }

    /**
     * A slot of this connector's shape without an offset and without reader history: no checkpoint ever existed.
     * Debezium cannot take an exported snapshot from an existing slot, and such a snapshot can miss concurrent
     * commits. Dropping the slot lets Debezium create a new slot with a consistent exported snapshot.
     */
    static boolean uncheckpointed(boolean initialized, Long resume, Slot slot) {
        return !initialized && resume == null && slot.exists() && "pgoutput".equals(slot.plugin())
                && "logical".equals(slot.type()) && slot.sameDatabase();
    }

    /** Only a replication login may drop a slot; the caller holds the shard ownership lock. */
    void dropSlot(String slotName) throws SQLException {
        try (Connection connection = DriverManager.getConnection(JdbcUrls.withDriverLimits(replication.jdbcUrl()),
                replication.username(), replication.password());
             PreparedStatement statement = prepare(connection, "SELECT pg_drop_replication_slot(?)")) {
            statement.setString(1, slotName);
            statement.execute();
        }
    }

    /**
     * Returns the position from which Debezium resumes: the last stored commit, otherwise the last event.
     * Debezium's trust_offset strategy seeks the slot to this position and fails when the slot is ahead of it.
     */
    long validateOffset(String key, String value, String identity) {
        try {
            return CdcOffset.position(key, value, identity);
        } catch (IllegalArgumentException invalid) {
            throw new RecoveryRequiredException(invalid.getMessage());
        }
    }

    /** A snapshot offset has not yet proved that the reader passed a later WAL barrier. */
    long validateStreamingOffset(String key, String value, String identity) {
        long position = validateOffset(key, value, identity);
        try {
            JsonNode offset = json.readTree(value);
            JsonNode snapshot = offset.path("snapshot");
            if (!snapshot.isMissingNode() && !snapshot.isBoolean() && !snapshot.isTextual()) {
                throw new RecoveryRequiredException("cleanup offset has ambiguous snapshot state");
            }
            if (snapshot.isBoolean() && snapshot.booleanValue()
                    || snapshot.isTextual() && !"false".equals(snapshot.textValue())) {
                throw new RecoveryRequiredException("cleanup offset is still a snapshot");
            }
            JsonNode completed = offset.path("snapshot_completed");
            if (!completed.isMissingNode() && !completed.isBoolean()) {
                throw new RecoveryRequiredException("cleanup offset has ambiguous snapshot completion");
            }
            if (completed.isBoolean() && !completed.booleanValue()) {
                throw new RecoveryRequiredException("cleanup offset has an incomplete snapshot");
            }
            return position;
        } catch (IOException malformed) {
            throw new RecoveryRequiredException("offset data is malformed");
        }
    }

    /** Names the reason why this reader needs explicit reconciliation, or returns null when it may start. */
    static String recoveryReason(boolean initialized, Long resume, Slot slot) {
        if (slot.exists()) {
            if (slot.invalidationReason() != null || "lost".equals(slot.walStatus())) {
                return "replication slot was invalidated ("
                        + (slot.invalidationReason() != null ? slot.invalidationReason() : "wal_status lost") + ")";
            }
            if (!"pgoutput".equals(slot.plugin()) || !"logical".equals(slot.type()) || !slot.sameDatabase()
                    || !slot.failover()) {
                return "replication slot is not a failover pgoutput slot of this database";
            }
        }
        boolean offset = resume != null;
        if (offset && !initialized) {
            return "durable offset exists without reader history";
        }
        if (offset && !slot.exists()) {
            return "durable offset exists without its replication slot";
        }
        if (!offset && initialized) {
            return slot.exists() ? "replication slot exists without a durable offset"
                    : "reader history exists without its offset and replication slot";
        }
        if (offset && slot.confirmedFlushLsn() != null && slot.confirmedFlushLsn() > resume) {
            return "replication slot is ahead of its durable offset";
        }
        return null;
    }

    boolean ownerAlive(Connection owner) {
        try {
            return !owner.isClosed() && owner.isValid(2);
        } catch (SQLException failure) {
            return false;
        }
    }

    void markPublished(String messageId) throws SQLException {
        long id = Long.parseLong(messageId);
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = prepare(connection,
                    "UPDATE ordering.order_outbox SET published_at = clock_timestamp() "
                            + "WHERE message_id = ? AND published_at IS NULL")) {
                statement.setLong(1, id);
                int updated = statement.executeUpdate();
                if (updated != 1) {
                    try (PreparedStatement check = prepare(connection,
                            "SELECT published_at IS NOT NULL FROM ordering.order_outbox WHERE message_id = ?")) {
                        check.setLong(1, id);
                        try (ResultSet rows = check.executeQuery()) {
                            if (!rows.next() || !rows.getBoolean(1)) {
                                throw new SQLException("Captured outbox row is missing or still unpublished");
                            }
                        }
                    }
                }
            }
            connection.commit();
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(JdbcUrls.withDriverLimits(endpoint.jdbcUrl()),
                endpoint.username(), endpoint.password());
    }

    private static PreparedStatement prepare(Connection connection, String sql) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        statement.setQueryTimeout(3);
        return statement;
    }

    static final class RecoveryRequiredException extends IllegalStateException {
        RecoveryRequiredException(String message) {
            super(message);
        }
    }
}
