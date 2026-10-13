package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class KindReadinessTest {
    private static JsonNode json(String value) { return KindLab.parse(value); }

    @Test
    void quantitiesCompareEquivalentKubernetesUnits() {
        assertEquals(KindReadiness.quantity("1"), KindReadiness.quantity("1000m"));
        assertEquals(KindReadiness.quantity("1Gi"), KindReadiness.quantity("1024Mi"));
        assertEquals(KindReadiness.quantity("1G"), KindReadiness.quantity("1e9"));
        assertEquals(KindReadiness.quantity("1000n"), KindReadiness.quantity("1u"));
        assertNotEquals(KindReadiness.quantity("1G"), KindReadiness.quantity("1Gi"));
        assertThrows(IllegalArgumentException.class, () -> KindReadiness.quantity("1invalid"));
    }

    @Test
    void admissionDefaultsOmittedRequestsButPreservesExplicitZero() {
        JsonNode limits = json("{\"limits\":{\"cpu\":\"1\",\"memory\":\"1Gi\"}}");
        JsonNode admitted = json("{\"limits\":{\"cpu\":\"1000m\",\"memory\":\"1024Mi\"},\"requests\":{\"cpu\":\"1\",\"memory\":\"1Gi\"}}");
        assertEquals(KindReadiness.resources(limits), KindReadiness.resources(admitted));
        JsonNode zero = json("{\"limits\":{\"cpu\":\"1\"},\"requests\":{\"cpu\":\"0\"}}");
        assertEquals(KindReadiness.quantity("0"), KindReadiness.resources(zero).get("requests").get("cpu"));
        assertNotEquals(KindReadiness.resources(json("{\"limits\":{\"cpu\":\"1\"}}")), KindReadiness.resources(zero));
    }

    @Test
    void parametersRequireExactConfiguredValueAppliedWithoutRestartPending() {
        JsonNode desired = json("{\"max_connections\":\"100\"}");
        assertTrue(KindReadiness.parametersApplied(desired, json("{\"max_connections\":{\"setting\":\"100\",\"applied\":true,\"pending\":false}}")));
        for (String actual : new String[]{"{}", "{\"max_connections\":{\"setting\":\"90\",\"applied\":true,\"pending\":false}}",
                "{\"max_connections\":{\"setting\":\"100\",\"applied\":false,\"pending\":false}}",
                "{\"max_connections\":{\"setting\":\"100\",\"applied\":true,\"pending\":true}}",
                "{\"max_connections\":{\"setting\":\"100\",\"applied\":true,\"pending\":null}}",
                "{\"max_connections\":{\"setting\":\"100\",\"applied\":\"true\",\"pending\":false}}",
                "{\"max_connections\":{\"setting\":\"100\",\"applied\":true}}"})
            assertFalse(KindReadiness.parametersApplied(desired, json(actual)));
    }

    @Test
    void appliedStatusesCannotPassForAnEarlierGenerationOrMissingResources() {
        assertTrue(KindReadiness.generationsApplied(json("{\"items\":[{\"metadata\":{\"generation\":2},\"status\":{\"observedGeneration\":2,\"applied\":true}}]}")));
        assertFalse(KindReadiness.generationsApplied(json("{\"items\":[]}")));
        assertFalse(KindReadiness.generationsApplied(json("{\"items\":[{\"metadata\":{\"generation\":2},\"status\":{\"observedGeneration\":1,\"applied\":true}}]}")));
        assertFalse(KindReadiness.generationsApplied(json("{\"items\":[{\"metadata\":{\"generation\":2},\"status\":{\"observedGeneration\":2,\"applied\":false}}]}")));
    }

    @Test
    void streamingRequiresBothExactStandbysAndNoExtraConnections() {
        JsonNode pods = json("{\"items\":[{\"metadata\":{\"name\":\"s-1\"}},{\"metadata\":{\"name\":\"s-2\"}},{\"metadata\":{\"name\":\"s-3\"}}]}");
        assertTrue(KindReadiness.streamingMatches("s-1", 3, pods, "2\ns-2\ns-3\n"));
        assertFalse(KindReadiness.streamingMatches("s-1", 3, pods, "3\ns-2\ns-3\n"));
        assertFalse(KindReadiness.streamingMatches("s-1", 3, pods, "2\ns-2\nother\n"));
        assertFalse(KindReadiness.streamingMatches("s-1", 3, pods, "2\ns-2\n"));
        assertFalse(KindReadiness.streamingMatches("other", 3, pods, "2\ns-2\ns-3\n"));
        ((ObjectNode) pods.path("items").get(2).path("metadata")).put("deletionTimestamp", "now");
        assertFalse(KindReadiness.streamingMatches("s-1", 3, pods, "2\ns-2\ns-3\n"));
    }

    @Test
    void twoRetainedInstancesCannotSatisfyTheThreeInstanceInventory() {
        JsonNode inventory = json("{\"instances\":\"3\"}");
        JsonNode current = json("{\"spec\":{\"instances\":2},\"status\":{\"currentPrimary\":\"s-1\"}}");
        JsonNode pods = json("{\"items\":[{\"metadata\":{\"name\":\"s-1\"}},{\"metadata\":{\"name\":\"s-2\"}}]}");
        assertFalse(KindReadiness.streamingMatches(inventory, current, pods, "1\ns-2\n"));
    }

    @Test
    void rolloutRejectsOldImagesUnreadyDeletingAndWrongResourcePods() {
        JsonNode cluster = json("{\"spec\":{\"instances\":1,\"imageName\":\"postgres@new\",\"resources\":{\"limits\":{\"cpu\":\"1\"}}}}");
        JsonNode pods = json("{\"items\":[{\"metadata\":{\"name\":\"s-1\"},\"spec\":{\"containers\":[{\"name\":\"postgres\",\"image\":\"postgres@new\",\"resources\":{\"limits\":{\"cpu\":\"1000m\"},\"requests\":{\"cpu\":\"1\"}}}],\"initContainers\":[{\"name\":\"bootstrap-controller\",\"image\":\"operator@new\"}]},\"status\":{\"conditions\":[{\"type\":\"Ready\",\"status\":\"True\"}]}}]}");
        assertTrue(KindReadiness.rolloutPending(cluster, pods, "operator@new").isEmpty());
        ObjectNode pod = (ObjectNode) pods.path("items").get(0);
        ((ObjectNode) pod.path("spec").path("containers").get(0)).put("image", "postgres@old");
        assertTrue(KindReadiness.rolloutPending(cluster, pods, "operator@new").contains("s-1 database image"));
        ((ObjectNode) pod.path("spec").path("containers").get(0)).put("image", "postgres@new");
        ((ObjectNode) pod.path("spec").path("containers").get(0).path("resources").path("requests")).put("cpu", "0");
        assertTrue(KindReadiness.rolloutPending(cluster, pods, "operator@new").contains("s-1 resources"));
        assertTrue(KindReadiness.rolloutPending(cluster, pods, "operator@old").contains("s-1 operator image"));
        ((ObjectNode) pod.path("status").path("conditions").get(0)).put("status", "False");
        assertTrue(KindReadiness.rolloutPending(cluster, pods, "operator@new").contains("s-1 not Ready"));
        ((ObjectNode) pod.path("status").path("conditions").get(0)).put("status", "True");
        ((ObjectNode) pod.path("metadata")).put("deletionTimestamp", "now");
        assertTrue(KindReadiness.rolloutPending(cluster, pods, "operator@new").contains("s-1 not Ready"));
        ((ObjectNode) cluster.path("spec")).put("instances", 3);
        assertTrue(KindReadiness.rolloutPending(cluster, pods, "operator@new").contains("instances 1/3"));
    }
}
