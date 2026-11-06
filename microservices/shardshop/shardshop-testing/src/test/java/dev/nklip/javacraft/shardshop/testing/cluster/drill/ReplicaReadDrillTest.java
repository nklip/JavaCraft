package dev.nklip.javacraft.shardshop.testing.cluster.drill;

import dev.nklip.javacraft.shardshop.testing.cluster.ClusterLab;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class ReplicaReadDrillTest {
    @Test
    void outageMustReturnTheDocumentedErrorBeforeTheServerBudget() throws IOException {
        var error = ClusterLab.parse("{\"code\":\"READ_REPLICA_UNAVAILABLE\"}");
        ReplicaReadDrill.assertRead(new ReplicaReadDrill.Reply(503, error, Duration.ofMillis(3001)), 503, "1");
        assertThrows(IOException.class, () -> ReplicaReadDrill.assertRead(
                new ReplicaReadDrill.Reply(503, error, Duration.ofSeconds(4)), 503, "1"));
        assertThrows(IOException.class, () -> ReplicaReadDrill.assertRead(
                new ReplicaReadDrill.Reply(200, ClusterLab.parse("{\"productId\":\"1\"}"), Duration.ZERO), 503, "1"));
        assertThrows(IOException.class, () -> ReplicaReadDrill.assertRead(
                new ReplicaReadDrill.Reply(503, ClusterLab.parse("{\"code\":\"DATABASE_UNAVAILABLE\"}"), Duration.ZERO), 503, "1"));
    }

    @Test
    void controlledLagIsDistinguishedFromUnavailableReplicas() throws IOException {
        ReplicaReadDrill.assertRead(new ReplicaReadDrill.Reply(404,
                ClusterLab.parse("{\"code\":\"PRODUCT_NOT_FOUND\"}"), Duration.ZERO), 404, "1");
        assertThrows(IOException.class, () -> ReplicaReadDrill.assertRead(new ReplicaReadDrill.Reply(200,
                ClusterLab.parse("{\"productId\":\"2\"}"), Duration.ZERO), 200, "1"));
    }

    @Test
    void readerMustObserveBothPeerSuccessAndReplicaErrorsWithoutClientTimeouts() throws IOException {
        assertTrue(ReplicaReadDrill.replicaOutcomes("OK=90, REPLICA_UNAVAILABLE=10"));
        assertFalse(ReplicaReadDrill.replicaOutcomes("OK=100"));
        assertFalse(ReplicaReadDrill.replicaOutcomes("REPLICA_UNAVAILABLE=100"));
        assertThrows(IOException.class, () -> ReplicaReadDrill.replicaOutcomes("OK=90, REPLICA_UNAVAILABLE=9, DEADLINE=1"));
        assertThrows(IOException.class, () -> ReplicaReadDrill.replicaOutcomes("OK=90, REPLICA_UNAVAILABLE=9, TRANSPORT=1"));
        assertThrows(IOException.class, () -> ReplicaReadDrill.replicaOutcomes("OK=90, NOT_FOUND=10"));
    }
}
