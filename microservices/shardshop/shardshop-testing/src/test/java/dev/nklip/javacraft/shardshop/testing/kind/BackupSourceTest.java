package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BackupSourceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void drainRequiresBothReadyAndUnacknowledgedMessagesToBeZero() throws Exception {
        assertTrue(BackupSource.zeroCounts(JSON.readTree("[{\"messages_ready\":0,\"messages_unacknowledged\":0}]")));
        assertFalse(BackupSource.zeroCounts(JSON.readTree("[{\"messages_ready\":1,\"messages_unacknowledged\":0}]")));
        assertFalse(BackupSource.zeroCounts(JSON.readTree("[{\"messages_ready\":0,\"messages_unacknowledged\":1}]")));
        for (String invalid : new String[]{"{}", "[{}]", "[{\"messages_ready\":-1,\"messages_unacknowledged\":0}]",
                "[{\"messages_ready\":\"0\",\"messages_unacknowledged\":0}]"})
            assertThrows(IOException.class, () -> BackupSource.zeroCounts(JSON.readTree(invalid)));
    }

    @Test
    void replicaRestorationTestsIdentityAndExactObservedSpec() throws Exception {
        ObjectNode saved = deployment();
        ObjectNode stopped = saved.deepCopy();
        ((ObjectNode) stopped.path("spec")).put("replicas", 0);
        JsonNode patch = BackupSource.replicaPatch(saved, stopped, 2);
        assertEquals("/metadata/uid", patch.get(0).path("path").asText());
        assertEquals("uid-1", patch.get(0).path("value").asText());
        assertEquals(stopped.path("spec"), patch.get(1).path("value"));
        assertEquals(2, patch.get(2).path("value").asInt());
    }

    @Test
    void replicaRestorationRefusesReplacedDeploymentOrForeignEdits() throws Exception {
        ObjectNode saved = deployment();
        ObjectNode replacement = saved.deepCopy();
        ((ObjectNode) replacement.path("metadata")).put("uid", "uid-2");
        assertThrows(IOException.class, () -> BackupSource.replicaPatch(saved, replacement, 2));
        ObjectNode changedSpec = saved.deepCopy();
        ((ObjectNode) changedSpec.path("spec")).put("revisionHistoryLimit", 4);
        assertThrows(IOException.class, () -> BackupSource.replicaPatch(saved, changedSpec, 2));
        ObjectNode changedScale = saved.deepCopy();
        ((ObjectNode) changedScale.path("spec")).put("replicas", 3);
        assertThrows(IOException.class, () -> BackupSource.replicaPatch(saved, changedScale, 2));
    }

    @Test
    void roleUnionAcceptsIdenticalRolesAndRejectsDivergenceOrExternalMembership() throws Exception {
        Map<String, JsonNode> roles = new LinkedHashMap<>();
        JsonNode first = JSON.readTree("[{\"name\":\"product_app\",\"login\":true,\"memberships\":[\"catalog_reader\"]}]");
        BackupSource.mergeRoles(roles, first);
        BackupSource.mergeRoles(roles, first);
        assertEquals(1, roles.size());
        assertThrows(IOException.class, () -> BackupSource.mergeRoles(roles,
                JSON.readTree("[{\"name\":\"product_app\",\"login\":false,\"memberships\":[\"catalog_reader\"]}]")));
        assertThrows(IOException.class, () -> BackupSource.mergeRoles(new LinkedHashMap<>(),
                JSON.readTree("[{\"name\":\"product_app\",\"memberships\":[\"pg_read_all_data\"]}]")));
    }

    private static ObjectNode deployment() throws IOException {
        return (ObjectNode) JSON.readTree("{\"metadata\":{\"uid\":\"uid-1\"},\"spec\":{\"replicas\":2,\"template\":{\"spec\":{}}}}");
    }
}
