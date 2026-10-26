package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.postgresql.PGConnection;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static dev.nklip.javacraft.shardshop.testing.kind.KindTopology.require;

/** Explicit Maven entrypoints: retained-source capture and fresh isolated-target qualification. */
public final class KindBackupRestore {
    private static final ObjectMapper JSON = new ObjectMapper();
    static final List<String> SHARDS = List.of("shard-a", "shard-b", "shard-c");
    static final Set<String> DATABASES = Set.of("shard-a", "shard-b", "shard-c", "ledger-db");
    static final String EPOCH = "2026-01-01T00:00:00Z";

    public void allocator() throws Exception {
        String source = required("shardshop.kind.cluster");
        KindLab lab = new KindLab();
        require(source.equals(lab.clusterName()), "Allocator evidence source differs");
        String temporary = System.getenv().getOrDefault("TMPDIR", "/tmp");
        try (BackupLifecycleLock lifecycle = new BackupLifecycleLock(Path.of(temporary.isBlank() ? "/tmp" : temporary))) {
            lifecycle.run(() -> {
                new KindDeployment(lab).deployed();
                new KindSaga(lab).deployed();
                return BackupAllocator.observe(lab, externalPath("shardshop.backup.allocator"));
            });
        }
    }

    public void backup() throws Exception {
        String source = required("shardshop.kind.cluster");
        KindLab lab = new KindLab();
        require(source.equals(lab.clusterName()) && lab.shards().equals(SHARDS), "Backup source inventory differs");
        Path directory = externalPath("shardshop.backup.directory");
        Path latest = externalPath("shardshop.backup.allocator");
        require(!latest.startsWith(directory), "Latest allocator evidence must be separate from database backups");
        BackupArtifacts bundle = BackupArtifacts.create(directory);
        ObjectNode metadata;
        String temporary = System.getenv().getOrDefault("TMPDIR", "/tmp");
        try (BackupLifecycleLock lifecycle = new BackupLifecycleLock(Path.of(temporary.isBlank() ? "/tmp" : temporary))) {
            metadata = lifecycle.run(() -> BackupSource.capture(lab, bundle, latest));
        }
        metadata.put("backupId", UUID.randomUUID().toString()).put("completedAt", Instant.now().toString());
        metadata.put("migrationFingerprint", migrationFingerprint());
        metadata.put("snowflakeEpoch", EPOCH).put("snowflakeLayout", "1/41/10/12");
        metadata.putArray("datasets").add("catalog-v2").add("buyers-v1");
        metadata.putArray("shards").add("shard-a").add("shard-b").add("shard-c");
        metadata.put("restoredTraffic", "blocked-until-explicit-recovery");
        metadata.put("allocatorHistory", latest.toString());
        BackupAllocator.validate(metadata.path("allocator"), BackupAllocator.read(latest));
        bundle.seal(metadata);
        BackupArtifacts.open(directory);
        System.out.println("Coordinated backup complete; source restored; isolated restore and explicit CDC recovery remain required.");
    }

    public void restore() throws Exception {
        BackupArtifacts bundle = BackupArtifacts.open(externalPath("shardshop.backup.directory"));
        ObjectNode manifest = bundle.manifest();
        JsonNode inventory = new KindLab().inventory(); // Reads the invocation snapshot; never contacts the source lab.
        validateManifest(manifest, inventory, migrationFingerprint());
        for (JsonNode database : manifest.path("databases")) bundle.file(database.path("dump").asText());
        bundle.file("rabbitmq-definitions.json");
        Path latest = externalPath("shardshop.backup.allocator");
        BackupAllocator.validate(manifest.path("allocator"), BackupAllocator.read(latest));
        Path evidence = externalPath("shardshop.restore.directory");
        require(!evidence.startsWith(bundle.directory()) && !bundle.directory().startsWith(evidence), "Restore evidence must be separate from immutable backup artifacts");
        String name = required("shardshop.restore.target");
        OwnedRestoreTarget.validateTargetName(name);
        RestoreCdcRecovery.preflight();
        // Exclusive private evidence and target selection precede all target mutation.
        BackupArtifacts report = BackupArtifacts.create(evidence);
        ObjectNode result = JSON.createObjectNode().put("backupId", manifest.path("backupId").asText())
                .put("targetName", name).put("idProducingTraffic", "disabled");
        try (OwnedRestoreTarget target = OwnedRestoreTarget.create(name, evidence)) {
            for (JsonNode database : manifest.path("databases"))
                target.requireDifferentSystemIdentifier(database.path("systemIdentifier").asText());
            for (JsonNode database : manifest.path("databases")) {
                target.restore(database.path("name").asText(), bundle.file(database.path("dump").asText()), manifest.path("roles"));
                target.applyDatabaseSecurity(database.path("name").asText(), database.path("databaseSecurity"));
            }
            for (JsonNode database : manifest.path("databases")) verifyFingerprint(target, database);
            result.set("consistency", JSON.valueToTree(RestoreVerification.verify(target, SHARDS)));
            result.set("cdcRecovery", JSON.valueToTree(RestoreCdcRecovery.verify(target, SHARDS, evidence)));
            result.put("completedAt", Instant.now().toString());
        }
        // Runtime directories contain private diagnostic logs; keep this report outside the immutable backup inventory.
        report.writeJson("restore-result.json", result.put("passed", true).put("ownedTargetRemoved", true));
        BackupArtifacts.open(bundle.directory());
        System.out.println("Isolated restore, retained identities, consistency and explicit CDC recovery passed; owned target removed.");
    }

    static void validateManifest(JsonNode manifest, JsonNode inventory, String migrations) throws IOException {
        require(manifest.path("snowflakeEpoch").asText().equals(EPOCH)
                && manifest.path("snowflakeLayout").asText().equals("1/41/10/12"), "Backup Snowflake contract is incompatible");
        require(manifest.path("migrationFingerprint").asText().equals(migrations), "Backup schema sources are incompatible");
        require(manifest.path("datasets").equals(JSON.valueToTree(List.of("catalog-v2", "buyers-v1")))
                && manifest.path("shards").equals(JSON.valueToTree(SHARDS)), "Backup dataset or shard inventory is incompatible");
        JsonNode routing = manifest.path("configuration").path("shardshop-routing").path("data");
        for (String key : List.of("version", "regionVersion")) require(!routing.path(key).asText().isBlank()
                && routing.path(key).equals(inventory.path(key)), "Backup routing identity is incompatible");
        require(manifest.path("roles").isArray() && !manifest.path("roles").isEmpty(), "Backup role metadata is missing");
        OwnedRestoreTarget.validateRoles(manifest.path("roles"));
        Set<String> names = new HashSet<>();
        for (JsonNode database : manifest.path("databases")) {
            String name = database.path("name").asText();
            require(DATABASES.contains(name) && names.add(name) && database.path("dump").asText().equals(name + ".dump"), "Backup database inventory is invalid");
            require(database.path("systemIdentifier").asText().matches("[0-9]+")
                    && database.path("serverVersion").asText().matches("18[0-9]{4}")
                    && !database.path("clusterUid").asText().isBlank(), "Backup database lineage or version is invalid");
            require(database.path("tableFingerprints").isArray() && !database.path("tableFingerprints").isEmpty(), "Backup table evidence is missing");
            OwnedRestoreTarget.validateDatabaseSecurity(database.path("databaseSecurity"));
            Set<String> tables = new HashSet<>();
            for (JsonNode table : database.path("tableFingerprints")) {
                String schema = identifier(table.path("schema").asText()), relation = identifier(table.path("table").asText());
                require(tables.add(schema + "." + relation) && table.path("sha256").asText().matches("[0-9a-f]{64}")
                        && table.path("rows").canConvertToLong() && table.path("rows").asLong(-1) >= 0, "Backup table fingerprint is invalid");
            }
        }
        require(names.equals(DATABASES), "Backup must contain all three shards and the ledger");
    }

    private static void verifyFingerprint(OwnedRestoreTarget target, JsonNode database) throws Exception {
        try (Connection connection = target.connection(database.path("name").asText())) {
            try (var statement = connection.createStatement()) { statement.execute("SET timezone='UTC'"); }
            Set<String> actual = new HashSet<>();
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "SELECT schemaname,tablename FROM pg_tables WHERE schemaname !~ '^pg_' AND schemaname<>'information_schema'")) {
                while (rows.next()) actual.add(rows.getString(1) + "." + rows.getString(2));
            }
            Set<String> expected = new HashSet<>();
            for (JsonNode table : database.path("tableFingerprints")) {
                String schema = table.path("schema").asText(), name = table.path("table").asText();
                expected.add(schema + "." + name);
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long count;
                try (var stream = new DigestOutputStream(OutputStream.nullOutputStream(), digest)) {
                    count = connection.unwrap(PGConnection.class).getCopyAPI().copyOut(
                            "COPY (SELECT encode(sha256(convert_to(row_to_json(t)::text,'UTF8')),'hex') FROM "
                                    + identifier(schema) + "." + identifier(name) + " t ORDER BY 1) TO STDOUT", stream);
                }
                require(count == table.path("rows").asLong() && HexFormat.of().formatHex(digest.digest()).equals(table.path("sha256").asText()),
                        "Restored table differs from the coordinated backup");
            }
            require(expected.equals(actual), "Restored table inventory differs from the coordinated backup");
            Set<String> schemas = new HashSet<>();
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "SELECT nspname,pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname !~ '^pg_' AND nspname<>'information_schema'")) {
                while (rows.next()) schemas.add(rows.getString(1) + ":" + rows.getString(2));
            }
            Set<String> expectedSchemas = new HashSet<>();
            for (JsonNode schema : database.path("schemas")) expectedSchemas.add(schema.path("name").asText() + ":" + schema.path("owner").asText());
            require(schemas.equals(expectedSchemas), "Restored schema ownership differs from the backup");
            target.verifyDatabaseSecurity(database.path("name").asText(), database.path("databaseSecurity"));
        }
    }

    static String identifier(String value) throws IOException {
        require(value.matches("[a-z_][a-z0-9_]*"), "Invalid backup SQL identifier");
        return '"' + value + '"';
    }

    static String migrationFingerprint() throws Exception {
        Path root = Path.of(required("shardshop.directory")).resolve("database");
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (var paths = Files.walk(root)) {
            for (Path file : paths.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".sql") || p.getFileName().toString().equals("flyway.toml")).sorted().toList()) {
                hash.update(root.relativize(file).toString().getBytes(StandardCharsets.UTF_8)); hash.update((byte) 0);
                try (var input = Files.newInputStream(file)) { byte[] bytes = new byte[8192]; int n; while ((n = input.read(bytes)) != -1) hash.update(bytes, 0, n); }
                hash.update((byte) 0);
            }
        }
        return HexFormat.of().formatHex(hash.digest());
    }

    static Path externalPath(String property) throws IOException {
        Path path = Path.of(required(property));
        require(path.isAbsolute() && path.normalize().equals(path), "Backup paths must be absolute and normalized");
        for (Path component : path) require(!component.toString().equals("target"), "Backup artifacts must survive Maven clean");
        return path;
    }

    private static String required(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Set explicit " + property);
        return value;
    }
}
