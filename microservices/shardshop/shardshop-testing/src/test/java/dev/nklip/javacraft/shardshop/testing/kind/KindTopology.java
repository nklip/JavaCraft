package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.ShardShopFiles;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Deployment observations only: CNPG remains responsible for reconciliation. */
public final class KindTopology {
    static final Duration CONVERGENCE = Duration.ofSeconds(180);
    private static final List<String> GOLDEN_SHARDS = List.of("shard-a", "shard-b", "shard-c");
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z][a-z0-9-]*");
    private final KindLab lab;

    public KindTopology() { this(new KindLab()); }
    KindTopology(KindLab lab) { this.lab = lab; }

    public void verify() throws IOException, InterruptedException {
        publishedTopology();
        observe();
        routing();
    }

    void publishedTopology() throws IOException, InterruptedException {
        for (String name : List.of("shardshop-routing", "shardshop-migration-topology")) {
            JsonNode published = KindLab.parse(lab.kubectl("get", "configmap", name, "-o", "json"));
            require(!published.isEmpty(), "Published deployment topology is missing: " + name);
            KindSafety.validateTopology(lab.inventory(), published, name);
        }
    }

    Map<String, Shard> observe() throws IOException, InterruptedException {
        List<String> shards = lab.shards();
        validateShards(shards);
        require(lab.inventory().path("instances").asInt() == 3, "Topology requires three instances per shard");
        Set<String> workers = new HashSet<>();
        for (JsonNode node : KindLab.items(KindLab.parse(lab.kubectl("get", "nodes", "-l",
                "!node-role.kubernetes.io/control-plane", "-o", "json")))) {
            workers.add(node.path("metadata").path("name").asText());
        }
        Map<String, Shard> result = new LinkedHashMap<>();
        Set<String> lineages = new HashSet<>();
        Set<String> claims = new HashSet<>();
        Set<String> uids = new HashSet<>();
        Set<String> volumes = new HashSet<>();
        for (String name : shards) {
            ready("cluster/" + name);
            lab.kubectl(Duration.ofSeconds(200), "--request-timeout=190s", "wait", "--for=condition=Ready", "pods",
                    "-l", selector(name), "--timeout=180s");
            JsonNode cluster = cluster(name);
            String primary = primary(name);
            List<JsonNode> pods = pods(name);
            Map<String, Volume> pvcs = volumes(name);
            validatePlacement(name, primary, pods, pvcs, workers);
            String lineage = sql(primary, "SELECT system_identifier FROM pg_control_system()");
            require(lineage.matches("[0-9]+") && lineages.add(lineage), name + " must have an independent database lineage");
            List<String> standbys = new ArrayList<>();
            String budget = cluster.path("spec").path("postgresql").path("parameters").path("max_slot_wal_keep_size").asText();
            require(!budget.isBlank() && !budget.equals("-1"), name + " must have a finite slot WAL retention budget");
            for (JsonNode pod : pods) {
                String instance = pod.path("metadata").path("name").asText();
                equal(sql(instance, "SELECT system_identifier FROM pg_control_system()"), lineage, instance + " lineage");
                equal(sql(instance, "SELECT pg_is_in_recovery(), current_setting('transaction_read_only')"),
                        instance.equals(primary) ? "f|off" : "t|on", instance + " database role");
                if (!instance.equals(primary)) standbys.add(instance);
                equal(sql(instance, "SHOW wal_level; SHOW hot_standby_feedback; SHOW sync_replication_slots; SHOW max_slot_wal_keep_size"),
                        "logical\non\non\n" + budget, instance + " logical replication settings");
            }
            standbys.sort(String::compareTo);
            streaming(primary, standbys);
            endpoints(name + "-rw", List.of(primary));
            endpoints(name + "-ro", standbys);
            String settings = sql(primary, "SHOW synchronous_commit; SHOW synchronous_standby_names; SHOW max_replication_slots; SHOW max_wal_senders");
            validateQuorumSettings(settings);
            awaitSql(primary, "SELECT count(*) FROM pg_replication_slots WHERE slot_type='physical' AND active "
                    + "AND slot_name = ANY(string_to_array(current_setting('synchronized_standby_slots'), ','))", "2");
            for (var entry : pvcs.entrySet()) {
                require(claims.add(entry.getKey()) && uids.add(entry.getValue().uid()) && volumes.add(entry.getValue().volume()),
                        "Shard instances must have distinct PVC identities and backing volumes");
            }
            result.put(name, new Shard(name, primary, List.copyOf(standbys), Map.copyOf(pvcs)));
        }
        return Map.copyOf(result);
    }

    void routing() throws IOException, InterruptedException {
        List<String> shards = lab.shards();
        String vectors = routingValues(Files.readAllLines(ShardShopFiles.resolve(
                "shardshop-core/sharding/src/test/resources/routing-vectors.csv")), shards);
        for (String shard : shards) {
            String primary = primary(shard);
            for (String entity : List.of("seller", "buyer")) {
                String schema = entity.equals("seller") ? "catalog" : "ordering";
                String predicate = sql(primary, "BEGIN READ ONLY; SELECT pg_get_expr(conbin, conrelid) FROM pg_constraint "
                        + "WHERE conrelid='" + schema + "." + entity + "s'::regclass AND conname='" + entity
                        + "s_shard_check' AND contype='c' AND convalidated; ROLLBACK;");
                require(!predicate.isBlank() && predicate.lines().count() == 1,
                        shard + " lacks a validated " + entity + " routing constraint");
                equal(sql(primary, "BEGIN READ ONLY; SELECT count(*) FROM (VALUES " + vectors + ") AS vectors("
                        + entity + "_id, expected_shard) WHERE (" + predicate + ") IS DISTINCT FROM (expected_shard='"
                        + shard + "'); ROLLBACK;"), "0", shard + " deployed " + entity + " routing against golden vectors");
            }
        }
    }

    static String routingValues(List<String> lines, List<String> shards) throws IOException {
        validateShards(shards);
        List<String> values = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank() || line.startsWith("#")) continue;
            int last = line.lastIndexOf(',');
            int previous = last < 0 ? -1 : line.lastIndexOf(',', last - 1);
            require(previous > 0, "Malformed golden routing fixture");
            String id = line.substring(previous + 1, last).strip();
            String expected = line.substring(last + 1).strip();
            require(id.matches("[1-9][0-9]{0,18}") && new BigInteger(id).compareTo(BigInteger.valueOf(Long.MAX_VALUE)) <= 0,
                    "Golden routing ID must be a canonical positive signed bigint");
            BigInteger digest;
            try {
                digest = new BigInteger(1, MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
            }
            require(GOLDEN_SHARDS.get(digest.mod(BigInteger.valueOf(3)).intValue()).equals(expected),
                    "Golden routing fixture disagrees with independent unsigned SHA-256");
            values.add("(" + id + "::bigint, '" + shards.get(digest.mod(BigInteger.valueOf(shards.size())).intValue()) + "')");
        }
        require(!values.isEmpty(), "No golden routing vectors found");
        return String.join(",", values);
    }

    static void validateShards(List<String> shards) throws IOException {
        require(!shards.isEmpty() && new HashSet<>(shards).size() == shards.size()
                && shards.stream().allMatch(shard -> IDENTIFIER.matcher(shard).matches()), "Invalid shard inventory");
    }

    static void validatePlacement(String shard, String primary, List<JsonNode> pods, Map<String, Volume> pvcs,
                                  Set<String> workers) throws IOException {
        require(pods.size() == 3 && pvcs.size() == 3, shard + " requires three pods and PVCs");
        Set<String> names = new HashSet<>();
        Set<String> nodes = new HashSet<>();
        Set<String> claims = new HashSet<>();
        for (JsonNode pod : pods) {
            String name = pod.path("metadata").path("name").asText();
            String node = pod.path("spec").path("nodeName").asText();
            String claim = "";
            for (JsonNode volume : pod.path("spec").path("volumes")) {
                if (volume.path("name").asText().equals("pgdata")) claim = volume.path("persistentVolumeClaim").path("claimName").asText();
            }
            require(name.matches(Pattern.quote(shard) + "-[0-9]+") && names.add(name)
                    && !pod.path("metadata").hasNonNull("deletionTimestamp"), "Unexpected shard instance " + name);
            require(workers.contains(node) && nodes.add(node), shard + " instances must occupy distinct worker nodes");
            require(pvcs.containsKey(claim) && claims.add(claim), name + " must attach its own Bound PVC");
        }
        require(names.contains(primary), shard + " primary is not one of its instances");
        Set<String> uids = new HashSet<>();
        Set<String> volumes = new HashSet<>();
        for (Volume volume : pvcs.values()) {
            require(!volume.uid().isBlank() && !volume.volume().isBlank() && volume.phase().equals("Bound")
                    && uids.add(volume.uid()) && volumes.add(volume.volume()), shard + " PVC identity is incomplete or shared");
        }
    }

    static void validateQuorumSettings(String settings) throws IOException {
        List<String> lines = settings.lines().toList();
        try {
            require(lines.size() == 4 && lines.get(0).equals("on") && lines.get(1).startsWith("ANY 1 ")
                    && Integer.parseInt(lines.get(2)) >= 3 && Integer.parseInt(lines.get(3)) >= 4,
                    "Synchronous quorum or CDC capacity settings are invalid");
        } catch (NumberFormatException failure) {
            throw new IOException("Replication capacity settings are invalid", failure);
        }
    }

    String primary(String shard) throws IOException, InterruptedException {
        String name = cluster(shard).path("status").path("currentPrimary").asText();
        require(name.matches(Pattern.quote(shard) + "-[0-9]+"), shard + " has no valid current primary");
        return name;
    }

    JsonNode cluster(String shard) throws IOException, InterruptedException {
        return KindLab.parse(lab.kubectl("get", "cluster", shard, "-o", "json"));
    }

    List<JsonNode> pods(String shard) throws IOException, InterruptedException {
        return KindLab.items(KindLab.parse(lab.kubectl("get", "pods", "-l", selector(shard), "-o", "json")));
    }

    Map<String, Volume> volumes(String shard) throws IOException, InterruptedException {
        Map<String, Volume> result = new LinkedHashMap<>();
        for (JsonNode pvc : KindLab.items(KindLab.parse(lab.kubectl("get", "pvc", "-l", "cnpg.io/cluster=" + shard, "-o", "json")))) {
            String name = pvc.path("metadata").path("name").asText();
            require(!name.isBlank() && !result.containsKey(name), "Duplicate or unnamed PVC");
            result.put(name, new Volume(pvc.path("metadata").path("uid").asText(),
                    pvc.path("spec").path("volumeName").asText(), pvc.path("status").path("phase").asText()));
        }
        return Map.copyOf(result);
    }

    void streaming(String primary, List<String> standbys) throws IOException, InterruptedException {
        awaitSql(primary, "SELECT count(*) FROM pg_stat_replication; SELECT application_name FROM pg_stat_replication "
                + "WHERE state='streaming' AND sync_state='quorum' ORDER BY application_name", "2\n" + String.join("\n", standbys));
    }

    void endpoints(String service, List<String> expected) throws IOException, InterruptedException {
        lab.await(service + " exact ready endpoints", CONVERGENCE, () -> readyEndpoints(KindLab.parse(
                lab.kubectl("get", "endpointslices", "-l", "kubernetes.io/service-name=" + service, "-o", "json")))
                .equals(expected.stream().sorted().toList()));
    }

    static List<String> readyEndpoints(JsonNode slices) {
        List<String> names = new ArrayList<>();
        for (JsonNode slice : KindLab.items(slices)) {
            for (JsonNode endpoint : slice.path("endpoints")) {
                if (endpoint.path("conditions").path("ready").asBoolean()) names.add(endpoint.path("targetRef").path("name").asText());
            }
        }
        return names.stream().sorted().toList();
    }

    void ready(String resource) throws IOException, InterruptedException {
        lab.kubectl(Duration.ofSeconds(200), "--request-timeout=190s", "wait", "--for=condition=Ready", resource, "--timeout=180s");
    }

    void awaitSql(String pod, String statement, String expected) throws IOException, InterruptedException {
        lab.await(pod + " database observation", CONVERGENCE, () -> sql(pod, statement).equals(expected));
    }

    String sql(String pod, String statement) throws IOException, InterruptedException {
        return successfulSql(sqlAttempt(pod, statement));
    }

    KindLab.CommandResult sqlAttempt(String pod, String statement) throws IOException, InterruptedException {
        return lab.kubectlAttempt("exec", pod, "-c", "postgres", "--", "env",
                "PGOPTIONS=-c statement_timeout=15000 -c lock_timeout=5000", "psql", "-X", "-qAt", "-v",
                "ON_ERROR_STOP=1", "-v", "VERBOSITY=sqlstate", "-U", "postgres", "-d", "shardshop", "-c", statement);
    }

    static String successfulSql(KindLab.CommandResult result) throws IOException {
        require(result.exitCode() == 0 && result.stderr().isBlank(), "Database probe failed or emitted a warning");
        return result.stdout().strip();
    }

    static String selector(String shard) { return "cnpg.io/cluster=" + shard + ",cnpg.io/podRole=instance"; }
    static void equal(Object actual, Object expected, String description) throws IOException {
        require(expected.equals(actual), description + " did not match the required topology");
    }
    static void require(boolean value, String description) throws IOException { if (!value) throw new IOException(description); }
    record Volume(String uid, String volume, String phase) { }
    record Shard(String name, String primary, List<String> standbys, Map<String, Volume> volumes) { }
}
