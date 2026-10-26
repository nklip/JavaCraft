package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class KindBackupRestoreTest {
    @TempDir Path directory;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static ObjectNode inventory() { return JSON.createObjectNode().put("version", "routing").put("regionVersion", "regions"); }
    private static ObjectNode manifest() {
        ObjectNode manifest = JSON.createObjectNode().put("snowflakeEpoch", KindBackupRestore.EPOCH)
                .put("snowflakeLayout", "1/41/10/12").put("migrationFingerprint", "migrations");
        manifest.putArray("datasets").add("catalog-v2").add("buyers-v1");
        manifest.set("shards", JSON.valueToTree(KindBackupRestore.SHARDS));
        manifest.putObject("configuration").putObject("shardshop-routing").set("data", inventory());
        var role = manifest.putArray("roles").addObject().put("name", "ordering_cdc").put("connectionLimit", -1);
        for (String flag : new String[]{"login", "inherit", "replication", "superuser", "createdb", "createrole", "bypassrls"}) role.put(flag, false);
        role.putArray("memberships");
        var databases = manifest.putArray("databases");
        for (String name : KindBackupRestore.DATABASES) {
            var db = databases.addObject().put("name", name).put("dump", name + ".dump").put("systemIdentifier", "123")
                    .put("serverVersion", "180006").put("clusterUid", "original");
            db.putArray("tableFingerprints").addObject().put("schema", "catalog").put("table", "products")
                    .put("sha256", "0".repeat(64)).put("rows", 0);
            db.putObject("databaseSecurity").put("owner", "shardshop_owner").putArray("privileges");
        }
        return manifest;
    }
    @Test void incompatibleOrIncompleteBackupIsRejectedBeforeRestore() throws Exception {
        KindBackupRestore.validateManifest(manifest(), inventory(), "migrations");
        ObjectNode missing = manifest(); missing.withArray("databases").remove(0);
        assertThrows(IOException.class, () -> KindBackupRestore.validateManifest(missing, inventory(), "migrations"));
        assertThrows(IOException.class, () -> KindBackupRestore.validateManifest(manifest(), inventory().put("version", "other"), "migrations"));
        assertThrows(IOException.class, () -> KindBackupRestore.validateManifest(manifest(), inventory(), "changed-schema"));
        ObjectNode elevated = manifest(); ((ObjectNode)elevated.path("roles").get(0)).put("superuser", true);
        assertThrows(IllegalArgumentException.class, () -> KindBackupRestore.validateManifest(elevated, inventory(), "migrations"));
    }
    @Test void backupSharesLifecycleExclusionAndReleasesOnlyItsEmptyOwnedLock() throws Exception {
        try (BackupLifecycleLock lock = new BackupLifecycleLock(directory)) {
            assertThrows(IOException.class, () -> new BackupLifecycleLock(directory));
            assertEquals("done", lock.run(() -> "done"));
        }
        assertFalse(Files.exists(directory.resolve("shardshop-up.lock")));
        try (BackupLifecycleLock lock = new BackupLifecycleLock(directory)) {
            Path foreign = directory.resolve("shardshop-up.lock/foreign");
            Files.writeString(foreign, "preserve");
            assertThrows(IOException.class, lock::close);
            assertTrue(Files.exists(foreign));
            Files.delete(foreign);
        }
    }
}
