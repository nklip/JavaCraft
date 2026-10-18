package dev.nklip.javacraft.shardshop.ledger.persistence;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nklip.javacraft.shardshop.common.JsonRequests;
import dev.nklip.javacraft.shardshop.common.jdbc.DatabasePhases;
import dev.nklip.javacraft.shardshop.common.jdbc.Endpoint;
import dev.nklip.javacraft.shardshop.common.jdbc.JdbcUrls;
import dev.nklip.javacraft.shardshop.ledger.command.LedgerCommand;
import dev.nklip.javacraft.shardshop.ledger.command.LedgerDecision;
import dev.nklip.javacraft.shardshop.ledger.command.LedgerStore;
import dev.nklip.javacraft.shardshop.ledger.command.TransportBinding;
import io.agroal.api.AgroalDataSource;
import io.agroal.api.configuration.supplier.AgroalDataSourceConfigurationSupplier;
import io.agroal.api.exceptionsorter.PostgreSQLExceptionSorter;
import io.agroal.api.security.NamePrincipal;
import io.agroal.api.security.SimplePassword;
import org.postgresql.Driver;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Function;

/** Atomic, bounded ledger decisions and duplicate-result rearming on the ledger primary. */
public final class JdbcLedgerStore implements LedgerStore, AutoCloseable {
    private final AgroalDataSource pool;
    private final DatabasePhases phases;

    public JdbcLedgerStore(Endpoint endpoint, int maxPoolSize) {
        if (maxPoolSize < 1 || maxPoolSize > 32) {
            throw new IllegalArgumentException("Ledger pool size must be between 1 and 32");
        }
        try {
            pool = AgroalDataSource.from(new AgroalDataSourceConfigurationSupplier()
                    .connectionPoolConfiguration(configuration -> configuration
                            .initialSize(0).minSize(0).maxSize(maxPoolSize)
                            .acquisitionTimeout(DatabasePhases.ACQUISITION_TIMEOUT)
                            .exceptionSorter(new PostgreSQLExceptionSorter())
                            .establishmentRetryAttempts(0)
                            .connectionFactoryConfiguration(factory -> factory
                                    .connectionProviderClass(Driver.class)
                                    // The retry log names the server error, never the row values of its detail.
                                    .jdbcUrl(JdbcUrls.withDriverLimits(endpoint.jdbcUrl())
                                            + "&logServerErrorDetail=false")
                                    .principal(new NamePrincipal(endpoint.username()))
                                    .credential(new SimplePassword(endpoint.password()))
                                    .loginTimeout(Duration.ofSeconds(1))
                                    .networkTimeout(DatabasePhases.PHASE_BUDGET))));
        } catch (SQLException failure) {
            throw new IllegalStateException("Ledger store unavailable");
        }
        phases = new DatabasePhases("ledger", maxPoolSize);
    }

    @Override
    public <T> T write(LedgerCommand command, Function<Session, T> work) {
        return phases.run(DatabasePhases.Pool.of(pool, () -> pool.flush(AgroalDataSource.FlushMode.ALL)),
                false, DatabasePhases.PHASE_BUDGET, (connection, deadline) -> {
                    connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                    DatabasePhases.refreshTimeouts(connection, deadline);
                    lockIdentities(connection, command);
                    return work.apply(new JdbcSession(connection));
                }, IllegalArgumentException.class,
                failure -> new IllegalStateException("Ledger store unavailable", failure));
    }

    private static void lockIdentities(Connection connection, LedgerCommand command) throws SQLException {
        TreeSet<Long> keys = new TreeSet<>(List.of(command.orderId(), command.sagaId(), command.commandId(),
                command.messageId(), command.resultMessageId()));
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
            for (long key : keys) {
                statement.setLong(1, key);
                statement.execute();
            }
        }
    }

    private static List<LedgerDecision> operations(Connection connection, LedgerCommand command) throws SQLException {
        List<LedgerDecision> found = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT order_id, buyer_id, saga_id, command_id, "
                + "request_fingerprint, snapshot, amount, currency, outcome, rejection_reason, decided_at, "
                + "result_schema_version, result_data "
                + "FROM ledger.ledger_operations WHERE order_id IN (?,?,?) OR saga_id IN (?,?,?) "
                + "OR command_id IN (?,?,?) ORDER BY order_id")) {
            for (int offset = 0; offset < 9; offset += 3) {
                statement.setLong(offset + 1, command.orderId());
                statement.setLong(offset + 2, command.sagaId());
                statement.setLong(offset + 3, command.commandId());
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (rows.getInt("result_schema_version") != 1) {
                        throw new SQLException("Unsupported saved ledger result version");
                    }
                    found.add(new LedgerDecision(rows.getLong("order_id"), rows.getLong("buyer_id"),
                            rows.getLong("saga_id"), rows.getLong("command_id"),
                            rows.getString("request_fingerprint"), json(rows.getString("snapshot")),
                            rows.getBigDecimal("amount"), rows.getString("currency"),
                            rows.getString("outcome"), rows.getString("rejection_reason"),
                            rows.getTimestamp("decided_at").toInstant(), json(rows.getString("result_data"))));
                }
            }
        }
        return found;
    }

    private static List<TransportBinding> bindings(Connection connection, LedgerCommand command) throws SQLException {
        List<TransportBinding> found = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT message_id, result_message_id, "
                + "order_id, command_envelope FROM ledger.ledger_result_ids "
                + "WHERE message_id IN (?,?) OR result_message_id IN (?,?) ORDER BY message_id")) {
            statement.setLong(1, command.messageId());
            statement.setLong(2, command.resultMessageId());
            statement.setLong(3, command.messageId());
            statement.setLong(4, command.resultMessageId());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    found.add(new TransportBinding(rows.getLong("message_id"), rows.getLong("result_message_id"),
                            rows.getLong("order_id"), json(rows.getString("command_envelope"))));
                }
            }
        }
        return found;
    }

    private static ObjectNode json(String value) throws SQLException {
        try {
            return (ObjectNode) LedgerCommand.canonical(JsonRequests.parse(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (RuntimeException failure) {
            throw new SQLException("Invalid saved ledger JSON", failure);
        }
    }

    private static void insertDecision(Connection connection, LedgerCommand command, LedgerDecision decision)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO ledger.ledger_operations "
                + "(order_id, buyer_id, saga_id, command_id, request_fingerprint, snapshot, amount, currency, "
                + "outcome, rejection_reason, result_schema_version, result_data, decided_at) "
                + "VALUES (?,?,?,?,?,?::jsonb,?,?,?, ?,1,?::jsonb,?)")) {
            statement.setLong(1, command.orderId());
            statement.setLong(2, command.buyerId());
            statement.setLong(3, command.sagaId());
            statement.setLong(4, command.commandId());
            statement.setString(5, command.requestFingerprint());
            statement.setString(6, command.snapshot().toString());
            statement.setBigDecimal(7, command.amount());
            statement.setString(8, command.currency());
            statement.setString(9, decision.outcome());
            statement.setString(10, decision.rejectionReason());
            statement.setString(11, decision.resultData().toString());
            statement.setTimestamp(12, Timestamp.from(decision.decidedAt()));
            statement.executeUpdate();
        }
        if ("RECORDED".equals(decision.outcome())) {
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO ledger.ledger_entries "
                    + "(order_id, amount, currency, outcome, recorded_at) VALUES (?,?,?,'RECORDED',?)")) {
                statement.setLong(1, command.orderId());
                statement.setBigDecimal(2, command.amount());
                statement.setString(3, command.currency());
                statement.setTimestamp(4, Timestamp.from(decision.decidedAt()));
                statement.executeUpdate();
            }
        }
    }

    private static void insertBinding(Connection connection, LedgerCommand command, ObjectNode envelope, Instant at)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO ledger.ledger_result_ids "
                + "(result_message_id, message_id, order_id, envelope, command_envelope, created_at) "
                + "VALUES (?,?,?,?::jsonb,?::jsonb,?)")) {
            statement.setLong(1, command.resultMessageId());
            statement.setLong(2, command.messageId());
            statement.setLong(3, command.orderId());
            statement.setString(4, envelope.toString());
            statement.setString(5, command.envelope().toString());
            statement.setTimestamp(6, Timestamp.from(at));
            statement.executeUpdate();
        }
    }

    private static void insertInbox(Connection connection, LedgerCommand command, Instant at) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO ledger.ledger_inbox "
                + "(message_id, result_message_id, envelope, received_at) VALUES (?,?,?::jsonb,?) "
                + "ON CONFLICT (message_id) DO NOTHING")) {
            statement.setLong(1, command.messageId());
            statement.setLong(2, command.resultMessageId());
            statement.setString(3, command.envelope().toString());
            statement.setTimestamp(4, Timestamp.from(at));
            statement.executeUpdate();
        }
    }

    private static void insertOutbox(Connection connection, long resultId, long attempt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO ledger.ledger_outbox "
                + "(message_id, publication_attempt) VALUES (?,?)")) {
            statement.setLong(1, resultId);
            statement.setLong(2, attempt);
            statement.executeUpdate();
        }
    }

    private static long nextAttempt(Connection connection, long resultId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE ledger.ledger_result_ids "
                + "SET publication_attempt = publication_attempt + 1 "
                + "WHERE result_message_id=? AND publication_attempt < 9223372036854775807 "
                + "RETURNING publication_attempt")) {
            statement.setLong(1, resultId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("Ledger publication attempt exhausted");
                }
                return rows.getLong(1);
            }
        }
    }

    private static void rearmOutbox(Connection connection, long resultId, long attempt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO ledger.ledger_outbox "
                + "(message_id, publication_attempt) VALUES (?,?) ON CONFLICT (message_id) DO UPDATE "
                + "SET publication_attempt=EXCLUDED.publication_attempt, published_at=NULL")) {
            statement.setLong(1, resultId);
            statement.setLong(2, attempt);
            statement.executeUpdate();
        }
    }

    private static void quarantine(Connection connection, LedgerCommand command, ObjectNode expected,
                                   ObjectNode received, String reason, Instant at) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO ledger.conflicting_commands "
                + "(message_id, payload_fingerprint, result_message_id, command_id, saga_id, order_id, "
                + "buyer_id, request_fingerprint, envelope, expected_identity, received_identity, reason, "
                + "first_seen_at, last_seen_at, delivery_count) "
                + "VALUES (?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?,?,?,1) "
                + "ON CONFLICT (message_id, payload_fingerprint) DO UPDATE SET "
                + "last_seen_at=GREATEST(ledger.conflicting_commands.last_seen_at, EXCLUDED.last_seen_at), "
                + "delivery_count=ledger.conflicting_commands.delivery_count+1 "
                + "WHERE ledger.conflicting_commands.delivery_count < 9223372036854775807")) {
            statement.setLong(1, command.messageId());
            statement.setString(2, command.payloadFingerprint());
            statement.setLong(3, command.resultMessageId());
            statement.setLong(4, command.commandId());
            statement.setLong(5, command.sagaId());
            statement.setLong(6, command.orderId());
            statement.setLong(7, command.buyerId());
            statement.setString(8, command.requestFingerprint());
            statement.setString(9, command.envelope().toString());
            statement.setString(10, expected.toString());
            statement.setString(11, received.toString());
            statement.setString(12, reason);
            statement.setTimestamp(13, Timestamp.from(at));
            statement.setTimestamp(14, Timestamp.from(at));
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Ledger quarantine delivery counter exhausted");
            }
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run() throws SQLException;
    }

    private static <T> T sql(SqlWork<T> work) {
        try {
            return work.run();
        } catch (SQLException failure) {
            throw new IllegalStateException("Ledger statement failed", failure);
        }
    }

    private static final class JdbcSession implements LedgerStore.Session {
        private final Connection connection;

        private JdbcSession(Connection connection) {
            this.connection = connection;
        }

        @Override
        public List<LedgerDecision> operations(LedgerCommand command) {
            return sql(() -> JdbcLedgerStore.operations(connection, command));
        }

        @Override
        public List<TransportBinding> bindings(LedgerCommand command) {
            return sql(() -> JdbcLedgerStore.bindings(connection, command));
        }

        @Override
        public void insertDecision(LedgerCommand command, LedgerDecision decision) {
            sql(() -> {
                JdbcLedgerStore.insertDecision(connection, command, decision);
                return null;
            });
        }

        @Override
        public void insertBinding(LedgerCommand command, ObjectNode resultEnvelope, Instant now) {
            sql(() -> {
                JdbcLedgerStore.insertBinding(connection, command, resultEnvelope, now);
                return null;
            });
        }

        @Override
        public void insertInbox(LedgerCommand command, Instant now) {
            sql(() -> {
                JdbcLedgerStore.insertInbox(connection, command, now);
                return null;
            });
        }

        @Override
        public void insertOutbox(long resultMessageId) {
            sql(() -> {
                JdbcLedgerStore.insertOutbox(connection, resultMessageId, 1);
                return null;
            });
        }

        @Override
        public long incrementAttempt(long resultMessageId) {
            return sql(() -> nextAttempt(connection, resultMessageId));
        }

        @Override
        public void rearmOutbox(long resultMessageId, long attempt) {
            sql(() -> {
                JdbcLedgerStore.rearmOutbox(connection, resultMessageId, attempt);
                return null;
            });
        }

        @Override
        public void quarantine(LedgerCommand command, ObjectNode expectedIdentity,
                               ObjectNode receivedIdentity, String reason, Instant now) {
            sql(() -> {
                JdbcLedgerStore.quarantine(connection, command, expectedIdentity, receivedIdentity, reason, now);
                return null;
            });
        }
    }

    @Override
    public void close() {
        phases.close();
        pool.close();
    }
}
