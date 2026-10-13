package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class KindTopologyTest {
    private static final List<String> SHARDS = List.of("shard-a", "shard-b", "shard-c");
    private static final Set<String> WORKERS = Set.of("worker-1", "worker-2", "worker-3");

    @Test
    void goldenVectorsPreserveExactLargeIdsAndRemapDeploymentOrderAndSize() throws IOException {
        List<String> vectors = List.of("# frozen fixture", "minimum positive ID,1,shard-a",
                "maximum signed long ID,9223372036854775807,shard-c",
                "above exact double range,9007199254740993,shard-c");
        assertEquals("(1::bigint, 'shard-c'),(9223372036854775807::bigint, 'shard-b'),(9007199254740993::bigint, 'shard-b')",
                KindTopology.routingValues(vectors, List.of("shard-c", "shard-a", "shard-b")));
        assertEquals("(1::bigint, 'east'),(9223372036854775807::bigint, 'east'),(9007199254740993::bigint, 'east')",
                KindTopology.routingValues(vectors, List.of("west", "east")));
    }

    @Test
    void refusesChangedGoldenExpectationsNoncanonicalIdsAndUnsafeInventoryNames() {
        assertThrows(IOException.class, () -> KindTopology.routingValues(List.of("altered,1,shard-b"), SHARDS));
        for (String id : List.of("0", "01", "-1", "1.0", "9223372036854775808")) {
            assertThrows(IOException.class, () -> KindTopology.routingValues(List.of("invalid," + id + ",shard-a"), SHARDS));
        }
        assertThrows(IOException.class, () -> KindTopology.routingValues(List.of("only comments"), SHARDS));
        assertThrows(IOException.class, () -> KindTopology.routingValues(List.of("valid,1,shard-a"), List.of("bad'")));
        assertThrows(IOException.class, () -> KindTopology.routingValues(List.of("valid,1,shard-a"), List.of("a", "a")));
        assertThrows(IOException.class, () -> KindTopology.routingValues(List.of("valid,1,shard-a"), List.of()));
    }

    @Test
    void rejectsSharedWorkerSharedClaimMissingPrimaryAndUnboundStorage() throws IOException {
        List<JsonNode> valid = List.of(pod(1, "worker-1", "data-1"), pod(2, "worker-2", "data-2"), pod(3, "worker-3", "data-3"));
        KindTopology.validatePlacement("shard-a", "shard-a-1", valid, volumes(), WORKERS);
        assertThrows(IOException.class, () -> KindTopology.validatePlacement("shard-a", "shard-a-1",
                List.of(valid.getFirst(), pod(2, "worker-1", "data-2"), valid.getLast()), volumes(), WORKERS));
        assertThrows(IOException.class, () -> KindTopology.validatePlacement("shard-a", "shard-a-1",
                List.of(valid.getFirst(), pod(2, "worker-2", "data-1"), valid.getLast()), volumes(), WORKERS));
        assertThrows(IOException.class, () -> KindTopology.validatePlacement("shard-a", "shard-a-9", valid, volumes(), WORKERS));
        assertThrows(IOException.class, () -> KindTopology.validatePlacement("shard-a", "shard-a-1", valid, volumes(), Set.of("worker-1", "worker-2")));
        Map<String, KindTopology.Volume> unbound = Map.of("data-1", new KindTopology.Volume("uid-1", "pv-1", "Pending"),
                "data-2", volumes().get("data-2"), "data-3", volumes().get("data-3"));
        assertThrows(IOException.class, () -> KindTopology.validatePlacement("shard-a", "shard-a-1", valid, unbound, WORKERS));
        Map<String, KindTopology.Volume> shared = Map.of("data-1", volumes().get("data-1"),
                "data-2", new KindTopology.Volume("uid-2", "pv-1", "Bound"), "data-3", volumes().get("data-3"));
        assertThrows(IOException.class, () -> KindTopology.validatePlacement("shard-a", "shard-a-1", valid, shared, WORKERS));
    }

    @Test
    void readyEndpointsIgnoreUnreadyTargetsButPreserveUnexpectedDuplicates() {
        JsonNode slices = KindLab.parse("""
                {"items":[{"endpoints":[
                {"conditions":{"ready":true},"targetRef":{"name":"shard-a-3"}},
                {"conditions":{"ready":false},"targetRef":{"name":"shard-a-1"}},
                {"conditions":{"ready":true},"targetRef":{"name":"shard-a-2"}}]},
                {"endpoints":[{"conditions":{"ready":true},"targetRef":{"name":"shard-a-2"}}]}]}
                """);
        assertEquals(List.of("shard-a-2", "shard-a-2", "shard-a-3"), KindTopology.readyEndpoints(slices));
    }

    @Test
    void quorumRequiresDurableAnyOneAndCapacityForReplicationCdcAndRecovery() throws IOException {
        KindTopology.validateQuorumSettings("on\nANY 1 (\"shard-a-2\",\"shard-a-3\")\n10\n10");
        for (String invalid : List.of("off\nANY 1 (*)\n10\n10", "on\nFIRST 1 (*)\n10\n10",
                "on\nANY 1 (*)\n2\n10", "on\nANY 1 (*)\n10\n3", "on\nANY 1 (*)\ninvalid\n10")) {
            assertThrows(IOException.class, () -> KindTopology.validateQuorumSettings(invalid));
        }
    }

    @Test
    void aZeroExitWithSynchronousWaitWarningIsAFailedCommit() throws IOException {
        assertEquals("committed", KindTopology.successfulSql(new KindLab.CommandResult(0, "committed\n", "")));
        assertThrows(IOException.class, () -> KindTopology.successfulSql(new KindLab.CommandResult(0, "committed", "WARNING: canceling wait for synchronous replication")));
        assertThrows(IOException.class, () -> KindTopology.successfulSql(new KindLab.CommandResult(1, "", "failure")));
    }

    private static JsonNode pod(int number, String worker, String claim) {
        return KindLab.parse("""
                {"metadata":{"name":"shard-a-%d"},"spec":{"nodeName":"%s",
                "volumes":[{"name":"pgdata","persistentVolumeClaim":{"claimName":"%s"}}]}}
                """.formatted(number, worker, claim));
    }

    private static Map<String, KindTopology.Volume> volumes() {
        return Map.of("data-1", new KindTopology.Volume("uid-1", "pv-1", "Bound"),
                "data-2", new KindTopology.Volume("uid-2", "pv-2", "Bound"),
                "data-3", new KindTopology.Volume("uid-3", "pv-3", "Bound"));
    }
}
