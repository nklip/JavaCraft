package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static dev.nklip.javacraft.shardshop.testing.kind.KindTopology.equal;
import static dev.nklip.javacraft.shardshop.testing.kind.KindTopology.require;

/** Explicitly selected, reversible database disruption against the existing kind lab. */
public final class KindDatabaseDrill {
    private static final String FENCE = "cnpg.io/fencedInstances";
    private final KindLab lab;
    private final KindTopology topology;

    public KindDatabaseDrill() { this(new KindLab()); }
    KindDatabaseDrill(KindLab lab) { this(lab, new KindTopology(lab)); }
    KindDatabaseDrill(KindLab lab, KindTopology topology) { this.lab = lab; this.topology = topology; }

    public void verify() throws IOException, InterruptedException {
        String shard = selectShard(lab.shards(), System.getProperty("shardshop.kind.drillShard"));
        require(lab.inventory().path("instances").asInt() == 3, "The quorum drill requires three instances per shard");
        String probe = "topology_probe_" + UUID.randomUUID().toString().replace("-", "");
        AtomicReference<Writer> writer = new AtomicReference<>();
        // One shared lock also protects workload drills, whose observations span every shard.
        try (KindDrillGuard guard = new KindDrillGuard(lab).acquire()) {
            guard.run(() -> {
                JsonNode originalCluster = topology.cluster(shard);
                rejectPreexistingFence(originalCluster);
                Fencing fencing = new Fencing(fenceState(originalCluster), new HashSet<>());
                topology.publishedTopology();
                Map<String, KindTopology.Shard> initial = topology.observe();
                topology.routing();
                KindTopology.Shard state = initial.get(shard);
                require(state != null && state.standbys().size() == 2, "Selected shard has no three-instance topology");
                String primary = state.primary();
                List<String> standbys = state.standbys();
                // LIFO cleanup: restore quorum, stop/join the writer, then remove only drill-owned SQL.
                // Each callback exists before any mutation, including a command whose response may be lost.
                guard.onClose("remove database drill schema/publication/slot " + probe,
                        () -> cleanSql(shard, probe));
                guard.onClose("stop database drill writer " + probe, () -> stopWriter(shard, probe, writer.get()));
                guard.onClose("restore both standbys on " + shard, () -> restore(shard, state, fencing));

                topology.sql(primary, "CREATE SCHEMA " + probe + "; CREATE TABLE " + probe
                        + ".rows (id bigint PRIMARY KEY, phase text NOT NULL); CREATE PUBLICATION " + probe + " FOR TABLE " + probe + ".rows;");
                equal(topology.sql(primary, "INSERT INTO " + probe + ".rows VALUES (1, 'initial') RETURNING id::text"), "1", "Probe insertion");
                for (String standby : standbys) {
                    topology.awaitSql(standby, phase(probe), "initial");
                    require(readOnlyRejection(topology.sqlAttempt(standby,
                            "UPDATE " + probe + ".rows SET phase='forbidden' WHERE id=1")),
                            standby + " did not reject a write with SQLSTATE 25006");
                    equal(topology.sql(standby, phase(probe)), "initial", "Rejected standby write preserves data");
                }
                isolation(initial, shard, probe);
                synchronizeSlot(primary, standbys, probe);
                replaceStandby(state, probe);

                fence(shard, primary, List.of(standbys.getFirst()), fencing);
                notReady(standbys.getFirst());
                topology.awaitSql(primary, "SELECT count(*) FROM pg_stat_replication WHERE state='streaming'", "1");
                equal(topology.sql(primary, "UPDATE " + probe + ".rows SET phase='one-standby' WHERE id=1 RETURNING phase"),
                        "one-standby", "Commit with one standby fenced");
                topology.awaitSql(standbys.getLast(), phase(probe), "one-standby");

                fence(shard, primary, standbys, fencing);
                notReady(standbys.getLast());
                topology.awaitSql(primary, "SELECT count(*) FROM pg_stat_replication", "0");
                Writer pending = startWriter(primary, probe);
                writer.set(pending);
                topology.awaitSql(primary, "SELECT count(*) FROM pg_stat_activity WHERE application_name='" + probe
                        + "' AND wait_event='SyncRep'", "1");
                require(!pending.result().isDone(), "Writer exited instead of waiting for synchronous replication");

                fence(shard, primary, List.of(standbys.getFirst()), fencing);
                topology.ready("pod/" + standbys.getLast());
                equal(waitWriter(pending), "committed", "Pending commit after one standby is restored");
                topology.awaitSql(standbys.getLast(), phase(probe), "recovered");
                restore(shard, state, fencing);
                for (String standby : standbys) topology.awaitSql(standby, phase(probe), "recovered");
                isolation(initial, shard, probe);
                topology.observe();
            });
        }
    }

    static String selectShard(List<String> shards, String requested) throws IOException {
        KindTopology.validateShards(shards);
        String shard = requested == null ? shards.getFirst() : requested;
        require(shards.contains(shard), "shardshop.kind.drillShard must name a shard in the invocation inventory");
        return shard;
    }

    static void rejectPreexistingFence(JsonNode cluster) throws IOException {
        String existing = cluster.path("metadata").path("annotations").path(FENCE).asText();
        require(existing.isEmpty() || existing.equals("[]"), "Cluster already has fenced instances; recover it before the database drill");
    }

    static boolean readOnlyRejection(KindLab.CommandResult result) {
        return result.exitCode() != 0 && java.util.regex.Pattern.compile("(?<![0-9])25006(?![0-9])").matcher(result.stderr()).find();
    }

    private void synchronizeSlot(String primary, List<String> standbys, String probe) throws IOException, InterruptedException {
        equal(topology.sql(primary, "SELECT slot_name FROM pg_create_logical_replication_slot('" + probe
                + "', 'pgoutput', false, false, true)"), probe, "Failover slot creation");
        // These retries mutate probe rows and consume WAL. Keep them on the guarded drill thread:
        // asynchronous probe cancellation could otherwise overlap those mutations with recovery.
        long deadline = System.nanoTime() + KindTopology.CONVERGENCE.toNanos();
        IOException lastFailure = null;
        for (;;) {
            try {
                equal(slotSql(deadline, primary, "UPDATE " + probe + ".rows SET phase='initial' WHERE id=1 RETURNING id::text"), "1", "CDC probe write");
                equal(slotSql(deadline, primary, "SELECT pg_log_standby_snapshot() IS NOT NULL"), "t", "Standby snapshot");
                equal(slotSql(deadline, primary, "SELECT count(*) > 0 FROM pg_logical_slot_get_binary_changes('" + probe
                        + "', NULL, NULL, 'proto_version', '1', 'publication_names', '" + probe + "')"), "t", "CDC probe consumption");
                boolean synchronizedSlots = true;
                for (String standby : standbys) {
                    synchronizedSlots &= slotSql(deadline, standby, "SELECT count(*) FROM pg_replication_slots WHERE slot_name='"
                            + probe + "' AND synced AND failover AND NOT temporary AND invalidation_reason IS NULL").equals("1");
                }
                if (synchronizedSlots) break;
            } catch (IOException failure) {
                lastFailure = failure;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new IOException("Timed out waiting for persistent failover slot on both standbys", lastFailure);
            TimeUnit.NANOSECONDS.sleep(Math.min(remaining, Duration.ofMillis(500).toNanos()));
        }
        topology.sql(primary, "SELECT pg_drop_replication_slot('" + probe + "')");
        for (String standby : standbys) topology.awaitSql(standby,
                "SELECT count(*) FROM pg_replication_slots WHERE slot_name='" + probe + "'", "0");
    }

    private String slotSql(long deadline, String pod, String statement) throws IOException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Database slot drill interrupted");
        require(System.nanoTime() < deadline, "Persistent failover slot synchronization exceeded its deadline");
        String result = topology.sql(pod, statement);
        require(System.nanoTime() < deadline, "Persistent failover slot synchronization exceeded its deadline");
        return result;
    }

    private void replaceStandby(KindTopology.Shard state, String probe) throws IOException, InterruptedException {
        equal(topology.primary(state.name()), state.primary(), "Primary before standby replacement");
        String standby = state.standbys().getFirst();
        String oldUid = podUid(standby);
        require(!oldUid.isBlank(), "Standby pod has no identity before replacement");
        lab.kubectl(Duration.ofSeconds(80), "--request-timeout=70s", "delete", "pod", standby, "--wait=true", "--timeout=60s");
        lab.await("replacement standby pod identity", KindTopology.CONVERGENCE, () -> {
            String uid = podUid(standby);
            return !uid.isBlank() && !uid.equals(oldUid);
        });
        topology.ready("pod/" + standby);
        equal(topology.volumes(state.name()), state.volumes(), "PVC identities after standby replacement");
        equal(topology.primary(state.name()), state.primary(), "Primary after standby replacement");
        topology.awaitSql(standby, phase(probe), "initial");
        topology.streaming(state.primary(), state.standbys());
    }

    private String podUid(String standby) throws IOException, InterruptedException {
        return lab.kubectl("get", "pod", standby, "--ignore-not-found", "-o", "jsonpath={.metadata.uid}").strip();
    }

    private void isolation(Map<String, KindTopology.Shard> shards, String drilled, String probe) throws IOException, InterruptedException {
        for (var entry : shards.entrySet()) {
            if (entry.getKey().equals(drilled)) continue;
            KindTopology.Shard peer = entry.getValue();
            equal(topology.sql(peer.primary(), "SELECT to_regnamespace('" + probe + "') IS NULL"), "t", "Peer primary isolation");
            for (String standby : peer.standbys()) {
                equal(topology.sql(standby, "SELECT to_regnamespace('" + probe + "') IS NULL"), "t", "Peer standby isolation");
            }
        }
    }

    private void fence(String shard, String primary, List<String> standbys, Fencing fencing) throws IOException, InterruptedException {
        equal(topology.primary(shard), primary, "Primary during quorum drill");
        JsonNode cluster = topology.cluster(shard);
        FenceState current = fenceState(cluster);
        require(current.equals(fencing.original()) || fencing.issued().contains(current),
                "Fence changed outside this drill; refusing to overwrite it on " + shard);
        String value = "[" + String.join(",", standbys.stream().map(name -> "\"" + name + "\"").toList()) + "]";
        FenceState target = new FenceState(true, value);
        fencing.issued().add(target);
        patchFence(shard, cluster, target);
    }

    private void notReady(String standby) throws IOException, InterruptedException {
        lab.kubectl(Duration.ofSeconds(140), "--request-timeout=130s", "wait", "--for=condition=Ready=false", "pod/" + standby, "--timeout=120s");
    }

    private boolean restore(String shard, KindTopology.Shard state, Fencing fencing) throws IOException, InterruptedException {
        JsonNode cluster = topology.cluster(shard);
        FenceState current = fenceState(cluster);
        require(current.equals(fencing.original()) || fencing.issued().contains(current),
                "Fence changed outside this drill; inspect it before restoring " + shard);
        if (!current.equals(fencing.original())) {
            patchFence(shard, cluster, fencing.original());
        }
        topology.ready("cluster/" + shard);
        for (String standby : state.standbys()) topology.ready("pod/" + standby);
        equal(topology.primary(shard), state.primary(), "Primary after quorum recovery");
        topology.streaming(state.primary(), state.standbys());
        topology.endpoints(shard + "-ro", state.standbys());
        equal(topology.volumes(shard), state.volumes(), "PVC identities after quorum recovery");
        return true;
    }

    private void patchFence(String shard, JsonNode cluster, FenceState target) throws IOException, InterruptedException {
        JsonNode metadata = cluster.path("metadata");
        String uid = metadata.path("uid").asText();
        String version = metadata.path("resourceVersion").asText();
        require(!uid.isBlank() && !version.isBlank(), "Cluster has no identity/version for a conditional fence change on " + shard);
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
        lab.kubectl("patch", "cluster", shard, "--type=json", "-p", patch.toString());
    }

    private Writer startWriter(String primary, String probe) {
        CountDownLatch finished = new CountDownLatch(1);
        FutureTask<KindLab.CommandResult> task = new FutureTask<>(() -> {
            try {
                return lab.kubectlAttempt(Duration.ofSeconds(150), "--request-timeout=140s", "exec", primary,
                        "-c", "postgres", "--", "env", "PGAPPNAME=" + probe,
                        "PGOPTIONS=-c statement_timeout=120000 -c lock_timeout=5000", "psql", "-X", "-qAt", "-v",
                        "ON_ERROR_STOP=1", "-U", "postgres", "-d", "shardshop", "-c", "BEGIN; UPDATE " + probe
                                + ".rows SET phase='recovered' WHERE id=1; COMMIT; SELECT 'committed';");
            } finally {
                finished.countDown();
            }
        });
        Thread runner = Thread.ofVirtual().unstarted(task);
        Writer writer = new Writer(task, runner, finished);
        runner.start();
        return writer;
    }

    private static String waitWriter(Writer writer) throws IOException, InterruptedException {
        try {
            return KindTopology.successfulSql(writer.result().get(130, TimeUnit.SECONDS));
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof InterruptedException interrupted) throw interrupted;
            throw new IOException("Database drill writer failed", failure.getCause());
        } catch (TimeoutException failure) {
            throw new IOException("Database drill writer did not complete after one standby recovered", failure);
        }
    }

    private boolean stopWriter(String shard, String probe, Writer writer) throws IOException, InterruptedException {
        if (writer == null) return true;
        String primary;
        // Cancelling kubectl alone can leave its remote PostgreSQL session waiting in SyncRep.
        // Terminate the named backend first if recovery has not already released it.
        try {
            primary = topology.primary(shard);
            topology.sql(primary, "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name='" + probe + "'");
        } finally {
            if (!writer.result().isDone()) writer.runner().interrupt();
            boolean interrupted = Thread.interrupted();
            try {
                require(writer.finished().await(10, TimeUnit.SECONDS), "Database drill writer did not stop during cleanup");
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
        equal(topology.sql(primary, "SELECT count(*) FROM pg_stat_activity WHERE application_name='" + probe + "'"), "0", "Database drill writer cleanup");
        return true;
    }

    private boolean cleanSql(String shard, String probe) throws IOException, InterruptedException {
        topology.sql(topology.primary(shard), "SET client_min_messages=warning; SELECT pg_drop_replication_slot(slot_name) FROM pg_replication_slots WHERE slot_name='"
                + probe + "'; DROP PUBLICATION IF EXISTS " + probe + "; DROP TABLE IF EXISTS " + probe + ".rows; DROP SCHEMA IF EXISTS " + probe + ";");
        return true;
    }

    static FenceState fenceState(JsonNode cluster) {
        JsonNode annotation = cluster.path("metadata").path("annotations").path(FENCE);
        return new FenceState(!annotation.isMissingNode(), annotation.asText());
    }

    private record Fencing(FenceState original, Set<FenceState> issued) { }
    record FenceState(boolean present, String value) { }
    private static String phase(String probe) { return "SELECT phase FROM " + probe + ".rows WHERE id=1"; }
    private record Writer(FutureTask<KindLab.CommandResult> result, Thread runner, CountDownLatch finished) { }
}
