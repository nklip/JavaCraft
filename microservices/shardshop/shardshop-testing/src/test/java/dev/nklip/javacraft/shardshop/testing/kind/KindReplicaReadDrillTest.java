package dev.nklip.javacraft.shardshop.testing.kind;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class KindReplicaReadDrillTest {
    @Test
    void outageMustReturnTheDocumentedErrorBeforeTheServerBudget() throws IOException {
        var error = KindLab.parse("{\"code\":\"READ_REPLICA_UNAVAILABLE\"}");
        KindReplicaReadDrill.assertRead(new KindReplicaReadDrill.Reply(503, error, Duration.ofMillis(3001)), 503, "1");
        assertThrows(IOException.class, () -> KindReplicaReadDrill.assertRead(
                new KindReplicaReadDrill.Reply(503, error, Duration.ofSeconds(4)), 503, "1"));
        assertThrows(IOException.class, () -> KindReplicaReadDrill.assertRead(
                new KindReplicaReadDrill.Reply(200, KindLab.parse("{\"productId\":\"1\"}"), Duration.ZERO), 503, "1"));
        assertThrows(IOException.class, () -> KindReplicaReadDrill.assertRead(
                new KindReplicaReadDrill.Reply(503, KindLab.parse("{\"code\":\"DATABASE_UNAVAILABLE\"}"), Duration.ZERO), 503, "1"));
    }

    @Test
    void controlledLagIsDistinguishedFromUnavailableReplicas() throws IOException {
        KindReplicaReadDrill.assertRead(new KindReplicaReadDrill.Reply(404,
                KindLab.parse("{\"code\":\"PRODUCT_NOT_FOUND\"}"), Duration.ZERO), 404, "1");
        assertThrows(IOException.class, () -> KindReplicaReadDrill.assertRead(new KindReplicaReadDrill.Reply(200,
                KindLab.parse("{\"productId\":\"2\"}"), Duration.ZERO), 200, "1"));
    }

    @Test
    void readerMustObserveBothPeerSuccessAndReplicaErrorsWithoutClientTimeouts() throws IOException {
        assertTrue(KindReplicaReadDrill.replicaOutcomes("OK=90, REPLICA_UNAVAILABLE=10"));
        assertFalse(KindReplicaReadDrill.replicaOutcomes("OK=100"));
        assertFalse(KindReplicaReadDrill.replicaOutcomes("REPLICA_UNAVAILABLE=100"));
        assertThrows(IOException.class, () -> KindReplicaReadDrill.replicaOutcomes("OK=90, REPLICA_UNAVAILABLE=9, DEADLINE=1"));
        assertThrows(IOException.class, () -> KindReplicaReadDrill.replicaOutcomes("OK=90, REPLICA_UNAVAILABLE=9, TRANSPORT=1"));
        assertThrows(IOException.class, () -> KindReplicaReadDrill.replicaOutcomes("OK=90, NOT_FOUND=10"));
    }
}
