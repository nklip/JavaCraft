package dev.nklip.javacraft.shardshop.testing.infra;

import dev.nklip.javacraft.shardshop.testing.cluster.Safety;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** The EKS values change only storage, placement, durability and secrets of the shared chart; kind keeps its lab. */
class EksValuesChartTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path SOURCE = Path.of(System.getProperty("shardshop.directory", "..")).toAbsolutePath().normalize();
    private static final Path CHART = SOURCE.resolve("infra/helm/shardshop");
    private static final String DIGEST = "0".repeat(64);
    private static final String ECR_FLYWAY = "123456789012.dkr.ecr.eu-west-2.amazonaws.com/shardshop-flyway@sha256:" + DIGEST;
    private static final String BUCKET = "s3://shardshop-backups-123456789012-eu-west-2/";
    private static final List<String> CLUSTERS = List.of("shard-a", "shard-b", "shard-c", "ledger-db");
    private static final String PLUGIN = "barman-cloud.cloudnative-pg.io";
    @TempDir Path temporary;

    @Test
    void eksInfrastructureUsesEncryptedGp3VolumesAndOneShardInstanceInEachZone() throws Exception {
        List<JsonNode> eks = render("infrastructure", "eks-values.yaml");
        List<JsonNode> classes = ofKind(eks, "StorageClass");
        assertEquals(1, classes.size());
        JsonNode storageClass = classes.getFirst();
        assertEquals("shardshop-gp3", storageClass.path("metadata").path("name").asText());
        assertEquals("ebs.csi.aws.com", storageClass.path("provisioner").asText());
        assertEquals(JSON.valueToTree(Map.of("type", "gp3", "encrypted", "true", "tagSpecification_1", "Project=shardshop")),
                storageClass.path("parameters"));
        assertEquals("WaitForFirstConsumer", storageClass.path("volumeBindingMode").asText(),
                "A volume must be created in the zone of its scheduled instance");
        assertEquals("Delete", storageClass.path("reclaimPolicy").asText(), "A deleted claim must not leave a billed volume");

        List<JsonNode> clusters = ofKind(eks, "Cluster");
        assertEquals(List.of("shard-a", "shard-b", "shard-c", "ledger-db"), names(clusters));
        for (JsonNode cluster : clusters) {
            assertEquals("shardshop-gp3", cluster.path("spec").path("storage").path("storageClass").asText());
            if (cluster.path("metadata").path("name").asText().equals("ledger-db")) continue;
            assertEquals(3, cluster.path("spec").path("instances").asInt());
            assertEquals(JSON.valueToTree(Map.of("enablePodAntiAffinity", true, "podAntiAffinityType", "required",
                    "topologyKey", "topology.kubernetes.io/zone")), cluster.path("spec").path("affinity"));
        }
        JsonNode broker = ofKind(eks, "StatefulSet").getFirst();
        assertEquals("shardshop-gp3", broker.path("spec").path("volumeClaimTemplates").get(0).path("spec")
                .path("storageClassName").asText());

        List<JsonNode> kind = render("infrastructure", "kind-values.yaml");
        assertTrue(ofKind(kind, "StorageClass").isEmpty(), "kind keeps its own standard class");
        for (JsonNode cluster : ofKind(kind, "Cluster")) {
            assertEquals("standard", cluster.path("spec").path("storage").path("storageClass").asText());
        }
    }

    @Test
    void eksKeepsTheSeparateOrderProcessesAndRolloutStrategiesOfKind() throws Exception {
        List<JsonNode> kind = ofKind(render("application-release", "kind-values.yaml"), "Deployment");
        List<JsonNode> eks = ofKind(render("application-release", "eks-values.yaml"), "Deployment");
        JsonNode http = named(eks, "shardshop-order");
        JsonNode saga = named(eks, "shardshop-order-saga");
        assertEquals("http", processMode(http));
        assertEquals("saga", processMode(saga));
        assertEquals(named(kind, "shardshop-order"), http);
        assertEquals(named(kind, "shardshop-order-saga"), saga);
    }

    @Test
    void migrationsAcceptOnlyADigestPinnedEcrCopyOfTheFlywayImage() throws Exception {
        Path image = values("migration-image.yaml", Map.of("migration", Map.of("image", ECR_FLYWAY), "migrationShard", "shard-a"));
        JsonNode job = ofKind(render("migration", "eks-values.yaml", image), "Job").getFirst();
        assertEquals(ECR_FLYWAY, job.path("spec").path("template").path("spec").path("containers").get(0).path("image").asText());
        assertEquals(ECR_FLYWAY, render("inventory", "eks-values.yaml", image).getFirst().path("data").path("migrationImage").asText(),
                "The drills compare the Jobs with the inventory image");

        Path floating = values("floating-image.yaml", Map.of("migration",
                Map.of("image", "123456789012.dkr.ecr.eu-west-2.amazonaws.com/shardshop-flyway:latest"), "migrationShard", "shard-a"));
        assertNotEquals(0, helm("migration", "eks-values.yaml", floating).exit());
    }

    @Test
    void eksDatabaseClustersArchiveWalAndTakeBaseBackupsWithTheBarmanCloudPlugin() throws Exception {
        List<JsonNode> eks = render("infrastructure", "eks-values.yaml");
        JsonNode store = ofKind(eks, "ObjectStore").getFirst();
        assertEquals("shardshop-backups", store.path("metadata").path("name").asText());
        JsonNode configuration = store.path("spec").path("configuration");
        assertEquals(BUCKET, configuration.path("destinationPath").asText());
        assertTrue(configuration.path("s3Credentials").path("inheritFromIAMRole").asBoolean(),
                "The pod identity of each cluster must supply the S3 credentials, never a static key");
        for (JsonNode cluster : ofKind(eks, "Cluster")) {
            JsonNode plugins = cluster.path("spec").path("plugins");
            assertEquals(1, plugins.size());
            assertEquals(PLUGIN, plugins.get(0).path("name").asText());
            assertTrue(plugins.get(0).path("isWALArchiver").asBoolean(), "Continuous WAL archiving");
            assertEquals("shardshop-backups", plugins.get(0).path("parameters").path("barmanObjectName").asText());
            assertFalse(plugins.get(0).path("parameters").has("serverName"),
                    "The cluster name is the S3 prefix that its backup role can write");
        }
        List<JsonNode> schedules = ofKind(eks, "ScheduledBackup");
        assertEquals(CLUSTERS, schedules.stream().map(schedule -> schedule.path("spec").path("cluster").path("name").asText()).toList());
        for (JsonNode schedule : schedules) {
            assertTrue(schedule.path("spec").path("immediate").asBoolean(), "A point-in-time restore needs a first base backup");
            assertEquals("plugin", schedule.path("spec").path("method").asText());
            assertEquals(PLUGIN, schedule.path("spec").path("pluginConfiguration").path("name").asText());
        }

        List<JsonNode> kind = render("infrastructure", "kind-values.yaml");
        assertTrue(ofKind(kind, "ObjectStore").isEmpty() && ofKind(kind, "ScheduledBackup").isEmpty(), "kind keeps its logical dumps");
        assertTrue(ofKind(kind, "Cluster").stream().noneMatch(cluster -> cluster.path("spec").has("plugins")));
        assertNotEquals(0, helm("infrastructure", "eks-values.yaml", false).exit(), "A backup without its bucket must fail");
    }

    @Test
    void eksSyncsEveryDatabaseRoleAndBrokerSecretFromSecretsManager() throws Exception {
        List<JsonNode> eks = render("infrastructure", "eks-values.yaml");
        JsonNode provider = ofKind(eks, "SecretProviderClass").getFirst().path("spec");
        assertEquals("aws", provider.path("provider").asText());
        assertEquals("true", provider.path("parameters").path("usePodIdentity").asText());
        Set<String> synced = new TreeSet<>();
        JsonNode broker = null;
        for (JsonNode secret : provider.path("secretObjects")) {
            synced.add(secret.path("secretName").asText());
            if (secret.path("secretName").asText().equals("shardshop-rabbitmq")) broker = secret;
            else assertEquals(Set.of("username", "password"), keys(secret));
        }
        Set<String> expected = new TreeSet<>(Set.of("shardshop-rabbitmq"));
        for (JsonNode role : ofKind(eks, "DatabaseRole")) {
            String secret = role.path("spec").path("passwordSecret").path("name").asText();
            if (!secret.isEmpty()) expected.add(secret);
        }
        assertEquals(expected, synced, "Every login role and the broker must get its Secret from Secrets Manager");
        // The keys that cluster-create.sh writes for kind and that the broker pods read.
        assertEquals(Set.of("users.json", "admin-password", "order-password", "ledger-password", "erlang-cookie"), keys(broker));
        JsonNode objects = Safety.infrastructureManifest(provider.path("parameters").path("objects").asText()).getFirst();
        assertEquals(synced.size(), objects.size(), "One Secrets Manager secret for each Kubernetes Secret");
        for (JsonNode object : objects) {
            assertTrue(object.path("objectName").asText().startsWith("shardshop/"), "Terraform grants only the shardshop/ secrets");
        }
        JsonNode holder = named(ofKind(eks, "Deployment"), "shardshop-runtime-secrets");
        assertEquals(0, holder.path("spec").path("strategy").path("rollingUpdate").path("maxUnavailable").asInt(),
                "A synced Secret exists only while a pod mounts its class");
        assertEquals("shardshop-runtime-secrets", holder.path("spec").path("template").path("spec").path("volumes").get(0)
                .path("csi").path("volumeAttributes").path("secretProviderClass").asText());

        assertTrue(ofKind(render("infrastructure", "kind-values.yaml"), "SecretProviderClass").isEmpty(),
                "kind keeps the Secrets of cluster-create.sh");
    }

    @Test
    void eksRunsThreeBrokersWithThreeMemberQuorumQueuesAndKindKeepsOneBroker() throws Exception {
        JsonNode eks = broker(render("infrastructure", "eks-values.yaml"));
        assertEquals(3, eks.path("replicas").asInt());
        assertEquals(3, groupSizes(eks).stream().distinct().reduce(0, Integer::sum), "Every quorum queue needs three members");
        assertTrue(eks.path("configuration").asText().contains("rabbit_peer_discovery_k8s"));
        assertTrue(eks.path("configuration").asText().contains("quorum_queue.continuous_membership_reconciliation.enabled = true\n"
                + "quorum_queue.continuous_membership_reconciliation.target_group_size = 3\n"),
                "The seed declares the queues alone, thus the joining brokers must grow them to three members");
        assertTrue(eks.path("plugins").asText().contains("rabbitmq_peer_discovery_k8s"));
        assertEquals("Parallel", eks.path("podManagementPolicy").asText(), "The seed must be able to wait for its peers");
        assertEquals("rabbit@$(POD_NAME).shardshop-rabbitmq-headless.shardshop.svc.cluster.local", eks.path("nodeName").asText());

        JsonNode kind = broker(render("infrastructure", "kind-values.yaml"));
        assertEquals(1, kind.path("replicas").asInt());
        assertEquals(1, groupSizes(kind).stream().distinct().reduce(0, Integer::sum));
        assertFalse(kind.path("configuration").asText().contains("peer_discovery"));
        assertFalse(kind.path("configuration").asText().contains("membership_reconciliation"));
        assertFalse(kind.has("podManagementPolicy"));
        assertEquals("rabbit@shardshop-rabbitmq-0.shardshop-rabbitmq-headless.shardshop.svc.cluster.local", kind.path("nodeName").asText());
    }

    private List<JsonNode> render(String mode, String environment, Path... extra) throws Exception {
        Render result = helm(mode, environment, extra);
        assertEquals(0, result.exit(), result.output());
        return Safety.infrastructureManifest(result.output());
    }

    private Render helm(String mode, String environment, Path... extra) throws Exception {
        return helm(mode, environment, true, extra);
    }

    /** The runbook adds the backup bucket to the EKS values, because its name contains the account ID. */
    private Render helm(String mode, String environment, boolean bucket, Path... extra) throws Exception {
        Path images = values("images.json", Map.of("images", Map.of(
                "product", image("product"), "product-seeder", image("product-seeder"), "product-reader", image("product-reader"),
                "order", image("order"), "ledger", image("ledger"), "order-producer", image("order-producer"))));
        List<String> command = new ArrayList<>(List.of("helm", "template", "shardshop", CHART.toString(), "--namespace", "shardshop",
                "--values", SOURCE.resolve("infra/shards.yaml").toString(), "--values", CHART.resolve(environment).toString(),
                "--values", images.toString()));
        if (bucket && environment.equals("eks-values.yaml")) {
            command.addAll(List.of("--values", values("bucket.json", Map.of("backup", Map.of("destinationPath", BUCKET))).toString()));
        }
        for (Path file : extra) command.addAll(List.of("--values", file.toString()));
        command.addAll(List.of("--set-string", "render=" + mode));
        Path output = temporary.resolve(mode + "-" + environment + ".out");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        process.getOutputStream().close();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Helm rendering exceeded its deadline");
            return new Render(process.exitValue(), Files.readString(output));
        } finally {
            process.destroyForcibly();
        }
    }

    private Path values(String name, Map<String, Object> content) throws Exception {
        Path file = temporary.resolve(name);
        Files.writeString(file, JSON.writeValueAsString(content));
        return file;
    }

    /** The broker settings that decide its cluster formation and its quorum queue members. */
    private static JsonNode broker(List<JsonNode> resources) {
        JsonNode data = named(ofKind(resources, "ConfigMap"), "shardshop-rabbitmq-config").path("data");
        JsonNode set = ofKind(resources, "StatefulSet").getFirst().path("spec");
        String nodeName = "";
        for (JsonNode variable : set.path("template").path("spec").path("containers").get(0).path("env")) {
            if (variable.path("name").asText().equals("RABBITMQ_NODENAME")) nodeName = variable.path("value").asText();
        }
        var result = JSON.createObjectNode().put("replicas", set.path("replicas").asInt())
                .put("configuration", data.path("rabbitmq.conf").asText()).put("plugins", data.path("enabled_plugins").asText())
                .put("definitions", data.path("definitions.json").asText()).put("nodeName", nodeName);
        if (set.has("podManagementPolicy")) result.put("podManagementPolicy", set.path("podManagementPolicy").asText());
        return result;
    }

    private static List<Integer> groupSizes(JsonNode broker) throws Exception {
        List<Integer> sizes = new ArrayList<>();
        for (JsonNode queue : JSON.readTree(broker.path("definitions").asText()).path("queues")) {
            assertEquals("quorum", queue.path("arguments").path("x-queue-type").asText());
            sizes.add(queue.path("arguments").path("x-quorum-initial-group-size").asInt());
        }
        assertEquals(4, sizes.size());
        return sizes;
    }

    private static Set<String> keys(JsonNode secretObject) {
        Set<String> keys = new TreeSet<>();
        secretObject.path("data").forEach(entry -> keys.add(entry.path("key").asText()));
        return keys;
    }

    private static String image(String application) {
        return "docker.io/library/shardshop-" + application + "@sha256:" + DIGEST;
    }

    private static String processMode(JsonNode deployment) {
        for (JsonNode variable : deployment.path("spec").path("template").path("spec").path("containers").get(0).path("env")) {
            if (variable.path("name").asText().equals("SHARDSHOP_ORDER_PROCESS")) return variable.path("value").asText();
        }
        return "";
    }

    private static List<JsonNode> ofKind(List<JsonNode> resources, String kind) {
        return resources.stream().filter(resource -> kind.equals(resource.path("kind").asText())).toList();
    }

    private static JsonNode named(List<JsonNode> resources, String name) {
        return resources.stream().filter(resource -> name.equals(resource.path("metadata").path("name").asText()))
                .findFirst().orElseThrow();
    }

    private static List<String> names(List<JsonNode> resources) {
        return resources.stream().map(resource -> resource.path("metadata").path("name").asText()).toList();
    }

    private record Render(int exit, String output) { }
}
