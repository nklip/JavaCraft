package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Constructs an earlier ordering snapshot exclusively inside a newly owned restore target. */
public final class RecoveryLossFixture {
    private RecoveryLossFixture() { }

    public record Witness(String shard, long orderId, long buyerId, long sagaId, long commandId,
                          long resultMessageId, JsonNode commandEnvelope, JsonNode resultEnvelope) { }

    /** Caller first verifies the coordinated drained backup; durable acceptance outlives transport cleanup. */
    public static List<Witness> select(OwnedRestoreTarget target, int count) throws Exception {
        if (count < 1 || count > 20) throw new IllegalArgumentException("Select between one and twenty loss witnesses");
        for (String shard : List.of("shard-a", "shard-b", "shard-c")) {
            List<Witness> result = new ArrayList<>();
            try (Connection home = target.connection(shard); Connection ledger = target.connection("ledger-db");
                 var query = home.createStatement()) {
                query.setQueryTimeout(30);
                query.setFetchSize(128);
                home.setAutoCommit(false);
                try (var rows = query.executeQuery("SELECT o.order_id,o.buyer_id,s.saga_id,s.command_id,r.result_message_id,"
                        + "r.received_envelope::text,s.ledger_result::text,r.message_id FROM ordering.orders o "
                        + "JOIN ordering.order_sagas s USING(order_id) JOIN ordering.order_result_ids r ON r.saga_id=s.saga_id "
                        + "WHERE o.status='CONFIRMED' AND s.status='CONFIRMED' AND r.received_envelope IS NOT NULL "
                        + "AND NOT EXISTS(SELECT 1 FROM ordering.orphan_results q WHERE q.order_id=o.order_id AND q.resolved_at IS NULL) "
                        + "AND NOT EXISTS(SELECT 1 FROM ordering.conflicting_results q WHERE q.order_id=o.order_id AND q.resolved_at IS NULL) "
                        + "ORDER BY o.order_id")) {
                    while (rows.next() && result.size() < count) {
                        long candidate = rows.getLong(1);
                        if (result.stream().anyMatch(w -> w.orderId() == candidate)) continue;
                        try (PreparedStatement match = ledger.prepareStatement("SELECT r.command_envelope::text,r.envelope::text "
                                + "FROM ledger.ledger_result_ids r JOIN ledger.ledger_operations o USING(order_id) "
                                + "JOIN ledger.ledger_outbox b ON b.message_id=r.result_message_id AND b.publication_attempt=r.publication_attempt "
                                + "WHERE r.order_id=? AND r.message_id=? AND r.result_message_id=? AND o.outcome='RECORDED' "
                                + "AND o.saga_id=? AND o.buyer_id=? AND o.command_id=? AND b.published_at IS NOT NULL")) {
                            match.setLong(1, rows.getLong(1)); match.setLong(2, rows.getLong(8)); match.setLong(3, rows.getLong(5));
                            match.setLong(4, rows.getLong(3)); match.setLong(5, rows.getLong(2)); match.setLong(6, rows.getLong(4));
                            try (var found = match.executeQuery()) {
                                if (!found.next()) continue;
                                JsonNode command = KindLab.parse(found.getString(1)), envelope = KindLab.parse(found.getString(2));
                                if (!envelope.equals(KindLab.parse(rows.getString(6))) || !envelope.equals(KindLab.parse(rows.getString(7)))) continue;
                                Witness witness = new Witness(shard, rows.getLong(1), rows.getLong(2), rows.getLong(3),
                                        rows.getLong(4), rows.getLong(5), command, envelope);
                                validateWitnesses(shard, List.of(witness));
                                result.add(witness);
                            }
                        }
                    }
                }
                home.rollback();
            }
            if (result.size() == count) return List.copyOf(result);
        }
        throw new IOException("The backup lacks enough same-shard confirmed witnesses with accepted and published original results");
    }

    /** Original recovery records must already be captured by the caller; catalog and ledger are never modified. */
    public static Path createOlderDump(OwnedRestoreTarget target, String shard, List<Witness> lost, Path dump) throws Exception {
        validateWitnesses(shard, lost);
        // Refuse unsafe destinations before deleting even target-only fixture records.
        if (dump == null || !dump.isAbsolute() || dump.getParent() == null || !dump.equals(dump.normalize())) {
            throw new IllegalArgumentException("Older dump requires an absolute normalized file");
        }
        OwnedRestoreTarget.privateDirectory(dump.getParent());
        if (java.nio.file.Files.exists(dump, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw new IOException("Older dump already exists");
        target.requireOrderingIdle(shard);
        try (Connection connection = target.connection(shard)) {
            connection.setAutoCommit(false);
            try {
                for (Witness witness : lost) {
                    try (PreparedStatement lock = connection.prepareStatement("SELECT o.order_id FROM ordering.orders o JOIN ordering.order_sagas s USING(order_id) "
                            + "WHERE o.order_id=? AND o.buyer_id=? AND s.saga_id=? AND s.command_id=? AND o.status='CONFIRMED' FOR UPDATE OF o,s")) {
                        lock.setLong(1,witness.orderId()); lock.setLong(2,witness.buyerId()); lock.setLong(3,witness.sagaId()); lock.setLong(4,witness.commandId());
                        try (var rows = lock.executeQuery()) { if (!rows.next()) throw new IOException("Loss witness no longer matches the owned target"); }
                    }
                    execute(connection,"DELETE FROM ordering.order_inbox WHERE saga_id=?",witness.sagaId());
                    execute(connection,"DELETE FROM ordering.order_outbox WHERE saga_id=?",witness.sagaId());
                    execute(connection,"DELETE FROM ordering.order_result_ids WHERE saga_id=?",witness.sagaId());
                    execute(connection,"DELETE FROM ordering.profit_intents WHERE order_id=?",witness.orderId());
                    execute(connection,"DELETE FROM ordering.order_items WHERE order_id=?",witness.orderId());
                    execute(connection,"DELETE FROM ordering.order_sagas WHERE order_id=?",witness.orderId());
                    if (execute(connection,"DELETE FROM ordering.orders WHERE order_id=?",witness.orderId()) != 1) throw new IOException("Loss witness deletion was incomplete");
                }
                connection.commit();
            } catch (Exception failure) { connection.rollback(); throw failure; }
        }
        target.dumpOrdering(shard, dump);
        return dump;
    }

    public static void restoreOlderDump(OwnedRestoreTarget target, String shard, Path dump) throws Exception {
        target.restoreOrdering(shard, dump);
    }

    /** A separately retained witness exercises mismatch detection without repairing or overwriting it. */
    public static void mismatchFingerprint(OwnedRestoreTarget target, Witness witness) throws Exception {
        validateWitnesses(witness.shard(), List.of(witness));
        target.requireOrderingIdle(witness.shard());
        try (Connection connection = target.connection(witness.shard()); PreparedStatement update = connection.prepareStatement(
                "UPDATE ordering.orders SET request_fingerprint=? WHERE order_id=? AND buyer_id=? AND request_fingerprint=? AND status='CONFIRMED'")) {
            String original = witness.commandEnvelope().path("requestFingerprint").asText();
            update.setString(1, "restore-loss-mismatch:" + original); update.setLong(2,witness.orderId());
            update.setLong(3,witness.buyerId()); update.setString(4,original);
            if (update.executeUpdate() != 1) throw new IOException("Mismatch witness no longer has its original fingerprint");
        }
    }

    static void validateWitnesses(String shard, List<Witness> witnesses) {
        OwnedRestoreTarget.validateOrderingSource(shard);
        if (witnesses == null || witnesses.isEmpty()) throw new IllegalArgumentException("Loss requires captured witnesses");
        Set<Long> orders = new HashSet<>();
        for (Witness witness : witnesses) {
            if (witness == null || !shard.equals(witness.shard()) || witness.orderId() <= 0 || witness.buyerId() <= 0
                    || witness.sagaId() <= 0 || witness.commandId() <= 0 || witness.resultMessageId() <= 0
                    || witness.commandId() == witness.resultMessageId() || !orders.add(witness.orderId())) {
                throw new IllegalArgumentException("Loss witness identity is invalid or duplicated");
            }
            JsonNode command = witness.commandEnvelope(), result = witness.resultEnvelope();
            if (command == null || result == null || !command.isObject() || !result.isObject()) throw new IllegalArgumentException("Loss witness lacks original envelopes");
            for (JsonNode envelope : List.of(command,result)) {
                if (!envelope.path("orderId").asText().equals(Long.toString(witness.orderId()))
                        || !envelope.path("buyerId").asText().equals(Long.toString(witness.buyerId()))
                        || !envelope.path("sagaId").asText().equals(Long.toString(witness.sagaId()))
                        || envelope.path("requestFingerprint").asText().isBlank()) throw new IllegalArgumentException("Loss witness envelope identity differs");
            }
            if (!command.path("messageId").asText().matches("[1-9][0-9]*")
                    || !command.path("commandId").asText().equals(Long.toString(witness.commandId()))
                    || !command.path("resultMessageId").asText().equals(Long.toString(witness.resultMessageId()))
                    || !result.path("messageId").asText().equals(Long.toString(witness.resultMessageId()))
                    || !result.path("commandId").asText().equals(Long.toString(witness.commandId()))
                    || !result.path("outcome").asText().equals("RECORDED")
                    || !command.path("requestFingerprint").equals(result.path("requestFingerprint"))) {
                throw new IllegalArgumentException("Loss witness does not retain original accepted transport identities");
            }
        }
    }

    private static int execute(Connection connection, String sql, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(30); statement.setLong(1,id); return statement.executeUpdate();
        }
    }
}
