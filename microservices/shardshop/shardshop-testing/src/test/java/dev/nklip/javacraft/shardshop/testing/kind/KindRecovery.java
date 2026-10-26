package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.sql.Connection;
import java.security.MessageDigest;
import java.security.DigestOutputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.postgresql.PGConnection;

/** Explicit, privileged recovery on fresh isolated targets; source databases are never modified. */
public final class KindRecovery {
    private static final ObjectMapper JSON = new ObjectMapper();

    public void audit() throws Exception { operate(false); }
    public void repair() throws Exception { operate(true); }

    /** Native schema round-trip regression, independent of application and broker qualification. */
    public void schema() throws Exception {
        Inputs inputs = inputs(false);
        BackupArtifacts output = BackupArtifacts.create(inputs.output());
        ObjectNode result = provenance(inputs).put("operation", "ordering-schema-roundtrip");
        try (OwnedRestoreTarget target = restored(inputs, false)) {
            String shard = KindBackupRestore.SHARDS.getFirst();
            ObjectNode before = orderingMetadata(target, shard);
            require(!before.path("publications").isEmpty(), "Schema regression requires original ordering publication membership");
            Path dump = inputs.output().resolve("original-ordering.dump");
            target.dumpOrdering(shard, dump);
            for (int attempt = 0; attempt < 2; attempt++) {
                target.restoreOrdering(shard, dump);
                require(before.equals(orderingMetadata(target, shard)), "Ordering namespace ownership, ACL or publication membership changed during schema restore");
                for (JsonNode database : inputs.orders().manifest().path("databases"))
                    KindBackupRestore.verifyFingerprint(target, database);
            }
            result.set("orderingMetadata", before);
            result.put("restores", 2).put("allDatabaseFingerprintsPreserved", true);
        }
        output.writeJson("result.json", result.put("passed", true).put("ownedTargetRemoved", true)
                .put("completedAt", Instant.now().toString()));
        verifyInputs(inputs);
        System.out.println("Repeated isolated ordering schema restore preserved namespace security, publication membership and all database fingerprints; owned target removed.");
    }

    private static ObjectNode orderingMetadata(OwnedRestoreTarget target, String shard) throws Exception {
        ObjectNode metadata = JSON.createObjectNode();
        var privileges = metadata.putArray("privileges");
        var publications = metadata.putArray("publications");
        try (Connection connection = target.connection(shard)) {
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "SELECT pg_get_userbyid(n.nspowner),pg_get_userbyid(a.grantor),"
                            + "CASE WHEN a.grantee=0 THEN 'PUBLIC' ELSE pg_get_userbyid(a.grantee) END,a.privilege_type,a.is_grantable "
                            + "FROM pg_namespace n LEFT JOIN LATERAL aclexplode(coalesce(n.nspacl,acldefault('n',n.nspowner))) a ON true "
                            + "WHERE n.nspname='ordering' ORDER BY 2,3,4,5")) {
                boolean found = false;
                while (rows.next()) {
                    found = true;
                    metadata.put("owner", rows.getString(1));
                    if (rows.getString(4) != null) privileges.addObject().put("grantor", rows.getString(2))
                            .put("grantee", rows.getString(3)).put("privilege", rows.getString(4)).put("grantable", rows.getBoolean(5));
                }
                require(found && !metadata.path("owner").asText().isBlank(), "Ordering namespace is absent after native restore");
            }
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "SELECT p.pubname,p.pubinsert,p.pubupdate,p.pubdelete,p.pubtruncate,p.pubviaroot "
                            + "FROM pg_publication p JOIN pg_publication_tables t ON t.pubname=p.pubname "
                            + "WHERE t.schemaname='ordering' AND t.tablename='order_outbox' ORDER BY p.pubname")) {
                while (rows.next()) publications.addObject().put("name", rows.getString(1)).put("insert", rows.getBoolean(2))
                        .put("update", rows.getBoolean(3)).put("delete", rows.getBoolean(4)).put("truncate", rows.getBoolean(5))
                        .put("viaRoot", rows.getBoolean(6));
            }
        }
        return metadata;
    }

    /** Export verified original rows before a later recovery, without reconstructing them from ledger data. */
    public void records() throws Exception {
        Inputs inputs = inputs(false);
        long order = selectedOrder();
        BackupArtifacts output = BackupArtifacts.create(inputs.output());
        ObjectNode result = provenance(inputs).put("operation", "original-record-export");
        try (OwnedRestoreTarget target = restored(inputs, false)) {
            RecoveryRecords.Saved saved = RecoveryRecords.capture(RecoveryAccess.owned(target), inputs.output().resolve("original-record"), order);
            result.put("orderId", Long.toString(saved.orderId())).put("recordSha256", saved.sha256());
        }
        output.writeJson("result.json", result.put("ownedTargetRemoved", true).put("completedAt", Instant.now().toString()));
        verifyInputs(inputs);
    }

    private void operate(boolean repair) throws Exception {
        Inputs inputs = inputs(true);
        long order = repair ? selectedOrder() : 0;
        Path original = repair ? path("shardshop.recovery.record") : null;
        if (repair) {
            separate(inputs.output(), original);
            RecoveryRecords.load(original); // Reject corrupt evidence before any target creation.
        }
        BackupArtifacts output = BackupArtifacts.create(inputs.output());
        ObjectNode result = provenance(inputs).put("operation", repair ? "selected-order-repair" : "ledger-audit");
        try (OwnedRestoreTarget target = restored(inputs, true)) {
            RecoveryAccess access = RecoveryAccess.owned(target);
            Path ledger = RecoveryAudit.exportLedger(access, inputs.output().resolve("ledger-export"));
            result.set("auditBefore", JSON.valueToTree(RecoveryAudit.audit(access, ledger)));
            if (repair) {
                result.set("repair", JSON.valueToTree(RecoveryRepair.repair(access, original, order)));
                result.set("auditAfter", JSON.valueToTree(RecoveryAudit.audit(access, ledger)));
            }
            // Preserve quarantine and repaired rows before disposing the isolated target.
            BackupArtifacts review = BackupArtifacts.create(inputs.output().resolve("review-dumps"));
            for (String database : List.of("shard-a", "shard-b", "shard-c", "ledger-db"))
                target.dumpDatabase(database, review.directory().resolve(database + ".dump"));
            review.seal(provenance(inputs).put("purpose", "recovery-review-only")
                    .put("trafficAuthorized", false).put("completedAt", Instant.now().toString()));
            result.put("reviewDumps", review.directory().toString()).put("trafficAuthorized", false);
        }
        output.writeJson("result.json", result.put("ownedTargetRemoved", true).put("completedAt", Instant.now().toString()));
        verifyInputs(inputs);
        System.out.println("Isolated operator recovery evidence and review dumps retained; owned target removed; traffic remains unauthorized.");
    }

    public void drill() throws Exception {
        Inputs inputs = inputs(false);
        RestoreCdcRecovery.preflight();
        BackupArtifacts output = BackupArtifacts.create(inputs.output());
        ObjectNode result = provenance(inputs).put("operation", "older-restore-qualification");
        try (OwnedRestoreTarget target = restored(inputs, false)) {
            result.set("baseline", JSON.valueToTree(RestoreVerification.verify(target, KindBackupRestore.SHARDS)));
            RecoveryAccess access = RecoveryAccess.owned(target);
            List<RecoveryLossFixture.Witness> witnesses = RecoveryLossFixture.select(target, 3);
            RecoveryLossFixture.Witness recoverable = witnesses.get(0), unavailable = witnesses.get(1), mismatch = witnesses.get(2);
            RecoveryRecords.Saved original = RecoveryRecords.capture(access, inputs.output().resolve("original-record"), recoverable.orderId());
            RecoveryRecords.Saved conflictRecord = RecoveryRecords.capture(access, inputs.output().resolve("conflicting-original-record"), mismatch.orderId());
            Map<String, String> catalogBefore = catalogFingerprints(target);
            long entries = count(access, "ledger-db", "SELECT count(*) FROM ledger.ledger_entries");
            Path ledger = RecoveryAudit.exportLedger(access, inputs.output().resolve("ledger-export"));
            result.set("baselineAudit", JSON.valueToTree(RecoveryAudit.audit(access, ledger)));
            Path current = inputs.output().resolve("before-loss-ordering.dump");
            target.dumpOrdering(recoverable.shard(), current);
            Path older = inputs.output().resolve("constructed-older-ordering.dump");
            RecoveryLossFixture.createOlderDump(target, recoverable.shard(), witnesses.subList(0, 2), older);
            target.restoreOrdering(recoverable.shard(), current);
            for (var witness : witnesses.subList(0, 2)) require(countOrder(access, witness) == 1,
                    "The later order state must exist before applying the older dump");
            RecoveryLossFixture.restoreOlderDump(target, recoverable.shard(), older);
            for (var witness : witnesses.subList(0, 2)) require(countOrder(access, witness) == 0,
                    "The actual older dump did not remove the later acknowledged order");
            RecoveryLossFixture.mismatchFingerprint(target, mismatch);
            RecoveryAudit.Report audit = RecoveryAudit.audit(access, ledger);
            result.set("lossAudit", JSON.valueToTree(audit));
            require(audit.quarantined() >= 3 && audit.orphanReservations() >= 2, "Audit missed restored ledger/order disagreements or orphan reservations");
            for (var witness : witnesses) require(unresolved(access, witness) > 0, "Audit must quarantine every selected disagreement before any late delivery");
            long retainedIncidents = incidents(access, recoverable);
            RecoveryAudit.audit(access, ledger);
            require(incidents(access, recoverable) == retainedIncidents, "Repeated audit duplicated original incident identity");
            RecoveryReplay.late(target, witnesses.subList(0, 2), inputs.output());
            for (var witness : witnesses) require(unresolved(access, witness) > 0, "Late delivery cleared unresolved evidence");
            boolean refused = false;
            try { RecoveryRepair.repair(access, conflictRecord.directory(), mismatch.orderId()); }
            catch (java.io.IOException expected) { refused = true; }
            require(refused && unresolved(access, mismatch) > 0, "Repair must refuse to overwrite conflicting original history");
            refused = false;
            try { RecoveryRepair.repair(access, inputs.output().resolve("absent-original-record"), unavailable.orderId()); }
            catch (java.io.IOException | IllegalArgumentException expected) { refused = true; }
            require(refused && unresolved(access, unavailable) > 0, "Missing original evidence must retain the incident");
            result.set("repair", JSON.valueToTree(RecoveryRepair.repair(access, original.directory(), recoverable.orderId())));
            require(unresolved(access, recoverable) == 0, "Verified recovery did not resolve its matching incidents");
            RecoveryReplay.replay(target, recoverable, inputs.output());
            require(count(access, "ledger-db", "SELECT count(*) FROM ledger.ledger_entries") == entries,
                    "Recovery or replay changed permanent ledger entry cardinality");
            require(unresolved(access, unavailable) > 0 && unresolved(access, mismatch) > 0,
                    "Recovery cleared a different unresolved incident");
            require(countOrder(access, unavailable) == 0, "Recovery fabricated an unrecoverable order");
            require(catalogBefore.equals(catalogFingerprints(target)), "Recovery or replay changed stock, reservations or seller profit data");
            result.set("finalAudit", JSON.valueToTree(RecoveryAudit.audit(access, ledger)));
            result.put("originallyAcknowledgedResultsDetectedBeforeReplay", true)
                    .put("actualConstructedOlderDumpRestored", true).put("unrecoverableLedgerHistoryRetained", true)
                    .put("foreignConflictRefused", true).put("originalReplayIdentitiesPreserved", true);
        }
        output.writeJson("result.json", result.put("passed", true).put("ownedTargetRemoved", true).put("completedAt", Instant.now().toString()));
        verifyInputs(inputs);
        System.out.println("Older-restore audit, blocking quarantine, verified original recovery and retained unresolved incidents passed; owned target removed.");
    }

    private static long incidents(RecoveryAccess access, RecoveryLossFixture.Witness witness) throws Exception {
        return count(access, witness.shard(), "SELECT count(*) FROM ordering.orphan_results WHERE order_id=" + witness.orderId());
    }

    private static Map<String, String> catalogFingerprints(OwnedRestoreTarget target) throws Exception {
        Map<String, String> hashes = new LinkedHashMap<>();
        for (String shard : KindBackupRestore.SHARDS) try (Connection connection = target.connection(shard)) {
            try (var statement = connection.createStatement()) { statement.execute("SET TIME ZONE 'UTC'"); }
            List<String> tables = new java.util.ArrayList<>();
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "SELECT tablename FROM pg_tables WHERE schemaname='catalog' ORDER BY tablename")) {
                while (rows.next()) tables.add(rows.getString(1));
            }
            for (String table : tables) {
                require(table.matches("[a-z_][a-z0-9_]*"), "Unexpected catalog relation identifier");
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (var output = new DigestOutputStream(OutputStream.nullOutputStream(), digest)) {
                    connection.unwrap(PGConnection.class).getCopyAPI().copyOut(
                            "COPY (SELECT encode(sha256(convert_to(row_to_json(t)::text,'UTF8')),'hex') FROM catalog.\""
                                    + table + "\" t ORDER BY 1) TO STDOUT", output);
                }
                hashes.put(shard + "/" + table, HexFormat.of().formatHex(digest.digest()));
            }
        }
        return Map.copyOf(hashes);
    }
    private static long unresolved(RecoveryAccess access, RecoveryLossFixture.Witness witness) throws Exception {
        return count(access, witness.shard(), "SELECT count(*) FROM ordering.orphan_results WHERE resolved_at IS NULL AND order_id=" + witness.orderId());
    }
    private static long countOrder(RecoveryAccess access, RecoveryLossFixture.Witness witness) throws Exception {
        return count(access, witness.shard(), "SELECT count(*) FROM ordering.orders WHERE order_id=" + witness.orderId());
    }
    private static long count(RecoveryAccess access, String database, String sql) throws Exception {
        try (Connection connection = access.open(database)) { return RestoreVerification.count(connection, sql); }
    }

    private record Inputs(BackupArtifacts orders, BackupArtifacts ledger, Path allocator, Path output, String target) { }
    private static Inputs inputs(boolean separateLedger) throws Exception {
        BackupArtifacts orders = BackupArtifacts.open(path("shardshop.backup.directory"));
        BackupArtifacts ledger = separateLedger ? BackupArtifacts.open(path("shardshop.recovery.ledger-backup")) : orders;
        JsonNode inventory = new KindLab().inventory();
        String migration = KindBackupRestore.migrationFingerprint();
        KindBackupRestore.validateManifest(orders.manifest(), inventory, migration);
        KindBackupRestore.validateManifest(ledger.manifest(), inventory, migration);
        if (separateLedger) require(!Instant.parse(ledger.manifest().path("completedAt").asText())
                .isBefore(Instant.parse(orders.manifest().path("completedAt").asText())), "Current ledger evidence predates the restored ordering backup");
        require(orders.manifest().path("roles").equals(ledger.manifest().path("roles")), "Recovery source role contracts differ");
        for (JsonNode database : orders.manifest().path("databases")) orders.file(database.path("dump").asText());
        ledger.file("ledger-db.dump");
        Path allocator = path("shardshop.backup.allocator");
        BackupAllocator.validate(orders.manifest().path("allocator"), BackupAllocator.read(allocator));
        BackupAllocator.validate(ledger.manifest().path("allocator"), BackupAllocator.read(allocator));
        Path output = path("shardshop.recovery.directory");
        separate(output, orders.directory()); separate(output, ledger.directory()); separate(output, allocator);
        String target = required("shardshop.restore.target");
        OwnedRestoreTarget.validateTargetName(target);
        return new Inputs(orders, ledger, allocator, output, target);
    }
    private static OwnedRestoreTarget restored(Inputs inputs, boolean currentLedger) throws Exception {
        OwnedRestoreTarget target = OwnedRestoreTarget.create(inputs.target(), inputs.output());
        java.lang.Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { target.close(); }
            catch (Exception failure) { System.err.println("Owned recovery target cleanup needs attention"); }
        }, "owned-recovery-target-cleanup"));
        try {
            for (JsonNode database : inputs.orders().manifest().path("databases")) {
                String name = database.path("name").asText();
                BackupArtifacts source = currentLedger && name.equals("ledger-db") ? inputs.ledger() : inputs.orders();
                JsonNode metadata = database;
                if (source != inputs.orders()) for (JsonNode candidate : source.manifest().path("databases"))
                    if (candidate.path("name").asText().equals(name)) metadata = candidate;
                target.requireDifferentSystemIdentifier(metadata.path("systemIdentifier").asText());
                target.restore(name, source.file(name + ".dump"), source.manifest().path("roles"));
                target.applyDatabaseSecurity(name, metadata.path("databaseSecurity"));
                KindBackupRestore.verifyFingerprint(target, metadata);
            }
            return target;
        } catch (Exception | Error failure) {
            try { target.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    private static ObjectNode provenance(Inputs inputs) throws java.io.IOException {
        return JSON.createObjectNode().put("orderBackupId", inputs.orders().manifest().path("backupId").asText())
                .put("ledgerBackupId", inputs.ledger().manifest().path("backupId").asText())
                .put("target", inputs.target()).put("sourceDatabasesModified", false);
    }
    private static void verifyInputs(Inputs inputs) throws Exception {
        BackupArtifacts.open(inputs.orders().directory()); BackupArtifacts.open(inputs.ledger().directory());
        BackupAllocator.validate(inputs.orders().manifest().path("allocator"), BackupAllocator.read(inputs.allocator()));
    }
    static long selectedOrder() {
        String value = required("shardshop.recovery.order-id");
        if (!value.matches("[1-9][0-9]*")) throw new IllegalArgumentException("Recovery requires one canonical positive order ID");
        try { return Long.parseLong(value); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("Recovery order ID exceeds signed 64-bit range"); }
    }
    static void separate(Path output, Path input) {
        if (output.startsWith(input) || input.startsWith(output)) throw new IllegalArgumentException("Recovery output must be separate from immutable input evidence");
    }
    private static Path path(String name) {
        Path path = Path.of(required(name));
        if (!path.isAbsolute() || !path.normalize().equals(path)) throw new IllegalArgumentException(name + " requires an absolute normalized path");
        for (Path part : path) if (part.toString().equals("target")) throw new IllegalArgumentException("Recovery evidence must survive Maven clean");
        return path;
    }
    private static String required(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Explicit " + name + " is required");
        return value;
    }
    private static void require(boolean condition, String message) throws java.io.IOException {
        if (!condition) throw new java.io.IOException(message);
    }
}
