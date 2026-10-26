package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static dev.nklip.javacraft.shardshop.testing.kind.KindTopology.equal;
import static dev.nklip.javacraft.shardshop.testing.kind.KindTopology.require;

/** Guard-owned, reversible standby fencing and replay pauses for the strict replica drill. */
final class KindReplicaStandbys {
    private static final String FENCE = "cnpg.io/fencedInstances";
    private final KindLab lab;
    private final KindTopology topology;
    private final KindTopology.Shard state;
    private final KindDrillGuard guard;
    private final KindDatabaseDrill.FenceState original;
    private final String clusterUid;
    private final Set<KindDatabaseDrill.FenceState> issued = new HashSet<>();
    private final Set<String> paused = new LinkedHashSet<>();

    KindReplicaStandbys(KindLab lab, KindTopology topology, KindTopology.Shard state, KindDrillGuard guard)
            throws IOException, InterruptedException {
        this.lab = lab;
        this.topology = topology;
        this.state = state;
        this.guard = guard;
        JsonNode cluster = topology.cluster(state.name());
        KindDatabaseDrill.rejectPreexistingFence(cluster);
        original = KindDatabaseDrill.fenceState(cluster);
        clusterUid = cluster.path("metadata").path("uid").asText();
        require(!clusterUid.isBlank(), "Cluster identity missing before standby drill");
        guard.onClose("restore standby fencing on " + state.name(), () -> { restore(); return true; });
    }

    void fence(List<String> standbyNames) throws IOException, InterruptedException {
        require(state.standbys().containsAll(standbyNames) && new HashSet<>(standbyNames).size() == standbyNames.size(),
                "Fence selection must contain only distinct observed standbys");
        equal(topology.primary(state.name()), state.primary(), "Primary before standby fencing");
        JsonNode cluster = ownedCluster();
        String value = "[" + String.join(",", standbyNames.stream().map(name -> "\"" + name + "\"").toList()) + "]";
        var target = new KindDatabaseDrill.FenceState(true, value);
        issued.add(target); // A timed-out API command can still have applied this exact value.
        patchFence(cluster, target);
        for (String standby : standbyNames) {
            lab.kubectl(Duration.ofSeconds(140), "--request-timeout=130s", "wait", "--for=condition=Ready=false",
                    "pod/" + standby, "--timeout=120s");
        }
        topology.endpoints(state.name() + "-ro", state.standbys().stream()
                .filter(name -> !standbyNames.contains(name)).toList());
    }

    void restore() throws IOException, InterruptedException {
        JsonNode cluster = ownedCluster();
        if (!KindDatabaseDrill.fenceState(cluster).equals(original)) patchFence(cluster, original);
        topology.ready("cluster/" + state.name());
        for (String standby : state.standbys()) topology.ready("pod/" + standby);
        equal(topology.primary(state.name()), state.primary(), "Primary after standby recovery");
        topology.streaming(state.primary(), state.standbys());
        topology.endpoints(state.name() + "-ro", state.standbys());
        equal(topology.volumes(state.name()), state.volumes(), "PVC identities after standby recovery");
    }

    void pauseReplay() throws IOException, InterruptedException {
        require(paused.isEmpty(), "Replay pauses from this drill must be resumed first");
        for (String standby : state.standbys()) {
            equal(topology.sql(standby, "SELECT pg_is_in_recovery(), pg_get_wal_replay_pause_state()"),
                    "t|not paused", "Standby replay before pause on " + standby);
        }
        guard.onClose("resume standby replay on " + state.name(), () -> { resumeReplay(); return true; });
        for (String standby : state.standbys()) {
            paused.add(standby); // Own recovery even if the successful SQL response is lost.
            topology.sql(standby, "SELECT pg_wal_replay_pause()");
            topology.awaitSql(standby, "SELECT pg_get_wal_replay_pause_state()", "paused");
        }
    }

    void resumeReplay() throws IOException, InterruptedException {
        IOException failure = null;
        for (String standby : List.copyOf(paused)) {
            try {
                topology.sql(standby, "SELECT pg_wal_replay_resume()");
                topology.awaitSql(standby, "SELECT pg_get_wal_replay_pause_state()", "not paused");
                paused.remove(standby);
            } catch (IOException problem) {
                if (failure == null) failure = problem;
                else failure.addSuppressed(problem);
            }
        }
        if (failure != null) throw failure;
    }

    private JsonNode ownedCluster() throws IOException, InterruptedException {
        JsonNode cluster = topology.cluster(state.name());
        equal(cluster.path("metadata").path("uid").asText(), clusterUid, "Cluster identity during standby drill");
        var current = KindDatabaseDrill.fenceState(cluster);
        require(current.equals(original) || issued.contains(current),
                "Fence changed outside this drill; refusing to overwrite it on " + state.name());
        return cluster;
    }

    private void patchFence(JsonNode cluster, KindDatabaseDrill.FenceState target) throws IOException, InterruptedException {
        JsonNode metadata = cluster.path("metadata");
        String uid = metadata.path("uid").asText();
        String version = metadata.path("resourceVersion").asText();
        require(!uid.isBlank() && !version.isBlank(), "Cluster has no identity/version for a conditional fence change on " + state.name());
        var patch = JsonNodeFactory.instance.arrayNode();
        // Kubernetes applies JSON Patch tests and mutation atomically. In particular, an absent
        // annotation cannot be tested directly, so the resource version protects its creation too.
        patch.addObject().put("op", "test").put("path", "/metadata/uid").put("value", uid);
        patch.addObject().put("op", "test").put("path", "/metadata/resourceVersion").put("value", version);
        String path = "/metadata/annotations/cnpg.io~1fencedInstances";
        if (target.present()) {
            if (metadata.path("annotations").isMissingNode()) {
                patch.addObject().put("op", "add").put("path", "/metadata/annotations")
                        .putObject("value").put(FENCE, target.value());
            } else {
                patch.addObject().put("op", "add").put("path", path).put("value", target.value());
            }
        } else {
            patch.addObject().put("op", "remove").put("path", path);
        }
        lab.kubectl("patch", "cluster", state.name(), "--type=json", "-p", patch.toString());
    }

}
