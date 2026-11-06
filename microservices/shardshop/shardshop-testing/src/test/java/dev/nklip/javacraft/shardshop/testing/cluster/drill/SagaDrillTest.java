package dev.nklip.javacraft.shardshop.testing.cluster.drill;

import dev.nklip.javacraft.shardshop.testing.cluster.ClusterLab;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertThrows;

/** The CDC replacement of the saga drill must move the saga to a new JVM on a new worker. */
final class SagaDrillTest {
    @Test void cdcReplacementMustChangeBothJvmAndWorker() throws Exception {
        var before = ClusterLab.parse("{\"metadata\":{\"uid\":\"old\"},\"spec\":{\"nodeName\":\"worker-a\"}}");
        var moved = ClusterLab.parse("{\"metadata\":{\"uid\":\"new\"},\"spec\":{\"nodeName\":\"worker-b\"}}");
        SagaDrill.replacement(before, moved, "worker-b");
        assertThrows(IOException.class, () -> SagaDrill.replacement(before, before, "worker-a"));
        assertThrows(IOException.class, () -> SagaDrill.replacement(before, moved, "worker-c"));
    }
}
