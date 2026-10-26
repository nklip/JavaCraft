package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nklip.javacraft.shardshop.common.Sha256;

import java.io.BufferedOutputStream;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;

/** Privileged operator evidence and quarantine. The caller must freeze every application writer. */
final class RecoveryAudit {
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final int RECORD_LIMIT = 1024 * 1024;
    private static final String DECISION = "SELECT order_id,buyer_id,saga_id,command_id,request_fingerprint,snapshot::text,"
            + "amount,currency,outcome,rejection_reason,result_schema_version,result_data::text,decided_at FROM ledger.ledger_operations";
    private RecoveryAudit() { }

    record Report(Path directory, long decisions, long matchingOrders, long quarantined, long unresolved,
                  long orphanReservations) { }

    static Path exportLedger(RecoveryAccess access, Path directory) throws Exception {
        BackupArtifacts artifacts = BackupArtifacts.create(directory);
        long count = 0;
        try (Connection ledger = access.open("ledger-db");
             OutputStream output = new BufferedOutputStream(Files.newOutputStream(artifacts.newFile("ledger.jsonl")))) {
            writable(ledger);
            ledger.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            ledger.setReadOnly(true);
            ledger.setAutoCommit(false);
            try (Statement query = ledger.createStatement()) {
                query.setFetchSize(128);
                query.setQueryTimeout(30);
                try (ResultSet rows = query.executeQuery(DECISION + " ORDER BY order_id")) {
                    while (rows.next()) {
                        ObjectNode decision = decision(rows);
                        decision.set("bindings", bindings(ledger, rows.getLong(1)));
                        validateEvidence(decision);
                        line(output, decision);
                        count++;
                    }
                }
            }
            ObjectNode metadata = JSON.createObjectNode().put("exportType", "ledger-recovery-v1").put("decisionCount", count);
            metadata.put("systemIdentifier", scalar(ledger, "SELECT system_identifier FROM pg_control_system()"));
            metadata.put("databaseOid", scalar(ledger, "SELECT oid FROM pg_database WHERE datname=current_database()"));
            ledger.rollback();
            output.close();
            artifacts.seal(metadata);
            return directory;
        } catch (Exception failure) {
            throw safe("Ledger recovery export failed", failure);
        }
    }

    static Report audit(RecoveryAccess access, Path exportDirectory) throws Exception {
        require(access.shards().equals(List.of("shard-a", "shard-b", "shard-c")), "Recovery audit requires the canonical shard inventory");
        BackupArtifacts evidence = BackupArtifacts.open(exportDirectory);
        ObjectNode manifest = evidence.manifest();
        require(manifest.path("exportType").asText().equals("ledger-recovery-v1")
                && manifest.path("decisionCount").isIntegralNumber() && manifest.path("decisionCount").canConvertToLong() && manifest.path("decisionCount").asLong() >= 0,
                "Ledger recovery export metadata is invalid");
        Path records = evidence.file("ledger.jsonl");
        try (AuditConnections sessions = new AuditConnections(access)) {
            // Validate every byte and immutable ledger identity before making any quarantine mutation.
            try (InputStream input = new BufferedInputStream(Files.newInputStream(records))) {
                Connection ledger = sessions.ledger();
                long count = 0;
                long previous = 0;
                for (ObjectNode record; (record = readLine(input)) != null;) {
                    validateEvidence(record);
                    long id = id(record, "orderId");
                    require(id > previous, "Ledger recovery records are duplicated or unordered");
                    previous = id;
                    ObjectNode current = loadDecision(ledger, id);
                    require(current != null && samePermanentEvidence(record, current), "Ledger recovery export differs from current permanent evidence");
                    count++;
                }
                require(count == manifest.path("decisionCount").asLong()
                        && count == Long.parseLong(scalar(ledger, "SELECT count(*) FROM ledger.ledger_operations")),
                        "Ledger recovery export is incomplete");
            } catch (Exception failure) { throw safe("Ledger recovery evidence validation failed", failure); }
            Path directory = exportDirectory.resolveSibling(exportDirectory.getFileName() + "-audit-" + UUID.randomUUID());
            BackupArtifacts findings = BackupArtifacts.create(directory);
            long decisions = 0, matching = 0, quarantined = 0, unresolved = 0, orphanReservations = 0;
            try (InputStream input = new BufferedInputStream(Files.newInputStream(records));
                 OutputStream output = new BufferedOutputStream(Files.newOutputStream(findings.newFile("findings.jsonl")))) {
                for (ObjectNode record; (record = readLine(input)) != null;) {
                    decisions++;
                    long order = id(record, "orderId");
                    String expected = home(access.shards(), id(record, "buyerId"));
                    List<Located> located = orders(sessions, order);
                    String reason;
                    if (located.isEmpty()) reason = "ORDER_ABSENT";
                    else if (located.size() != 1) reason = "DUPLICATE_ORDER_ID";
                    else if (!located.getFirst().shard().equals(expected)) reason = "MISPLACED_ORDER";
                    else reason = mismatch(record, located.getFirst().state());
                    if (reason == null) { matching++; continue; }
                    List<String> targets = new ArrayList<>();
                    targets.add(expected);
                    for (Located item : located) if (!targets.contains(item.shard())) targets.add(item.shard());
                    for (String shard : targets) quarantine(sessions, shard, record, reason);
                    line(output, JSON.createObjectNode().put("type", "LEDGER_ORDER_DISAGREEMENT")
                            .put("orderId", Long.toString(order)).put("expectedShard", expected).put("reason", reason)
                            .put("state", "UNRESOLVED"));
                    quarantined++;
                    unresolved++;
                }
                // Inspect every order, including orders absent from the ledger. Pending stock and stock rejection
                // legitimately have no ledger decision; a ledger-stage or confirmed order does not.
                Connection ledger = sessions.ledger();
                for (String shard : access.shards()) {
                    Connection source = sessions.cursor(shard);
                    source.setAutoCommit(false);
                    try (Statement query = source.createStatement()) {
                        query.setFetchSize(128); query.setQueryTimeout(30);
                        try (ResultSet rows = query.executeQuery("SELECT order_id FROM ordering.orders ORDER BY order_id")) {
                            while (rows.next()) {
                                long id = rows.getLong(1);
                                if (loadDecision(ledger, id) != null) continue;
                                List<Located> located = orders(sessions, id);
                                JsonNode state = located.stream().filter(item -> item.shard().equals(shard)).findFirst().orElseThrow().state();
                                String status = state.path("status").asText();
                                if (located.size() == 1 && (status.equals("PENDING_STOCK")
                                    || status.equals("CANCELLED") && state.path("reason").asText().equals("OUT_OF_STOCK"))) continue;
                                line(output, JSON.createObjectNode().put("type", "ORDER_WITHOUT_LEDGER_EVIDENCE")
                                    .put("orderId", Long.toString(id)).put("shard", shard).put("state", "UNRESOLVED"));
                                unresolved++;
                            }
                        }
                    }
                    source.rollback();
                }
                for (String shard : access.shards()) {
                    Connection catalog = sessions.cursor(shard);
                    catalog.setAutoCommit(false);
                    try (Statement query = catalog.createStatement()) {
                        query.setFetchSize(128); query.setQueryTimeout(30);
                        try (ResultSet rows = query.executeQuery("SELECT order_id,product_id,quantity,released_at IS NOT NULL "
                            + "FROM catalog.stock_reservations ORDER BY order_id,product_id")) {
                            while (rows.next()) {
                                if (!orders(sessions, rows.getLong(1)).isEmpty()) continue;
                                line(output, JSON.createObjectNode().put("type", "ORPHAN_STOCK_RESERVATION")
                                    .put("shard", shard).put("orderId", Long.toString(rows.getLong(1)))
                                    .put("productId", Long.toString(rows.getLong(2))).put("quantity", rows.getInt(3))
                                    .put("released", rows.getBoolean(4)).put("state", "UNRESOLVED"));
                                orphanReservations++;
                            }
                        }
                    }
                    catalog.rollback();
                }
                output.close();
                findings.seal(JSON.createObjectNode().put("auditType", "ledger-order-recovery-v1").put("decisions", decisions)
                        .put("matchingOrders", matching).put("quarantined", quarantined).put("unresolved", unresolved)
                        .put("orphanReservations", orphanReservations));
                return new Report(directory, decisions, matching, quarantined, unresolved, orphanReservations);
            } catch (Exception failure) { throw safe("Recovery audit failed; preserve partial findings and existing quarantine", failure); }
        }
    }

    /** One frozen-target audit owns at most ten physical sessions, with transaction roles kept separate. */
    private static final class AuditConnections implements AutoCloseable {
        private final RecoveryAccess access;
        private final Map<String, Connection> connections = new LinkedHashMap<>();
        AuditConnections(RecoveryAccess access) { this.access = access; }
        Connection ledger() throws Exception { return get("ledger", "ledger-db"); }
        Connection lookup(String shard) throws Exception { return get("lookup:" + shard, shard); }
        Connection cursor(String shard) throws Exception { return get("cursor:" + shard, shard); }
        Connection writer(String shard) throws Exception { return get("writer:" + shard, shard); }
        private Connection get(String role, String database) throws Exception {
            Connection existing = connections.get(role);
            if (existing != null) return existing;
            Connection connection = access.open(database);
            // Register ownership before readiness SQL, so failed initialization still closes this session.
            connections.put(role, connection);
            writable(connection);
            return connection;
        }
        @Override public void close() throws SQLException {
            SQLException failure = null;
            for (Connection connection : connections.values()) {
                try { if (!connection.getAutoCommit()) connection.rollback(); }
                catch (SQLException problem) { if (failure == null) failure = problem; else failure.addSuppressed(problem); }
                try { connection.close(); }
                catch (SQLException problem) { if (failure == null) failure = problem; else failure.addSuppressed(problem); }
            }
            if (failure != null) throw failure;
        }
    }

    private record Located(String shard, ObjectNode state) { }

    private static List<Located> orders(AuditConnections sessions, long order) throws Exception {
        List<Located> result = new ArrayList<>();
        for (String shard : sessions.access.shards()) {
            Connection source = sessions.lookup(shard);
            try (PreparedStatement query = source.prepareStatement("SELECT o.buyer_id,s.saga_id,s.command_id,o.request_fingerprint,"
                    + "o.currency,o.total_amount,o.status,s.status,s.cancellation_reason,s.ledger_result::text,"
                    + "EXISTS(SELECT 1 FROM ordering.order_allocations a WHERE a.order_id=o.order_id AND a.buyer_id=o.buyer_id) "
                    + "FROM ordering.orders o LEFT JOIN ordering.order_sagas s USING(order_id) WHERE o.order_id=?")) {
                query.setQueryTimeout(15); query.setLong(1, order);
                try (ResultSet rows = query.executeQuery()) {
                    if (!rows.next()) continue;
                    ObjectNode state = JSON.createObjectNode().put("orderId", Long.toString(order))
                            .put("buyerId", Long.toString(rows.getLong(1))).put("sagaId", Long.toString(rows.getLong(2)))
                            .put("commandId", Long.toString(rows.getLong(3))).put("requestFingerprint", rows.getString(4))
                            .put("currency", rows.getString(5)).put("amount", rows.getBigDecimal(6).toPlainString())
                            .put("status", rows.getString(7)).put("sagaStatus", rows.getString(8))
                            .put("reason", rows.getString(9)).put("allocated", rows.getBoolean(11));
                    state.set("result", rows.getString(10) == null ? JSON.nullNode() : JSON.readTree(rows.getString(10)));
                    state.set("items", items(source, order));
                    result.add(new Located(shard, state));
                }
            }
        }
        return result;
    }

    static String mismatch(JsonNode evidence, JsonNode state) throws IOException {
        if (state == null || state.isNull()) return "ORDER_ABSENT";
        for (String key : List.of("orderId", "buyerId", "sagaId", "commandId", "requestFingerprint", "currency"))
            if (!evidence.path(key).equals(state.path(key))) return "IDENTITY_MISMATCH";
        if (!state.path("allocated").asBoolean()) return "ALLOCATION_ABSENT";
        if (money(evidence.path("amount")).compareTo(money(state.path("amount"))) != 0) return "SNAPSHOT_MISMATCH";
        if (!sameItems(evidence.path("snapshot").path("items"), state.path("items"))) return "SNAPSHOT_MISMATCH";
        String status = state.path("status").asText();
        if (!status.equals(state.path("sagaStatus").asText())) return "ORDER_SAGA_STATE_MISMATCH";
        if (status.equals("PENDING_LEDGER")) return state.path("result").isNull() ? null : "SAVED_OUTCOME_MISMATCH";
        if (!(evidence.path("outcome").asText().equals("RECORDED") ? "CONFIRMED" : "CANCELLED").equals(status)) return "OUTCOME_MISMATCH";
        if (!evidence.path("reason").equals(state.path("reason"))) return "OUTCOME_MISMATCH";
        JsonNode accepted = state.path("result").deepCopy();
        if (!(accepted instanceof ObjectNode object)) return "SAVED_OUTCOME_MISMATCH";
        object.remove(List.of("messageId", "occurredAt"));
        return canonical(evidence.path("result")).equals(canonical(object)) ? null : "SAVED_OUTCOME_MISMATCH";
    }

    private static boolean sameItems(JsonNode expected, JsonNode actual) throws IOException {
        if (!expected.isArray() || !actual.isArray() || expected.isEmpty() || expected.size() != actual.size()) return false;
        for (int index = 0; index < expected.size(); index++) {
            JsonNode left = expected.get(index), right = actual.get(index);
            for (String key : List.of("itemPosition", "sellerId", "productId", "quantity", "productName", "currency"))
                if (!left.path(key).equals(right.path(key))) return false;
            if (money(left.path("unitPrice")).compareTo(money(right.path("unitPrice"))) != 0) return false;
        }
        return true;
    }

    private static ArrayNode items(Connection source, long order) throws Exception {
        ArrayNode items = JSON.createArrayNode();
        try (PreparedStatement query = source.prepareStatement("SELECT item_position,seller_id,product_id,quantity,product_name,unit_price,currency "
                + "FROM ordering.order_items WHERE order_id=? ORDER BY item_position")) {
            query.setQueryTimeout(15); query.setLong(1, order);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) items.addObject().put("itemPosition", rows.getInt(1)).put("sellerId", Long.toString(rows.getLong(2)))
                        .put("productId", Long.toString(rows.getLong(3))).put("quantity", rows.getInt(4))
                        .put("productName", rows.getString(5)).put("unitPrice", rows.getBigDecimal(6).toPlainString()).put("currency", rows.getString(7));
            }
        }
        return items;
    }

    private static void quarantine(AuditConnections sessions, String shard, JsonNode evidence, String reason) throws Exception {
        JsonNode envelope = evidence.path("bindings").get(0).path("envelope");
        Connection source = sessions.writer(shard);
        source.setAutoCommit(false);
        try {
            try (Statement settings = source.createStatement()) {
                settings.execute("SET LOCAL statement_timeout='15s'; SET LOCAL lock_timeout='5s'; SET LOCAL idle_in_transaction_session_timeout='20s'");
            }
            try (PreparedStatement lock = source.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                lock.setQueryTimeout(10); lock.setLong(1, Sha256.unsignedDigest("order\0\0" + id(evidence, "orderId")).longValue()); lock.execute();
            }
            try (PreparedStatement insert = source.prepareStatement("INSERT INTO ordering.orphan_results "
                    + "(message_id,payload_fingerprint,command_id,saga_id,order_id,buyer_id,request_fingerprint,outcome,envelope,reason) "
                    + "VALUES (?,?,?,?,?,?,?,?,?::jsonb,?) ON CONFLICT(message_id,payload_fingerprint) DO UPDATE SET "
                    + "resolved_at=NULL,resolution=NULL,reason=EXCLUDED.reason,last_seen_at=GREATEST(clock_timestamp(),ordering.orphan_results.last_seen_at) "
                    + "WHERE ordering.orphan_results.resolved_at IS NOT NULL")) {
                insert.setQueryTimeout(15);
                insert.setLong(1, id(envelope, "messageId"));
                insert.setString(2, String.format("%064x", Sha256.unsignedDigest(canonical(envelope).toString())));
                insert.setLong(3, id(evidence, "commandId")); insert.setLong(4, id(evidence, "sagaId"));
                insert.setLong(5, id(evidence, "orderId")); insert.setLong(6, id(evidence, "buyerId"));
                insert.setString(7, evidence.path("requestFingerprint").asText()); insert.setString(8, evidence.path("outcome").asText());
                insert.setString(9, canonical(envelope).toString()); insert.setString(10, "AUDIT_" + reason); insert.executeUpdate();
            }
            source.commit();
        } catch (Exception failure) { source.rollback(); throw failure; }
    }

    static void validateEvidence(JsonNode record) throws IOException {
        require(record.isObject(), "Ledger recovery record is malformed");
        for (String key : List.of("orderId", "buyerId", "sagaId", "commandId")) id(record, key);
        require(record.path("requestFingerprint").isTextual() && record.path("requestFingerprint").asText().matches("[0-9a-f]{64}"), "Ledger fingerprint is invalid");
        require(record.path("snapshot").isObject() && record.path("snapshot").path("items").isArray()
                && !record.path("snapshot").path("items").isEmpty() && record.path("result").isObject(), "Ledger recovery snapshots are absent");
        require(record.path("currency").asText().matches("[A-Z]{3}")
                && record.path("currency").equals(record.path("snapshot").path("currency")), "Ledger snapshot currency is inconsistent");
        require(money(record.path("amount")).compareTo(money(record.path("snapshot").path("amount"))) == 0,
                "Ledger snapshot amount is inconsistent");
        require(money(record.path("amount")).compareTo(money(record.path("result").path("amount"))) == 0,
                "Ledger result amount is inconsistent");
        require(record.path("resultSchemaVersion").isInt()
                && record.path("resultSchemaVersion").equals(record.path("result").path("schemaVersion")), "Ledger result schema is inconsistent");
        timestamp(record.path("decidedAt"));
        require(record.path("decidedAt").equals(record.path("result").path("decidedAt")), "Ledger result decision time is inconsistent");
        require(record.path("outcome").asText().equals("RECORDED") || record.path("outcome").asText().equals("REJECTED"), "Ledger recovery outcome is invalid");
        if (record.path("outcome").asText().equals("RECORDED")) {
            require(record.path("reason").isNull() && !record.path("result").has("rejectionReason"), "Recorded ledger decision has a rejection reason");
        } else {
            require(record.path("reason").isTextual() && !record.path("reason").asText().isBlank()
                    && record.path("reason").equals(record.path("result").path("rejectionReason")), "Ledger rejection reason is inconsistent");
        }
        require(record.path("bindings").isArray() && !record.path("bindings").isEmpty(), "Original ledger result bindings are absent");
        for (JsonNode binding : record.path("bindings")) {
            JsonNode envelope = binding.path("envelope");
            JsonNode command = binding.path("commandEnvelope");
            require(command.isObject(), "Original command envelope is absent");
            require(id(binding, "messageId") == id(command, "messageId")
                    && id(binding, "resultMessageId") == id(command, "resultMessageId"), "Original command binding disagrees");
            timestamp(command.path("occurredAt"));
            for (String key : List.of("orderId", "buyerId", "sagaId", "commandId", "requestFingerprint"))
                require(record.path(key).equals(command.path(key)), "Original command business identity is inconsistent");
            require(canonical(record.path("snapshot")).equals(canonical(command.path("snapshot"))), "Original command snapshot is inconsistent");
            require(id(binding, "messageId") != id(binding, "resultMessageId"), "Ledger transport identities collide");
            timestamp(binding.path("createdAt"));
            timestamp(envelope.path("occurredAt"));
            require(binding.path("publicationAttempt").canConvertToLong() && binding.path("publicationAttempt").asLong() > 0,
                    "Ledger result publication attempt is invalid");
            require(id(binding, "resultMessageId") == id(envelope, "messageId"), "Ledger result binding disagrees with original envelope");
            for (String key : List.of("orderId", "buyerId", "sagaId", "commandId", "requestFingerprint", "currency", "outcome"))
                require(record.path(key).equals(envelope.path(key)), "Ledger result envelope identity is inconsistent");
            JsonNode data = envelope.deepCopy();
            require(data instanceof ObjectNode, "Ledger result envelope is malformed");
            ((ObjectNode) data).remove(List.of("messageId", "occurredAt"));
            require(canonical(record.path("result")).equals(canonical(data)), "Ledger result envelope changed its permanent decision");
        }
    }

    static boolean samePermanentEvidence(JsonNode expected, JsonNode current) throws IOException {
        if (!expected.isObject() || !current.isObject() || !expected.path("bindings").isArray()
                || !current.path("bindings").isArray() || expected.path("bindings").size() != current.path("bindings").size()) return false;
        ObjectNode before = expected.deepCopy(), after = current.deepCopy();
        for (int i = 0; i < before.path("bindings").size(); i++) {
            JsonNode left = before.path("bindings").get(i), right = after.path("bindings").get(i);
            if (!left.isObject() || !right.isObject() || !left.path("publicationAttempt").isIntegralNumber()
                    || !right.path("publicationAttempt").isIntegralNumber() || !left.path("publicationAttempt").canConvertToLong()
                    || !right.path("publicationAttempt").canConvertToLong() || left.path("publicationAttempt").asLong() <= 0
                    || right.path("publicationAttempt").asLong() < left.path("publicationAttempt").asLong()) return false;
            ((ObjectNode) left).remove("publicationAttempt");
            ((ObjectNode) right).remove("publicationAttempt");
        }
        return canonical(before).equals(canonical(after));
    }

    private static ObjectNode decision(ResultSet rows) throws Exception {
        ObjectNode result = JSON.createObjectNode().put("orderId", Long.toString(rows.getLong(1)))
                .put("buyerId", Long.toString(rows.getLong(2))).put("sagaId", Long.toString(rows.getLong(3)))
                .put("commandId", Long.toString(rows.getLong(4))).put("requestFingerprint", rows.getString(5));
        result.set("snapshot", JSON.readTree(rows.getString(6)));
        result.put("amount", rows.getBigDecimal(7).toPlainString()).put("currency", rows.getString(8))
                .put("outcome", rows.getString(9)).put("reason", rows.getString(10)).put("resultSchemaVersion", rows.getInt(11));
        result.set("result", JSON.readTree(rows.getString(12)));
        result.put("decidedAt", rows.getTimestamp(13).toInstant().toString());
        return result;
    }

    private static ObjectNode loadDecision(Connection ledger, long id) throws Exception {
        try (PreparedStatement query = ledger.prepareStatement(DECISION + " WHERE order_id=?")) {
            query.setQueryTimeout(15); query.setLong(1, id);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) return null;
                ObjectNode decision = decision(rows);
                decision.set("bindings", bindings(ledger, id));
                return decision;
            }
        }
    }

    private static ArrayNode bindings(Connection ledger, long id) throws Exception {
        ArrayNode bindings = JSON.createArrayNode();
        int bytes = 0;
        try (PreparedStatement query = ledger.prepareStatement("SELECT message_id,result_message_id,envelope::text,created_at,publication_attempt,command_envelope::text "
                + "FROM ledger.ledger_result_ids WHERE order_id=? ORDER BY result_message_id")) {
            query.setQueryTimeout(15); query.setLong(1, id);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    ObjectNode binding = bindings.addObject().put("messageId", Long.toString(rows.getLong(1)))
                            .put("resultMessageId", Long.toString(rows.getLong(2))).put("createdAt", rows.getTimestamp(4).toInstant().toString())
                            .put("publicationAttempt", rows.getLong(5));
                    String envelope = rows.getString(3);
                    require(envelope != null && envelope.length() <= RECORD_LIMIT, "Original result envelope exceeds its bound");
                    binding.set("envelope", JSON.readTree(envelope));
                    String command = rows.getString(6);
                    require(command != null && command.length() <= RECORD_LIMIT, "Original command envelope is absent or exceeds its bound");
                    binding.set("commandEnvelope", JSON.readTree(command));
                    bytes += JSON.writeValueAsBytes(binding).length;
                    require(bytes <= RECORD_LIMIT, "Original result bindings exceed their record bound");
                }
            }
        }
        return bindings;
    }

    static JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = JSON.createObjectNode();
            node.properties().stream().sorted(Comparator.comparing(java.util.Map.Entry::getKey))
                    .forEach(entry -> sorted.set(entry.getKey(), canonical(entry.getValue())));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode values = JSON.createArrayNode(); node.forEach(value -> values.add(canonical(value))); return values;
        }
        return node.deepCopy();
    }

    private static String home(List<String> shards, long buyer) {
        return shards.get(Sha256.unsignedDigest(Long.toString(buyer)).mod(BigInteger.valueOf(shards.size())).intValue());
    }

    private static void writable(Connection connection) throws Exception {
        require("false|off".equals(scalar(connection, "SELECT pg_is_in_recovery()::text||'|'||current_setting('transaction_read_only')")),
                "Recovery audit requires writable database primaries");
        try (Statement query = connection.createStatement()) { query.execute("SET timezone='UTC'"); }
    }

    private static String scalar(Connection connection, String sql) throws Exception {
        try (Statement query = connection.createStatement()) {
            query.setQueryTimeout(15);
            try (ResultSet rows = query.executeQuery(sql)) { require(rows.next(), "Recovery query returned no observation"); return rows.getString(1); }
        }
    }

    private static BigDecimal money(JsonNode node) throws IOException {
        require(node.isTextual() && node.asText().matches("(?:0|[1-9][0-9]{0,16})\\.[0-9]{2}"), "Recovery money observation is malformed");
        return new BigDecimal(node.asText());
    }

    private static void timestamp(JsonNode node) throws IOException {
        require(node.isTextual(), "Original ledger timestamp is missing");
        try { java.time.Instant.parse(node.asText()); }
        catch (java.time.format.DateTimeParseException invalid) { throw new IOException("Original ledger timestamp is malformed"); }
    }

    private static long id(JsonNode node, String key) throws IOException {
        String value = node.path(key).asText();
        require(node.path(key).isTextual() && value.matches("[1-9][0-9]{0,18}"), "Recovery identity is malformed");
        try { return Long.parseLong(value); } catch (NumberFormatException invalid) { throw new IOException("Recovery identity exceeds BIGINT"); }
    }

    private static void line(OutputStream output, JsonNode record) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(record);
        require(bytes.length <= RECORD_LIMIT, "Recovery evidence record exceeds its size bound");
        output.write(bytes); output.write('\n');
    }

    static ObjectNode readLine(InputStream input) throws IOException {
        var bytes = new java.io.ByteArrayOutputStream();
        for (int value; (value = input.read()) != -1;) {
            if (value == '\n') {
                require(bytes.size() > 0, "Recovery evidence contains an empty record");
                JsonNode record;
                try { record = JSON.readTree(bytes.toByteArray()); } catch (IOException invalid) { throw new IOException("Recovery evidence record is malformed"); }
                require(record instanceof ObjectNode, "Recovery evidence record is malformed");
                return (ObjectNode) record;
            }
            require(bytes.size() < RECORD_LIMIT, "Recovery evidence record exceeds its size bound");
            bytes.write(value);
        }
        require(bytes.size() == 0, "Recovery evidence ends with a truncated record");
        return null;
    }

    private static Exception safe(String message, Exception failure) {
        if (failure instanceof InterruptedException) { Thread.currentThread().interrupt(); return failure; }
        return new IOException(message);
    }

    private static void require(boolean condition, String message) throws IOException { if (!condition) throw new IOException(message); }
}
