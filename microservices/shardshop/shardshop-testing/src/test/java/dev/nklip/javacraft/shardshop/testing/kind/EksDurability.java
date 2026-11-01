package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static dev.nklip.javacraft.shardshop.testing.kind.KindTopology.equal;
import static dev.nklip.javacraft.shardshop.testing.kind.KindTopology.require;

/** EKS-only observations and drills: S3 backups, point-in-time restores, broker quorum and runtime secrets. */
public final class EksDurability {
    private static final Duration ARCHIVE = Duration.ofMinutes(10);
    private static final Duration RESTORE = Duration.ofMinutes(15);
    private static final Duration BROKER_RECOVERY = Duration.ofMinutes(5);
    private static final String PLUGIN = "barman-cloud.cloudnative-pg.io";
    private static final String BROKER = "shardshop-rabbitmq";
    private static final int BROKERS = 3;
    private final KindLab lab;

    public EksDurability() { this(new KindLab()); }
    EksDurability(KindLab lab) { this.lab = lab; }

    public void backups() throws IOException, InterruptedException {
        for (String cluster : lab.clusters()) {
            lab.await(cluster + " continuous WAL archiving", ARCHIVE, () -> condition(cluster(cluster), "ContinuousArchiving"));
            lab.await(cluster + " completed base backup", ARCHIVE, () -> KindLab.items(KindLab.parse(lab.kubectl("get", "backups", "-o", "json")))
                    .stream().anyMatch(backup -> backup.path("spec").path("cluster").path("name").asText().equals(cluster)
                            && backup.path("spec").path("method").asText().equals("plugin")
                            && backup.path("status").path("phase").asText().equals("completed")));
            String primary = primary(cluster);
            lab.await(cluster + " archived WAL without a later failure", ARCHIVE, () -> sql(primary, database(cluster),
                    "SELECT archived_count > 0 AND (last_failed_time IS NULL OR last_archived_time > last_failed_time) FROM pg_stat_archiver")
                    .equals("t"));
        }
    }

    public void pointInTimeRestores() throws IOException, InterruptedException {
        String requested = System.getProperty("shardshop.kind.drillCluster");
        List<String> clusters = lab.clusters();
        require(requested == null || clusters.contains(requested), "shardshop.kind.drillCluster must name an inventory shard or ledger-db");
        for (String cluster : requested == null ? clusters : List.of(requested)) restore(cluster);
    }

    /** Restores the backups of one cluster into the new cluster <cluster>-pitr, whose pod identity can only read them. */
    private void restore(String cluster) throws IOException, InterruptedException {
        String target = cluster + "-pitr";
        String database = database(cluster);
        String probe = "pitr_probe_" + UUID.randomUUID().toString().replace("-", "");
        try (KindDrillGuard guard = new KindDrillGuard(lab).acquire()) {
            guard.run(() -> {
                require(KindLab.parse(lab.kubectl("get", "cluster", target, "--ignore-not-found", "-o", "json")).isEmpty(),
                        target + " already exists; delete it before the restore drill");
                JsonNode source = cluster(cluster);
                String primary = primary(cluster);
                guard.onClose("remove probe schema " + probe + " from " + cluster, () -> {
                    sql(primary(cluster), database, "SET client_min_messages=warning; DROP TABLE IF EXISTS " + probe
                            + ".rows; DROP SCHEMA IF EXISTS " + probe);
                    return true;
                });
                sql(primary, database, "CREATE SCHEMA " + probe + "; CREATE TABLE " + probe + ".rows (id bigint PRIMARY KEY, phase text NOT NULL)");
                sql(primary, database, "INSERT INTO " + probe + ".rows VALUES (1, 'before')");
                // Read after the first commit and before the second one, thus only the first commit precedes it.
                String targetTime = sql(primary, database,
                        "SELECT to_char(clock_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS.US') || '+00'");
                sql(primary, database, "INSERT INTO " + probe + ".rows VALUES (2, 'after')");
                // The recovery stops at the first commit after the target, thus that commit must be archived.
                String segment = sql(primary, database, "SELECT pg_walfile_name(pg_current_wal_lsn())");
                sql(primary, database, "SELECT pg_switch_wal()");
                lab.await(cluster + " archive of " + segment, ARCHIVE, () -> sql(primary, database,
                        "SELECT coalesce(last_archived_wal >= '" + segment + "', false) FROM pg_stat_archiver").equals("t"));

                guard.onClose("delete restored cluster " + target + " and its volume", () -> {
                    lab.kubectl(Duration.ofSeconds(200), "--request-timeout=190s", "delete", "cluster", target,
                            "--ignore-not-found", "--wait=true", "--timeout=180s");
                    lab.await(target + " volume deletion", Duration.ofMinutes(3), () -> KindLab.items(KindLab.parse(
                            lab.kubectl("get", "pvc", "-l", "cnpg.io/cluster=" + target, "-o", "json"))).isEmpty());
                    return true;
                });
                create(restoreCluster(source, target, cluster, targetTime));
                lab.await(target + " point-in-time recovery", RESTORE, () -> condition(cluster(target), "Ready"));
                String restored = primary(target);
                equal(sql(restored, database, "SELECT pg_is_in_recovery()"), "f", target + " promotion");
                equal(sql(restored, database, "SELECT string_agg(phase, ',' ORDER BY id) FROM " + probe + ".rows"), "before",
                        target + " rows at " + targetTime);
            });
        }
    }

    /** One instance from the backups of the source, with the source settings that a recovery needs. */
    static ObjectNode restoreCluster(JsonNode source, String target, String cluster, String targetTime) {
        JsonNode spec = source.path("spec");
        ObjectNode manifest = JsonNodeFactory.instance.objectNode().put("apiVersion", "postgresql.cnpg.io/v1").put("kind", "Cluster");
        manifest.putObject("metadata").put("name", target).put("namespace", "shardshop");
        ObjectNode restored = manifest.putObject("spec").put("instances", 1)
                .put("imageName", spec.path("imageName").asText()).put("enableSuperuserAccess", false);
        restored.set("storage", spec.path("storage").deepCopy());
        restored.set("resources", spec.path("resources").deepCopy());
        // A standby or a recovery needs at least the source values of the replication and connection settings.
        restored.putObject("postgresql").set("parameters", spec.path("postgresql").path("parameters").deepCopy());
        JsonNode initdb = spec.path("bootstrap").path("initdb");
        ObjectNode recovery = restored.putObject("bootstrap").putObject("recovery").put("source", "origin")
                .put("database", initdb.path("database").asText()).put("owner", initdb.path("owner").asText());
        recovery.putObject("recoveryTarget").put("targetTime", targetTime);
        restored.putArray("externalClusters").addObject().put("name", "origin").putObject("plugin").put("name", PLUGIN)
                .putObject("parameters").put("barmanObjectName", "shardshop-backups").put("serverName", cluster);
        return manifest;
    }

    public void brokerQuorum() throws IOException, InterruptedException {
        try (KindDrillGuard guard = new KindDrillGuard(lab).acquire()) {
            guard.run(() -> {
                Set<String> zones = new HashSet<>();
                List<String> nodes = new ArrayList<>();
                for (int index = 0; index < BROKERS; index++) {
                    String pod = BROKER + "-" + index;
                    lab.kubectl(Duration.ofSeconds(140), "--request-timeout=130s", "wait", "--for=condition=Ready", "pod/" + pod, "--timeout=120s");
                    String node = lab.kubectl("get", "pod", pod, "-o", "jsonpath={.spec.nodeName}").strip();
                    zones.add(lab.kubectl("get", "node", node, "-o", "jsonpath={.metadata.labels.topology\\.kubernetes\\.io/zone}").strip());
                    nodes.add("rabbit@" + pod + "." + BROKER + "-headless.shardshop.svc.cluster.local");
                }
                equal(zones.size(), BROKERS, "Broker zones");
                awaitQuorum(BROKER + "-0", nodes);
                // Stop the broker that leads the command queue, and observe a new leader for every queue.
                String leader = queues(BROKER + "-0").stream().filter(queue -> queue.path("name").asText().equals("ledger.record-order"))
                        .findFirst().orElseThrow(() -> new IOException("ledger.record-order is missing")).path("leader").asText();
                String stopped = leader.substring("rabbit@".length(), leader.indexOf('.'));
                String observer = nodes.stream().map(node -> node.substring("rabbit@".length(), node.indexOf('.')))
                        .filter(pod -> !pod.equals(stopped)).findFirst().orElseThrow();
                guard.onClose("wait until broker " + stopped + " rejoins all quorum queues", () -> {
                    lab.kubectl(Duration.ofSeconds(320), "--request-timeout=310s", "wait", "--for=condition=Ready", "pod/" + stopped, "--timeout=300s");
                    awaitQuorum(observer, nodes);
                    return true;
                });
                String uid = lab.kubectl("get", "pod", stopped, "-o", "jsonpath={.metadata.uid}").strip();
                lab.kubectl(Duration.ofSeconds(30), "delete", "pod", stopped, "--wait=false");
                lab.await("a leader without " + leader + " for every quorum queue", BROKER_RECOVERY, () -> queues(observer).stream()
                        .allMatch(queue -> !queue.path("leader").asText().equals(leader) && contains(queue.path("online"), queue.path("leader").asText())));
                lab.await(stopped + " replacement", BROKER_RECOVERY, () -> {
                    String current = lab.kubectl("get", "pod", stopped, "--ignore-not-found", "-o", "jsonpath={.metadata.uid}").strip();
                    return !current.isEmpty() && !current.equals(uid);
                });
            });
        }
    }

    /** Every broker runs, and every quorum queue has exactly one online member on each broker. */
    private void awaitQuorum(String pod, List<String> nodes) throws IOException, InterruptedException {
        Set<String> expected = Set.copyOf(nodes);
        lab.await("three running brokers with three online members of every quorum queue", BROKER_RECOVERY, () -> {
            JsonNode status = KindLab.parse(rabbitmqctl(pod, "cluster_status"));
            if (!names(status.path("running_nodes")).equals(expected)) return false;
            List<JsonNode> queues = queues(pod);
            return queues.size() >= 4 && queues.stream().allMatch(queue -> queue.path("type").asText().equals("quorum")
                    && queue.path("members").size() == BROKERS && names(queue.path("members")).equals(expected)
                    && names(queue.path("online")).equals(expected) && contains(queue.path("online"), queue.path("leader").asText()));
        });
    }

    private List<JsonNode> queues(String pod) throws IOException, InterruptedException {
        JsonNode queues = KindLab.parse(rabbitmqctl(pod, "list_queues", "-p", "shardshop", "name", "type", "members", "online", "leader"));
        require(queues.isArray(), "RabbitMQ did not return a queue list");
        return KindLab.items(queues);
    }

    private String rabbitmqctl(String pod, String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("exec", pod, "-c", "rabbitmq", "--", "rabbitmqctl", "--quiet", "--timeout", "10",
                "--formatter=json"));
        command.addAll(List.of(arguments));
        return lab.kubectl(Duration.ofSeconds(20), command.toArray(String[]::new));
    }

    public void runtimeSecrets() throws IOException, InterruptedException {
        JsonNode provider = KindLab.parse(lab.kubectl("get", "secretproviderclass", "shardshop-runtime-secrets", "-o", "json"));
        require(provider.path("spec").path("provider").asText().equals("aws"), "The runtime secrets need the AWS provider class");
        lab.kubectl(Duration.ofSeconds(140), "--request-timeout=130s", "rollout", "status", "deployment/shardshop-runtime-secrets", "--timeout=120s");
        for (JsonNode object : provider.path("spec").path("secretObjects")) {
            String name = object.path("secretName").asText();
            // Never print the Secret: check only its origin label and that each key has a value.
            JsonNode secret = KindLab.parse(lab.kubectl("get", "secret", name, "-o", "json"));
            require(secret.path("metadata").path("labels").path("secrets-store.csi.k8s.io/managed").asText().equals("true"),
                    name + " was not synced by the Secrets Store CSI driver");
            for (JsonNode entry : object.path("data")) {
                require(!secret.path("data").path(entry.path("key").asText()).asText().isEmpty(), name + " has no " + entry.path("key").asText());
            }
        }
        // CNPG set each login password from its synced Secret.
        lab.await("every database role applied", ARCHIVE, () -> KindLab.items(KindLab.parse(lab.kubectl("get",
                "databaseroles.postgresql.cnpg.io", "-o", "json"))).stream().allMatch(role -> role.path("status").path("applied").asBoolean(false)));
    }

    private void create(JsonNode manifest) throws IOException, InterruptedException {
        Path file = Files.createTempFile("shardshop-eks-", ".json");
        try {
            Files.writeString(file, manifest.toString());
            lab.kubectl("create", "-f", file.toString());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private JsonNode cluster(String name) throws IOException, InterruptedException {
        return KindLab.parse(lab.kubectl("get", "cluster", name, "-o", "json"));
    }

    private String primary(String cluster) throws IOException, InterruptedException {
        String name = cluster(cluster).path("status").path("currentPrimary").asText();
        require(name.startsWith(cluster + "-"), cluster + " has no current primary");
        return name;
    }

    private String database(String cluster) throws IOException, InterruptedException {
        return cluster(cluster).path("spec").path("bootstrap").path("initdb").path("database").asText();
    }

    private String sql(String pod, String database, String statement) throws IOException, InterruptedException {
        return KindTopology.successfulSql(lab.kubectlAttempt("exec", pod, "-c", "postgres", "--", "env",
                "PGOPTIONS=-c statement_timeout=15000 -c lock_timeout=5000", "psql", "-X", "-qAt", "-v", "ON_ERROR_STOP=1",
                "-U", "postgres", "-d", database, "-c", statement));
    }

    static boolean condition(JsonNode resource, String type) {
        for (JsonNode condition : resource.path("status").path("conditions")) {
            if (condition.path("type").asText().equals(type)) return condition.path("status").asText().equals("True");
        }
        return false;
    }

    private static Set<String> names(JsonNode array) {
        Set<String> names = new HashSet<>();
        array.forEach(name -> names.add(name.asText()));
        return names;
    }

    private static boolean contains(JsonNode array, String value) {
        return !value.isEmpty() && names(array).contains(value);
    }
}
