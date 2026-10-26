package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static dev.nklip.javacraft.shardshop.testing.kind.KindTopology.equal;
import static dev.nklip.javacraft.shardshop.testing.kind.KindTopology.require;

/** Guard-owned CNPG transitions. Faults retain deletion tombstones, never delete data volumes. */
final class KindFailoverCluster {
    private static final String FENCE = "cnpg.io/fencedInstances";
    private static final String ROLE_SQL = "SELECT pg_is_in_recovery(), current_setting('transaction_read_only')";
    private static final String LINEAGE_SQL = "SELECT system_identifier FROM pg_control_system()";
    static final String LOGICAL_SLOTS_SQL = "SELECT coalesce(jsonb_agg(to_jsonb(s) ORDER BY slot_name), '[]'::jsonb)::text"
            + " FROM pg_replication_slots s WHERE slot_type='logical'";
    static final String FAILOVER_SLOTS_SQL = LOGICAL_SLOTS_SQL + " AND failover AND NOT temporary";
    private final KindLab lab;
    private final KindTopology topology;
    private final KindDrillGuard guard;
    private final String shard;
    private final KindTopology.Shard initial;
    private final List<String> instances;
    private final String clusterUid;
    private final String lineage;
    private final String finalizer = "shardshop.javacraft/failover-" + UUID.randomUUID();
    private final KindDatabaseDrill.FenceState originalFence;
    private final Set<KindDatabaseDrill.FenceState> issuedFences = new HashSet<>();
    private final Map<String, String> heldPods = new LinkedHashMap<>();
    private final Map<String, JsonNode> formerPrimarySlots = new LinkedHashMap<>();
    private String slotWitness;

    KindFailoverCluster(KindLab lab, KindTopology topology, KindDrillGuard guard, String shard,
                        KindTopology.Shard initial) throws IOException, InterruptedException {
        this.lab = lab;
        this.topology = topology;
        this.guard = guard;
        this.shard = shard;
        this.initial = initial;
        require(initial.name().equals(shard) && initial.standbys().size() == 2, "Failover requires an observed three-instance shard");
        instances = java.util.stream.Stream.concat(java.util.stream.Stream.of(initial.primary()), initial.standbys().stream())
                .sorted().toList();
        require(new HashSet<>(instances).size() == 3, "Failover instance identities must be distinct");
        require(shard.matches("[a-z][a-z0-9-]*") && instances.stream().allMatch(name ->
                name.matches(java.util.regex.Pattern.quote(shard) + "-[0-9]+")), "Invalid failover instance names");
        JsonNode cluster = topology.cluster(shard);
        KindDatabaseDrill.rejectPreexistingFence(cluster);
        require(cluster.path("spec").path("replicationSlots").path("highAvailability")
                        .path("synchronizeLogicalDecoding").asBoolean(),
                "CNPG-managed logical slot synchronization must be enabled before the failover drill");
        clusterUid = cluster.path("metadata").path("uid").asText();
        require(!clusterUid.isBlank(), "Missing cluster identity before failover drill");
        originalFence = KindDatabaseDrill.fenceState(cluster);
        lineage = topology.sql(initial.primary(), LINEAGE_SQL);
        require(lineage.matches("[0-9]+"), "Missing database lineage before failover drill");
        guard.onClose("restore failover faults and original primary on " + shard, () -> {
            restoreStandbys();
            if (!topology.primary(shard).equals(initial.primary())) switchover(initial.primary());
            assertHealthy();
            return true;
        });
    }

    String switchover(String target) throws IOException, InterruptedException {
        transitionReady();
        String previous = topology.primary(shard);
        require(instances.contains(target) && !previous.equals(target), "Switchover target must be a current standby");
        equal(topology.sql(target, ROLE_SQL), "t|on", "Switchover candidate role");
        // Capture once: a running CDC consumer can continue advancing the primary.
        // Recovery switchback must preserve at least this durable consumer position.
        JsonNode requiredSlots = KindLab.parse(topology.sql(previous, FAILOVER_SLOTS_SQL));
        validateRequiredSlots(requiredSlots);
        lab.await("synchronized failover logical slots before switchover to " + target, KindTopology.CONVERGENCE,
                () -> synchronizedSlots(requiredSlots, KindLab.parse(topology.sql(target, LOGICAL_SLOTS_SQL))));
        JsonNode cluster = ownedCluster();
        equal(cluster.path("status").path("currentPrimary").asText(), previous, "Primary before switchover");
        equal(cluster.path("status").path("targetPrimary").asText(), previous, "No transition before switchover");
        formerPrimarySlots.put(previous, requiredSlots.deepCopy());
        ArrayNode patch = identityPatch(cluster);
        // The CNPG 1.30.1 promote plugin uses these status fields. CNPG performs demotion,
        // WAL receiver shutdown and promotion; this never invokes pg_promote/pg_ctl.
        add(patch, "/status/targetPrimary", target);
        add(patch, "/status/targetPrimaryTimestamp", Instant.now().toString());
        add(patch, "/status/phase", "Switchover in progress");
        add(patch, "/status/phaseReason", "Switching over to " + target);
        lab.kubectl("patch", "cluster", shard, "--subresource=status", "--type=json", "-p", patch.toString());
        awaitPrimary(previous, target);
        setFence(originalFence);
        reconcileFormerPrimaries();
        assertHealthy();
        return target;
    }

    String failPrimary() throws IOException, InterruptedException {
        transitionReady();
        String previous = topology.primary(shard);
        rememberPrimarySlots(previous);
        holdDeleted(previous);
        // With one standby fenced, quorum forbids failover. Restore it only after the
        // old primary has stopped, preserving the parent's paused CDC witness boundary.
        setFence(originalFence);
        String promoted = awaitPrimary(previous, null);
        releaseHeldPods();
        reconcileFormerPrimaries();
        assertHealthy();
        equal(topology.sql(previous, ROLE_SQL), "t|on", "Former primary rejoined as a standby");
        return promoted;
    }

    void assertHealthy() throws IOException, InterruptedException {
        ownedCluster();
        require(heldPods.isEmpty(), "Restore held instance deletions before a healthy topology check");
        topology.ready("cluster/" + shard);
        String primary = topology.primary(shard);
        require(instances.contains(primary), "CNPG selected an instance outside the original topology");
        List<String> standbys = instances.stream().filter(name -> !name.equals(primary)).toList();
        for (String pod : instances) {
            topology.ready("pod/" + pod);
            equal(topology.sql(pod, LINEAGE_SQL), lineage, pod + " preserved database lineage");
            equal(topology.sql(pod, ROLE_SQL), pod.equals(primary) ? "f|off" : "t|on", pod + " exclusive database role");
        }
        topology.streaming(primary, standbys);
        topology.endpoints(shard + "-rw", List.of(primary));
        topology.endpoints(shard + "-ro", standbys);
        equal(topology.volumes(shard), initial.volumes(), "Exact PVC identities and backing volumes after failover");
    }

    void fenceStandby(String pod) throws IOException, InterruptedException {
        String primary = topology.primary(shard);
        require(instances.contains(pod) && !primary.equals(pod), "Only a current standby may be fenced");
        require(KindDatabaseDrill.fenceState(ownedCluster()).equals(originalFence), "Restore the existing drill fence first");
        setFence(new KindDatabaseDrill.FenceState(true, "[\"" + pod + "\"]"));
        lab.kubectl(Duration.ofSeconds(140), "--request-timeout=130s", "wait", "--for=condition=Ready=false",
                "pod/" + pod, "--timeout=120s");
        topology.endpoints(shard + "-ro", instances.stream()
                .filter(name -> !name.equals(pod) && !name.equals(primary)).toList());
    }

    private void transitionReady() throws IOException, InterruptedException {
        JsonNode cluster = ownedCluster();
        require(heldPods.isEmpty(), "A previous failover fault still needs recovery");
        String primary = topology.primary(shard);
        equal(cluster.path("status").path("targetPrimary").asText(), primary, "No existing CNPG transition");
        equal(topology.sql(primary, ROLE_SQL), "f|off", "Current writable primary before transition");
        equal(topology.volumes(shard), initial.volumes(), "Preserved PVCs before transition");
        assertAtMostOneWritable();
    }

    void restoreStandbys() throws IOException, InterruptedException {
        releaseHeldPods();
        setFence(originalFence);
        reconcileFormerPrimaries();
        assertHealthy();
    }

    void prepareSlotWitness() throws IOException, InterruptedException {
        require(slotWitness == null, "A failover slot witness already exists");
        assertHealthy();
        String primary = topology.primary(shard);
        equal(primary, initial.primary(), "Slot witness must be prepared before the first promotion");
        String probe = "failover_slot_" + UUID.randomUUID().toString().replace("-", "");
        slotWitness = probe;
        guard.onClose("remove owned logical failover slot and publication " + probe, () -> {
            restoreStandbys();
            String current = topology.primary(shard);
            topology.sql(current, "SELECT pg_drop_replication_slot(slot_name) FROM pg_replication_slots WHERE slot_name=" + literal(probe));
            for (String pod : instances) topology.awaitSql(pod,
                    "SELECT count(*) FROM pg_replication_slots WHERE slot_name=" + literal(probe), "0");
            topology.sql(current, "SET client_min_messages=warning; DROP PUBLICATION IF EXISTS " + probe
                    + "; DROP SCHEMA IF EXISTS " + probe + " CASCADE");
            // The owned slot is intentionally gone before the final business-slot switchback.
            formerPrimarySlots.replaceAll((pod, slots) -> withoutSlot(slots, probe));
            return true;
        });
        topology.sql(primary, "CREATE SCHEMA " + probe + "; CREATE TABLE " + probe
                + ".rows (id integer PRIMARY KEY); CREATE PUBLICATION " + probe + " FOR TABLE " + probe + ".rows");
        equal(topology.sql(primary, "SELECT slot_name FROM pg_create_logical_replication_slot("
                + literal(probe) + ", 'pgoutput', false, false, true)"), probe, "Owned permanent failover slot creation");
        long deadline = System.nanoTime() + KindTopology.CONVERGENCE.toNanos();
        for (;;) {
            // Advance this slot through a consistent snapshot, as in KindDatabaseDrill.
            primeOwnedSlot(primary, deadline);
            JsonNode source = onlySlot(KindLab.parse(topology.sql(primary, FAILOVER_SLOTS_SQL)), probe);
            boolean synchronizedCopies = true;
            for (String standby : initial.standbys()) synchronizedCopies &= synchronizedSlots(source,
                    KindLab.parse(topology.sql(standby, LOGICAL_SLOTS_SQL)));
            if (synchronizedCopies) return;
            require(System.nanoTime() < deadline, "Owned failover slot did not synchronize within its deadline");
            TimeUnit.MILLISECONDS.sleep(500);
        }
    }

    private void rememberPrimarySlots(String primary) throws IOException, InterruptedException {
        JsonNode slots = KindLab.parse(topology.sql(primary, FAILOVER_SLOTS_SQL));
        validateRequiredSlots(slots);
        formerPrimarySlots.put(primary, slots.deepCopy());
    }

    private void reconcileFormerPrimaries() throws IOException, InterruptedException {
        String primary = topology.primary(shard);
        for (var entry : formerPrimarySlots.entrySet()) {
            if (!entry.getKey().equals(primary)) reconcileDemotedSlots(entry.getKey(), entry.getValue(), primary);
        }
    }

    private void reconcileDemotedSlots(String former, JsonNode known, String primary) throws IOException, InterruptedException {
        topology.ready("pod/" + former);
        topology.ready("pod/" + primary);
        requireStableRoles(former, primary);
        JsonNode authoritative = KindLab.parse(topology.sql(primary, FAILOVER_SLOTS_SQL));
        validateRequiredSlots(authoritative);
        Map<String, JsonNode> sources = slotMap(authoritative);
        Map<String, JsonNode> previous = slotMap(known);
        JsonNode local = KindLab.parse(topology.sql(former, LOGICAL_SLOTS_SQL));
        Map<String, JsonNode> localSlots = slotMap(local);
        for (JsonNode slot : local) require(previous.containsKey(slot.path("slot_name").asText()),
                "Unknown logical slot on former primary; refusing retirement");
        Map<String, String> retirements = new LinkedHashMap<>();
        for (JsonNode captured : known) {
            String name = captured.path("slot_name").asText();
            JsonNode source = sources.get(name);
            require(source != null && sameSlotIdentity(captured, source)
                            && lsn(source.path("confirmed_flush_lsn").asText()).compareTo(lsn(captured.path("confirmed_flush_lsn").asText())) >= 0,
                    "Authoritative logical slot is missing, changed identity or moved backwards");
            JsonNode old = localSlots.get(name);
            if (old == null || old.path("synced").asBoolean()) continue;
            retirements.put(name, retirementSql(captured, source, old));
        }
        // Validate the whole captured set before retiring any copy.
        for (var retirement : retirements.entrySet()) {
            requireStableRoles(former, primary);
            equal(topology.sql(former, retirement.getValue()), retirement.getKey() + "|",
                    "Retired only the observed obsolete former-primary slot");
        }
        if (slotWitness != null && sources.containsKey(slotWitness)) {
            // A freshly recreated owned slot can reserve a restart LSN beyond its idle
            // source. Advance only this probe until it can build a persistent snapshot.
            // Keep the captured authoritative boundary fixed and mutations on the guard
            // thread; an asynchronously cancelled lab.await probe must never consume WAL.
            long deadline = System.nanoTime() + KindTopology.CONVERGENCE.toNanos();
            for (;;) {
                requireStableRoles(former, primary);
                JsonNode observed = KindLab.parse(topology.sql(former, LOGICAL_SLOTS_SQL));
                if (synchronizedSlots(authoritative, observed)) break;
                require(System.nanoTime() < deadline, "Native synchronized logical slots on demoted primary exceeded deadline");
                JsonNode probe = slotMap(observed).get(slotWitness);
                if (probe == null || probe.path("temporary").asBoolean() || probe.path("confirmed_flush_lsn").isNull()) {
                    primeOwnedSlot(primary, deadline);
                }
                TimeUnit.MILLISECONDS.sleep(500);
            }
        } else {
            lab.await("native synchronized logical slots on demoted primary " + former, KindTopology.CONVERGENCE, () -> {
                requireStableRoles(former, primary);
                return synchronizedSlots(authoritative, KindLab.parse(topology.sql(former, LOGICAL_SLOTS_SQL)));
            });
        }
    }

    private void primeOwnedSlot(String primary, long deadline) throws IOException, InterruptedException {
        require(slotWitness != null, "No owned slot may be consumed");
        reserveProbeCommand(deadline);
        equal(topology.sql(primary, ROLE_SQL), "f|off", "Owned slot witness source is primary");
        reserveProbeCommand(deadline);
        topology.sql(primary, "INSERT INTO " + slotWitness + ".rows VALUES (1) ON CONFLICT (id) DO UPDATE SET id=1");
        reserveProbeCommand(deadline);
        topology.sql(primary, "SELECT pg_log_standby_snapshot() IS NOT NULL");
        String statement = "SELECT count(*) FROM pg_logical_slot_get_binary_changes(" + literal(slotWitness)
                + ", NULL, NULL, 'proto_version', '1', 'publication_names', " + literal(slotWitness) + ")";
        reserveProbeCommand(deadline);
        List<String> physicalSlots = physicalStandbySlots(primary);
        for (int attempt = 0; attempt < 3; attempt++) {
            awaitPhysicalStandbys(primary, physicalSlots, deadline);
            require(System.nanoTime() + Duration.ofSeconds(20).toNanos() <= deadline,
                    "Owned probe decode lacks time within its shared convergence deadline");
            // Verbose diagnostics are used only for this owned probe: sqlstate-only
            // output cannot distinguish this documented warning from other 55000s.
            KindLab.CommandResult result = lab.kubectlAttempt(Duration.ofSeconds(20), "exec", primary, "-c", "postgres", "--", "env",
                    "PGOPTIONS=-c statement_timeout=15000 -c lock_timeout=5000", "psql", "-X", "-qAt", "-v",
                    "ON_ERROR_STOP=1", "-v", "VERBOSITY=verbose", "-v", "SHOW_CONTEXT=never", "-U", "postgres",
                    "-d", "shardshop", "-c", statement);
            if (result.exitCode() == 0 && result.stderr().isBlank()) {
                KindTopology.successfulSql(result);
                return;
            }
            require(ownedStandbyWaitWarning(result, physicalSlots), "Owned probe decode failed or emitted an unexpected warning");
            require(attempt < 2, "Owned probe decode did not achieve warning-free success in three attempts");
            TimeUnit.MILLISECONDS.sleep(500);
        }
    }

    private List<String> physicalStandbySlots(String primary) throws IOException, InterruptedException {
        JsonNode cluster = ownedCluster();
        equal(cluster.path("status").path("currentPrimary").asText(), primary, "Stable primary before owned probe decode");
        equal(cluster.path("status").path("targetPrimary").asText(), primary, "No promotion during owned probe decode");
        String prefix = cluster.path("spec").path("replicationSlots").path("highAvailability").path("slotPrefix").asText("_cnpg_");
        return instances.stream().filter(name -> !name.equals(primary)).map(name ->
                (prefix + name).toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_]", "_")).sorted().toList();
    }

    private void awaitPhysicalStandbys(String primary, List<String> slots, long deadline) throws IOException, InterruptedException {
        String array = "ARRAY[" + String.join(",", slots.stream().map(KindFailoverCluster::literal).toList()) + "]::text[]";
        String statement = "SELECT count(*)=2 AND coalesce(bool_and(s.active AND r.state='streaming'),false)"
                + " FROM pg_replication_slots s JOIN pg_stat_replication r ON r.pid=s.active_pid"
                + " WHERE s.slot_type='physical' AND s.slot_name::text=ANY(" + array + ")"
                + " AND (SELECT array_agg(trim(both ' \"' from n) ORDER BY trim(both ' \"' from n))"
                + " FROM unnest(string_to_array(current_setting('synchronized_standby_slots'),',')) AS names(n))=" + array;
        for (;;) {
            reserveProbeCommand(deadline);
            equal(physicalStandbySlots(primary), slots, "Unchanged physical standby slot identities before decode");
            reserveProbeCommand(deadline);
            if (topology.sql(primary, statement).equals("t")) return;
            TimeUnit.MILLISECONDS.sleep(500);
        }
    }

    private static void reserveProbeCommand(long deadline) throws IOException {
        // KindTopology SQL and its metadata reads have the existing 30s command bound.
        require(System.nanoTime() + Duration.ofSeconds(30).toNanos() <= deadline,
                "Owned probe lacks time for another command within its shared convergence deadline");
    }

    static boolean ownedStandbyWaitWarning(KindLab.CommandResult result, List<String> physicalSlots) {
        if (result.exitCode() != 0 || !result.stdout().strip().matches("[0-9]+")) return false;
        var warning = java.util.regex.Pattern.compile("WARNING:\\s+55000:\\s+replication slot \"([^\"]+)\" specified in parameter \"synchronized_standby_slots\" does not have active_pid");
        boolean found = false;
        for (String line : result.stderr().lines().toList()) {
            if (line.isBlank()) continue;
            var match = warning.matcher(line.strip());
            if (match.matches() && physicalSlots.contains(match.group(1))) found = true;
            else if (!(line.startsWith("DETAIL:") || line.startsWith("HINT:") || line.startsWith("LOCATION:"))) return false;
        }
        return found;
    }

    private void requireStableRoles(String former, String primary) throws IOException, InterruptedException {
        JsonNode cluster = ownedCluster();
        equal(cluster.path("status").path("currentPrimary").asText(), primary, "Stable authoritative primary during slot retirement");
        equal(cluster.path("status").path("targetPrimary").asText(), primary, "No concurrent promotion during slot retirement");
        require(!former.equals(primary) && instances.contains(former), "Slot retirement target must be a known former primary");
        equal(topology.sql(primary, ROLE_SQL), "f|off", "Authoritative logical-slot server is primary");
        equal(topology.sql(former, ROLE_SQL), "t|on", "Obsolete logical-slot server is a standby");
        equal(topology.sql(primary, LINEAGE_SQL), lineage, "Authoritative slot lineage");
        equal(topology.sql(former, LINEAGE_SQL), lineage, "Former-primary slot lineage");
    }

    static String retirementSql(JsonNode captured, JsonNode source, JsonNode local) throws IOException {
        require(sameSlotIdentity(captured, source) && sameSlotIdentity(captured, local), "Logical slot identity changed before retirement");
        require(source.path("failover").asBoolean() && !source.path("temporary").asBoolean()
                        && source.path("invalidation_reason").isNull(), "Authoritative failover slot is invalid");
        require(local.path("failover").asBoolean() && !local.path("synced").asBoolean()
                        && local.has("active") && !local.path("active").asBoolean() && !local.path("temporary").asBoolean()
                        && local.path("invalidation_reason").isNull(), "Only an inactive valid nonsynchronized local slot may be retired");
        BigInteger old = lsn(local.path("confirmed_flush_lsn").asText());
        require(old.compareTo(lsn(captured.path("confirmed_flush_lsn").asText())) >= 0
                        && old.compareTo(lsn(source.path("confirmed_flush_lsn").asText())) <= 0,
                "Obsolete slot position is outside the captured authoritative lineage");
        String name = local.path("slot_name").asText();
        require(name.matches("[a-z0-9_]{1,63}"), "Invalid logical slot name");
        // One statement rechecks recovery role and every observed slot property at deletion.
        // It cannot drop an active primary slot or an automatically synchronized copy.
        return "SELECT slot_name, pg_drop_replication_slot(slot_name) FROM pg_replication_slots WHERE pg_is_in_recovery() AND slot_type='logical'"
                + " AND slot_name=" + literal(name) + " AND database=" + literal(local.path("database").asText())
                + " AND plugin=" + literal(local.path("plugin").asText())
                + " AND failover AND NOT synced AND NOT active AND NOT temporary AND invalidation_reason IS NULL"
                + " AND confirmed_flush_lsn=" + literal(local.path("confirmed_flush_lsn").asText()) + "::pg_lsn";
    }

    private static boolean sameSlotIdentity(JsonNode first, JsonNode second) {
        return first.path("slot_name").equals(second.path("slot_name")) && first.path("database").equals(second.path("database"))
                && first.path("plugin").equals(second.path("plugin"));
    }

    private static Map<String, JsonNode> slotMap(JsonNode slots) throws IOException {
        require(slots.isArray(), "Logical slot observation must be an array");
        Map<String, JsonNode> result = new LinkedHashMap<>();
        for (JsonNode slot : slots) require(result.put(slot.path("slot_name").asText(), slot) == null, "Duplicate logical slot identity");
        return result;
    }

    private static JsonNode onlySlot(JsonNode slots, String name) {
        ArrayNode result = JsonNodeFactory.instance.arrayNode();
        slots.forEach(slot -> { if (slot.path("slot_name").asText().equals(name)) result.add(slot); });
        return result;
    }

    private static JsonNode withoutSlot(JsonNode slots, String name) {
        ArrayNode result = JsonNodeFactory.instance.arrayNode();
        slots.forEach(slot -> { if (!slot.path("slot_name").asText().equals(name)) result.add(slot); });
        return result;
    }

    private static String literal(String value) { return "'" + value.replace("'", "''") + "'"; }

    void unsafeSurvivor() throws IOException, InterruptedException { unsafeSurvivor(() -> { }); }

    void unsafeSurvivor(KindDrillGuard.Drill whileIsolated) throws IOException, InterruptedException {
        assertHealthy();
        String primary = topology.primary(shard);
        List<String> standbys = instances.stream().filter(name -> !name.equals(primary)).toList();
        String survivor = standbys.getFirst();
        String aligned = standbys.getLast();
        String probe = "failover_probe_" + UUID.randomUUID().toString().replace("-", "");
        guard.onClose("remove failover witness " + probe, () -> {
            topology.sql(topology.primary(shard), "SET client_min_messages=warning; DROP SCHEMA IF EXISTS " + probe + " CASCADE");
            return true;
        });
        guard.onClose("restore failover witness faults on " + shard, () -> { restoreStandbys(); return true; });
        requireQuorum(primary, standbys);
        topology.sql(primary, "CREATE SCHEMA " + probe + "; CREATE TABLE " + probe + ".witness (id integer PRIMARY KEY)");
        for (String standby : standbys) topology.awaitSql(standby, "SELECT to_regclass('" + probe + ".witness') IS NOT NULL", "t");
        fenceStandby(survivor);
        topology.awaitSql(primary, "SELECT count(*)" + KindTopology.STANDBY_SENDERS_SQL + " AND state='streaming'", "1");
        equal(topology.sql(primary, "INSERT INTO " + probe + ".witness VALUES (1) RETURNING id::text"), "1", "Synchronous witness commit");
        topology.awaitSql(aligned, "SELECT count(*) FROM " + probe + ".witness", "1");
        whileIsolated.run();
        requireQuorum(primary, standbys);
        rememberPrimarySlots(primary);
        // The confirmed copy is made unavailable first. Primary fencing would suppress
        // failover, so hold actual pod deletions instead and let CNPG evaluate its quorum.
        holdDeleted(aligned);
        holdDeleted(primary);
        setFence(originalFence);
        topology.ready("pod/" + survivor);
        equal(topology.sql(survivor, "SELECT count(*) FROM " + probe + ".witness"), "0", "Survivor actually lacks an acknowledged commit");
        observeUnconfirmed(primary, survivor, noPromotionWindow(ownedCluster()));
        restoreStandbys();
        for (String pod : instances) topology.awaitSql(pod, "SELECT count(*) FROM " + probe + ".witness", "1");
    }

    static Duration noPromotionWindow(JsonNode cluster) throws IOException {
        int delay = cluster.path("spec").path("failoverDelay").asInt(0);
        require(delay >= 0 && delay <= 60, "Failover delay exceeds the bounded drill prerequisite (60 seconds)");
        return KindTopology.CONVERGENCE.plusSeconds(delay);
    }

    private void observeUnconfirmed(String primary, String survivor, Duration window) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + window.toNanos();
        do {
            JsonNode cluster = ownedCluster();
            assertUnconfirmed(cluster, primary, survivor);
            equal(topology.sql(survivor, ROLE_SQL), "t|on", "Unconfirmed survivor stays read-only");
            JsonNode slices = KindLab.parse(lab.kubectl("get", "endpointslices", "-l",
                    "kubernetes.io/service-name=" + shard + "-rw", "-o", "json"));
            require(KindTopology.readyEndpoints(slices).isEmpty(), "Unsafe survivor must not receive writable service traffic");
            for (var held : heldPods.entrySet()) requireTerminated(held.getKey(), held.getValue());
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) break;
            TimeUnit.NANOSECONDS.sleep(Math.min(remaining, Duration.ofMillis(500).toNanos()));
        } while (true);
    }

    static void assertUnconfirmed(JsonNode cluster, String previous, String survivor) throws IOException {
        equal(cluster.path("status").path("currentPrimary").asText(), previous, "No unconfirmed survivor promotion");
        String target = cluster.path("status").path("targetPrimary").asText();
        require(target.equals(previous) || target.equals("pending"), "CNPG must not nominate the unconfirmed survivor " + survivor);
    }

    private void requireQuorum(String primary, List<String> standbys) throws IOException, InterruptedException {
        JsonNode cluster = ownedCluster();
        require(cluster.path("spec").path("postgresql").path("synchronous").path("failoverQuorum").asBoolean(),
                "Failover quorum must be enabled");
        String legacy = cluster.path("metadata").path("annotations").path("alpha.cnpg.io/failoverQuorum").asText();
        require(legacy.isEmpty() || legacy.equals("true"), "Legacy annotation disables failover quorum");
        noPromotionWindow(cluster);
        JsonNode status = KindLab.parse(lab.kubectl("get", "failoverquorum", shard, "-o", "json")).path("status");
        List<String> names = new ArrayList<>();
        status.path("standbyNames").forEach(name -> names.add(name.asText()));
        equal(status.path("primary").asText(), primary, "Failover quorum primary");
        require(status.path("method").asText().equalsIgnoreCase("any"), "Failover quorum must use ANY replication");
        equal(status.path("standbyNumber").asInt(), 1, "Failover write quorum");
        equal(names.stream().sorted().toList(), standbys, "Failover quorum includes both potential synchronous standbys");
    }

    private String awaitPrimary(String previous, String target) throws IOException, InterruptedException {
        lab.await("CNPG promotion on " + shard, KindTopology.CONVERGENCE, () -> {
            assertAtMostOneWritable();
            String current = ownedCluster().path("status").path("currentPrimary").asText();
            return !current.equals(previous) && instances.contains(current) && (target == null || target.equals(current));
        });
        return topology.primary(shard);
    }

    void assertAtMostOneWritable() throws IOException, InterruptedException {
        List<String> writable = writableInstances(instances);
        // Sequential observations can straddle demotion/promotion. Recheck the two
        // candidates immediately before declaring split brain. This is bounded role
        // sampling, not an atomic cross-instance observation or a continuous proof.
        if (writable.size() > 1) writable = writableInstances(writable);
        // lab.await retries IO errors; a split brain is a terminal failed assertion.
        if (writable.size() > 1) throw new IllegalStateException("Multiple writable primaries observed on " + shard);
    }

    private List<String> writableInstances(List<String> pods) throws IOException, InterruptedException {
        List<String> writable = new ArrayList<>();
        for (String pod : pods) {
            KindLab.CommandResult attempt = topology.sqlAttempt(pod, ROLE_SQL);
            if (attempt.exitCode() == 0 && attempt.stderr().isBlank() && attempt.stdout().strip().equals("f|off")) writable.add(pod);
        }
        return writable;
    }

    private void holdDeleted(String pod) throws IOException, InterruptedException {
        ownedCluster();
        JsonNode object = pod(pod);
        String uid = object.path("metadata").path("uid").asText();
        require(!uid.isBlank() && !object.path("metadata").hasNonNull("deletionTimestamp"), "Instance is already deleting or missing: " + pod);
        List<String> finalizers = strings(object.path("metadata").path("finalizers"));
        require(!finalizers.contains(finalizer), "Unexpected preexisting drill finalizer");
        heldPods.put(pod, uid); // Own recovery before an API response can be lost.
        ArrayNode patch = identityPatch(object);
        ArrayNode target = JsonNodeFactory.instance.arrayNode();
        finalizers.forEach(target::add);
        target.add(finalizer);
        patch.addObject().put("op", "add").put("path", "/metadata/finalizers").set("value", target);
        lab.kubectl("patch", "pod", pod, "--type=json", "-p", patch.toString());
        // DELETE preconditions are essential: a name alone could delete a replacement
        // between the last GET and DELETE. kubectl's raw endpoint accepts DeleteOptions.
        JsonNode beforeDelete = pod(pod);
        equal(beforeDelete.path("metadata").path("uid").asText(), uid, "Pod identity before deletion");
        require(strings(beforeDelete.path("metadata").path("finalizers")).contains(finalizer),
                "Owned deletion finalizer disappeared before the fault");
        var options = JsonNodeFactory.instance.objectNode().put("apiVersion", "v1").put("kind", "DeleteOptions")
                .put("gracePeriodSeconds", 30);
        options.putObject("preconditions").put("uid", uid)
                .put("resourceVersion", beforeDelete.path("metadata").path("resourceVersion").asText());
        Path request = Files.createTempFile(lab.snapshot(), "failover-delete-", ".json");
        try {
            Files.writeString(request, options.toString());
            lab.kubectl(Duration.ofSeconds(45), "--request-timeout=35s", "delete", "--raw",
                    "/api/v1/namespaces/shardshop/pods/" + pod, "-f", request.toString());
        } finally {
            Files.deleteIfExists(request);
        }
        lab.await("terminated held instance " + pod, KindTopology.CONVERGENCE, () -> {
            requireTerminated(pod, uid);
            return true;
        });
    }

    private void requireTerminated(String name, String uid) throws IOException, InterruptedException {
        JsonNode object = pod(name);
        equal(object.path("metadata").path("uid").asText(), uid, "Held pod identity");
        require(object.path("metadata").hasNonNull("deletionTimestamp"), "Held pod is not deleting");
        boolean postgres = false;
        for (JsonNode container : object.path("status").path("containerStatuses")) {
            if (container.path("name").asText().equals("postgres")) {
                postgres = container.path("state").has("terminated");
            }
        }
        require(postgres, "Held PostgreSQL container has not terminated on " + name);
    }

    private void releaseHeldPods() throws IOException, InterruptedException {
        ownedCluster();
        IOException failure = null;
        for (var entry : Map.copyOf(heldPods).entrySet()) {
            try {
                JsonNode object = pod(entry.getKey());
                if (object.isEmpty()) { heldPods.remove(entry.getKey()); continue; }
                if (!object.path("metadata").path("uid").asText().equals(entry.getValue())) {
                    require(!strings(object.path("metadata").path("finalizers")).contains(finalizer),
                            "Owned finalizer unexpectedly appears on a replacement pod");
                    heldPods.remove(entry.getKey());
                    continue; // A lost successful removal response can already have allowed replacement.
                }
                List<String> finalizers = strings(object.path("metadata").path("finalizers"));
                int index = finalizers.indexOf(finalizer);
                if (index >= 0) {
                    ArrayNode patch = identityPatch(object);
                    patch.addObject().put("op", "remove").put("path", "/metadata/finalizers/" + index);
                    lab.kubectl("patch", "pod", entry.getKey(), "--type=json", "-p", patch.toString());
                }
                heldPods.remove(entry.getKey());
            } catch (IOException problem) {
                if (failure == null) failure = problem;
                else failure.addSuppressed(problem);
            }
        }
        if (failure != null) throw failure;
    }

    private JsonNode pod(String name) throws IOException, InterruptedException {
        return KindLab.parse(lab.kubectl("get", "pod", name, "--ignore-not-found", "-o", "json"));
    }

    private JsonNode ownedCluster() throws IOException, InterruptedException {
        return requireOwnedCluster(topology.cluster(shard));
    }

    private JsonNode requireOwnedCluster(JsonNode cluster) throws IOException {
        equal(cluster.path("metadata").path("uid").asText(), clusterUid, "Original CNPG cluster identity");
        var current = KindDatabaseDrill.fenceState(cluster);
        require(current.equals(originalFence) || issuedFences.contains(current), "Foreign fence changed during failover drill");
        return cluster;
    }

    private void setFence(KindDatabaseDrill.FenceState target) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
        for (int attempt = 0; attempt < 3; attempt++) {
            JsonNode cluster = fenceCluster(deadline);
            if (KindDatabaseDrill.fenceState(cluster).equals(target)) return;
            issuedFences.add(target);
            ArrayNode patch = fenceIdentityPatch(cluster);
            String path = "/metadata/annotations/cnpg.io~1fencedInstances";
            if (!target.present()) patch.addObject().put("op", "remove").put("path", path);
            else if (!cluster.path("metadata").path("annotations").isObject()) {
                patch.addObject().put("op", "add").put("path", "/metadata/annotations").putObject("value").put(FENCE, target.value());
            } else add(patch, path, target.value());
            try {
                lab.kubectl(fenceTimeout(deadline), "patch", "cluster", shard, "--request-timeout=5s",
                        "--type=json", "-p", patch.toString());
                return;
            } catch (IOException failure) {
                JsonNode observed = fenceCluster(deadline); // Revalidates UID and fence ownership before any retry.
                if (KindDatabaseDrill.fenceState(observed).equals(target)) return; // Lost successful response.
                String before = cluster.path("metadata").path("resourceVersion").asText();
                String after = observed.path("metadata").path("resourceVersion").asText();
                if (before.equals(after)) throw new IOException("Fence change failed without an observed resourceVersion change; not retrying", failure);
                if (attempt == 2) throw new IOException("Fence change exhausted three conditional attempts after concurrent resourceVersion changes", failure);
                // Only observed concurrent updates justify retrying; generic failures do not.
                long backoff = Duration.ofMillis(50L * (attempt + 1)
                        + java.util.concurrent.ThreadLocalRandom.current().nextLong(50)).toNanos();
                require(System.nanoTime() + backoff < deadline, "Conditional fence recovery exceeded its total deadline");
                TimeUnit.NANOSECONDS.sleep(backoff);
            }
        }
    }

    private JsonNode fenceCluster(long deadline) throws IOException, InterruptedException {
        return requireOwnedCluster(KindLab.parse(lab.kubectl(fenceTimeout(deadline), "get", "cluster", shard,
                "--request-timeout=5s", "-o", "json")));
    }

    private static Duration fenceTimeout(long deadline) throws IOException {
        long remaining = deadline - System.nanoTime();
        require(remaining > 0, "Conditional fence change exceeded its total deadline");
        return Duration.ofNanos(Math.min(remaining, Duration.ofSeconds(8).toNanos()));
    }

    private static ArrayNode fenceIdentityPatch(JsonNode cluster) throws IOException {
        JsonNode metadata = cluster.path("metadata");
        String uid = metadata.path("uid").asText();
        require(!uid.isBlank(), "Fence mutation requires the original cluster UID");
        ArrayNode patch = JsonNodeFactory.instance.arrayNode();
        patch.addObject().put("op", "test").put("path", "/metadata/uid").put("value", uid);
        JsonNode annotations = metadata.path("annotations");
        JsonNode fence = annotations.path(FENCE);
        if (!fence.isMissingNode()) {
            patch.addObject().put("op", "test").put("path", "/metadata/annotations/cnpg.io~1fencedInstances")
                    .set("value", fence.deepCopy());
        } else if (annotations.isObject()) {
            // JSON Patch cannot test an absent key. Comparing the observed map proves
            // absence and preserves concurrent annotations without coupling to status RVs.
            patch.addObject().put("op", "test").put("path", "/metadata/annotations")
                    .set("value", annotations.deepCopy());
        } else {
            // No annotations map exists to test; protect creation by comparing metadata.
            patch.addObject().put("op", "test").put("path", "/metadata").set("value", metadata.deepCopy());
        }
        return patch;
    }

    private static ArrayNode identityPatch(JsonNode object) throws IOException {
        String uid = object.path("metadata").path("uid").asText();
        String version = object.path("metadata").path("resourceVersion").asText();
        require(!uid.isBlank() && !version.isBlank(), "Mutation requires UID and resourceVersion");
        ArrayNode patch = JsonNodeFactory.instance.arrayNode();
        patch.addObject().put("op", "test").put("path", "/metadata/uid").put("value", uid);
        patch.addObject().put("op", "test").put("path", "/metadata/resourceVersion").put("value", version);
        return patch;
    }

    private static void add(ArrayNode patch, String path, String value) {
        patch.addObject().put("op", "add").put("path", path).put("value", value);
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }

    private static void validateRequiredSlots(JsonNode slots) throws IOException {
        require(slots.isArray() && !slots.isEmpty(), "Saga switchover requires a permanent failover logical slot on the primary");
        Set<String> names = new HashSet<>();
        for (JsonNode slot : slots) {
            require(!slot.path("slot_name").asText().isBlank() && names.add(slot.path("slot_name").asText())
                    && !slot.path("database").asText().isBlank() && !slot.path("plugin").asText().isBlank()
                    && slot.path("failover").asBoolean() && !slot.path("temporary").asBoolean()
                    && slot.path("invalidation_reason").isNull(), "Primary has an invalid permanent failover logical slot");
            lsn(slot.path("confirmed_flush_lsn").asText());
        }
    }

    static boolean synchronizedSlots(JsonNode required, JsonNode observed) throws IOException {
        validateRequiredSlots(required);
        require(observed.isArray(), "Target logical slot observation must be an array");
        Map<String, JsonNode> targets = new LinkedHashMap<>();
        for (JsonNode slot : observed) {
            require(targets.put(slot.path("slot_name").asText(), slot) == null, "Duplicate target logical slot identity");
        }
        for (JsonNode source : required) {
            JsonNode target = targets.get(source.path("slot_name").asText());
            if (target == null || !target.path("synced").asBoolean() || !target.path("failover").asBoolean()
                    || target.path("temporary").asBoolean() || !target.path("invalidation_reason").isNull()
                    || !target.path("database").equals(source.path("database"))
                    || !target.path("plugin").equals(source.path("plugin"))
                    || lsn(target.path("confirmed_flush_lsn").asText()).compareTo(lsn(source.path("confirmed_flush_lsn").asText())) < 0) return false;
        }
        return true;
    }

    private static BigInteger lsn(String value) throws IOException {
        require(value.matches("[0-9A-Fa-f]{1,8}/[0-9A-Fa-f]{1,8}"), "Logical slot lacks a valid confirmed WAL position");
        String[] words = value.split("/");
        return new BigInteger(words[0], 16).shiftLeft(32).add(new BigInteger(words[1], 16));
    }
}
