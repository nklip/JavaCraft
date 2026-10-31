package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OwnedRestoreTargetTest {
    @TempDir Path temporary;

    @Test
    void orderingReplacementCannotSelectTheLedgerOrAnArbitraryDatabase() {
        for (String shard : List.of("shard-a", "shard-b", "shard-c")) OwnedRestoreTarget.validateOrderingSource(shard);
        for (String source : List.of("ledger-db", "postgres", "shard_a", "shard-a;DROP DATABASE postgres")) {
            assertThrows(IllegalArgumentException.class, () -> OwnedRestoreTarget.validateOrderingSource(source));
        }
        assertThrows(IllegalArgumentException.class, () -> OwnedRestoreTarget.validateOrderingSource(null));
    }

    @Test
    void qualifiedFullyNamedDigestIsAcceptedByThePostgresContainerWithoutStartingDocker() {
        String image = "docker.io/library/postgres@sha256:979bccfde35445db618c65107d27d08c6c21339d8dc3ad4c830c6633947be0e4";
        assertDoesNotThrow(() -> new org.testcontainers.postgresql.PostgreSQLContainer(OwnedRestoreTarget.qualifiedImage(image)));
        assertThrows(IllegalArgumentException.class, () -> OwnedRestoreTarget.qualifiedImage("postgres:latest"));
    }

    @Test
    void targetCannotNameTheRetainedLabOrAnArbitraryDockerContainer() {
        OwnedRestoreTarget.validateTargetName("shardshop-restore-ab12");
        for (String name : List.of("shardshop", "shardshop-test-abc", "postgres", "shardshop-restore-",
                "shardshop-restore-a;touch", "shardshop-restore-" + "x".repeat(64))) {
            assertThrows(IllegalArgumentException.class, () -> OwnedRestoreTarget.validateTargetName(name));
        }
    }

    @Test
    void onlyTheFourSourceInventoryNamesCanSelectTargetDatabases() {
        assertEquals("shard_a", OwnedRestoreTarget.databaseName("shard-a"));
        assertEquals("shard_b", OwnedRestoreTarget.databaseName("shard-b"));
        assertEquals("shard_c", OwnedRestoreTarget.databaseName("shard-c"));
        assertEquals("ledger_db", OwnedRestoreTarget.databaseName("ledger-db"));
        for (String name : List.of("postgres", "shardshop", "shard_a", "ledger", "shard-a;DROP DATABASE postgres")) {
            assertThrows(IllegalArgumentException.class, () -> OwnedRestoreTarget.databaseName(name));
        }
        assertThrows(IllegalArgumentException.class, () -> OwnedRestoreTarget.databaseName(null));
    }

    @Test
    void restoreWorkRequiresAPrivateDirectoryAndRejectsSymlinkSelection() throws Exception {
        Path privateWork = Files.createDirectory(temporary.resolve("private"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        assertEquals(privateWork.toRealPath(), OwnedRestoreTarget.privateDirectory(privateWork));
        Path link = Files.createSymbolicLink(temporary.resolve("link"), privateWork);
        assertThrows(IllegalArgumentException.class, () -> OwnedRestoreTarget.privateDirectory(link));
        Files.setPosixFilePermissions(privateWork, PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThrows(IllegalArgumentException.class, () -> OwnedRestoreTarget.privateDirectory(privateWork));
        assertThrows(IllegalArgumentException.class, () -> OwnedRestoreTarget.privateDirectory(Path.of("relative")));
    }

    @Test
    void sourceRoleFlagsAndMembershipsAreRetainedWithoutImportingPasswords() {
        ArrayNode roles = roles();
        ((ObjectNode) roles.get(1)).put("password", "source-secret-must-not-be-used");
        var validated = OwnedRestoreTarget.validateRoles(roles);
        assertEquals(List.of("ordering_cdc_reader"), validated.get(1).memberships());
        assertTrue(validated.get(1).login());
        assertTrue(validated.get(1).replication());
        assertFalse(validated.get(0).login());
        assertEquals(-1, validated.get(0).connectionLimit());
    }

    @Test
    void unexpectedElevatedOrMalformedRolesFailBeforeAnyDatabaseMutation() {
        for (String name : List.of("postgres", "pg_read_all_data", "streaming_replica", "owner; DROP DATABASE postgres")) {
            ArrayNode roles = roles();
            ((ObjectNode) roles.get(0)).put("name", name);
            assertThrows(IllegalArgumentException.class, () -> OwnedRestoreTarget.validateRoles(roles));
        }
        for (String privilege : List.of("superuser", "createdb", "createrole", "bypassrls")) {
            ArrayNode roles = roles();
            ((ObjectNode) roles.get(1)).put(privilege, true);
            assertThrows(IllegalArgumentException.class, () -> OwnedRestoreTarget.validateRoles(roles));
        }
        ArrayNode duplicate = roles();
        duplicate.add(duplicate.get(0).deepCopy());
        assertThrows(IllegalArgumentException.class, () -> OwnedRestoreTarget.validateRoles(duplicate));
        ArrayNode absent = roles();
        absent.remove(0);
        assertThrows(IllegalArgumentException.class, () -> OwnedRestoreTarget.validateRoles(absent));
        ArrayNode missingFlag = roles();
        ((ObjectNode) missingFlag.get(1)).remove("superuser");
        assertThrows(IllegalArgumentException.class, () -> OwnedRestoreTarget.validateRoles(missingFlag));
        ArrayNode invalidReplication = roles();
        ((ObjectNode) invalidReplication.get(0)).put("replication", true);
        assertThrows(IllegalArgumentException.class, () -> OwnedRestoreTarget.validateRoles(invalidReplication));
        ArrayNode invalidMembership = roles();
        ((ObjectNode) invalidMembership.get(1)).putArray("memberships").add("pg_read_all_data");
        assertThrows(IllegalArgumentException.class, () -> OwnedRestoreTarget.validateRoles(invalidMembership));
        assertThrows(IllegalArgumentException.class, () -> OwnedRestoreTarget.validateRoles(KindLab.parse("[]")));
    }

    private static ArrayNode roles() {
        return (ArrayNode) KindLab.parse("""
                [{"name":"ordering_cdc_reader","login":false,"inherit":true,"replication":false,
                  "superuser":false,"createdb":false,"createrole":false,"bypassrls":false,"connectionLimit":-1,"memberships":[]},
                 {"name":"ordering_cdc","login":true,"inherit":true,"replication":true,
                  "superuser":false,"createdb":false,"createrole":false,"bypassrls":false,"connectionLimit":-1,
                  "memberships":["ordering_cdc_reader"]}]
                """);
    }

    @Test
    void databaseSecurityRetainsRevokedPublicDefaultsAndExplicitGrantOptions() {
        var security = KindLab.parse("""
                {"owner":"shardshop_owner","privileges":[
                  {"grantee":"order_app","privilege":"CONNECT","grantable":false},
                  {"grantee":"catalog_owner","privilege":"CREATE","grantable":true}]}
                """);
        assertEquals(java.util.Set.of("order_app:CONNECT:false", "catalog_owner:CREATE:true"),
                OwnedRestoreTarget.securityPrivileges(security));
        assertFalse(OwnedRestoreTarget.securityPrivileges(security).stream().anyMatch(p -> p.startsWith("PUBLIC:")));
        OwnedRestoreTarget.validateDatabaseSecurity(KindLab.parse("{\"owner\":\"ledger_owner\",\"privileges\":[]}"));
    }

    @Test
    void databaseSecurityRejectsUnknownRolesPrivilegesAndAmbiguousEntries() {
        for (String invalid : List.of(
                "{\"owner\":\"postgres\",\"privileges\":[]}",
                "{\"owner\":\"shardshop_owner; DROP DATABASE postgres\",\"privileges\":[]}",
                "{\"owner\":\"shardshop_owner\",\"privileges\":[{\"grantee\":\"pg_read_all_data\",\"privilege\":\"CONNECT\",\"grantable\":false}]}",
                "{\"owner\":\"shardshop_owner\",\"privileges\":[{\"grantee\":\"order_app\",\"privilege\":\"ALL\",\"grantable\":false}]}",
                "{\"owner\":\"shardshop_owner\",\"privileges\":[{\"grantee\":\"PUBLIC\",\"privilege\":\"CONNECT\",\"grantable\":true}]}",
                "{\"owner\":\"shardshop_owner\",\"privileges\":[{\"grantee\":\"order_app\",\"privilege\":\"CONNECT\"}]}",
                "{\"owner\":\"shardshop_owner\",\"privileges\":[{\"grantee\":\"order_app\",\"privilege\":\"CONNECT\",\"grantable\":false},{\"grantee\":\"order_app\",\"privilege\":\"CONNECT\",\"grantable\":true}]}")) {
            assertThrows(IllegalArgumentException.class, () -> OwnedRestoreTarget.validateDatabaseSecurity(KindLab.parse(invalid)));
        }
    }
}
