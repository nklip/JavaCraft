package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Verify the broker's durable, restricted deployment and architecture delivery contract with real Helm. */
class RabbitMqChartTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temporary;
    private Path source;
    private Path chart;
    private ObjectNode values;

    @BeforeEach
    void snapshotChart() throws IOException {
        source = Path.of(System.getProperty("shardshop.directory", "..")).toAbsolutePath().normalize();
        chart = temporary.resolve("chart");
        try (var paths = Files.walk(source.resolve("infra/helm/shardshop"))) {
            for (Path path : paths.toList()) {
                Path target = chart.resolve(source.resolve("infra/helm/shardshop").relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(target);
                else Files.copy(path, target);
            }
        }
        values = JSON.createObjectNode();
    }

    @Test
    void singleBrokerRetainsStorageAndHasOnlyInternalServices() throws Exception {
        List<JsonNode> resources = render("infrastructure", "shardshop");
        JsonNode broker = named(resources, "StatefulSet", "shardshop-rabbitmq");
        JsonNode spec = broker.path("spec");
        assertEquals(1, spec.path("replicas").asInt());
        assertEquals("shardshop-rabbitmq-headless", spec.path("serviceName").asText());
        assertEquals("Retain", spec.path("persistentVolumeClaimRetentionPolicy").path("whenDeleted").asText());
        assertEquals("Retain", spec.path("persistentVolumeClaimRetentionPolicy").path("whenScaled").asText());
        JsonNode claim = spec.path("volumeClaimTemplates").get(0);
        assertEquals("data", claim.path("metadata").path("name").asText());
        assertEquals("keep", claim.path("metadata").path("annotations").path("helm.sh/resource-policy").asText());
        assertEquals(JSON.valueToTree(List.of("ReadWriteOnce")), claim.path("spec").path("accessModes"));
        assertEquals("2Gi", claim.path("spec").path("resources").path("requests").path("storage").asText());
        assertEquals("standard", claim.path("spec").path("storageClassName").asText());
        JsonNode headless = named(resources, "Service", "shardshop-rabbitmq-headless").path("spec");
        assertEquals("None", headless.path("clusterIP").asText());
        assertTrue(headless.path("publishNotReadyAddresses").asBoolean(), "Stable node DNS must exist while the broker starts");
        JsonNode client = named(resources, "Service", "shardshop-rabbitmq").path("spec");
        assertEquals("ClusterIP", client.path("type").asText());
        assertEquals(List.of(5672, 15672), elements(client.path("ports")).stream().map(port -> port.path("port").asInt()).toList());
        for (JsonNode port : client.path("ports")) assertFalse(port.has("nodePort"));
        for (JsonNode resource : resources) {
            if (resource.path("metadata").path("name").asText().startsWith("shardshop-rabbitmq")) {
                assertEquals("keep", resource.path("metadata").path("annotations").path("helm.sh/resource-policy").asText());
            }
        }
        assertTrue(render("inventory", "shardshop").stream().noneMatch(resource -> resource.path("metadata").path("name").asText().startsWith("shardshop-rabbitmq")));
    }

    @Test
    void arbitraryNonRootUidUsesOnlyWritableDataAndTemporaryDirectories() throws Exception {
        values.putObject("rabbitmq").put("runAsUser", 1000780001).put("fsGroup", 1000780002);
        JsonNode broker = named(render("infrastructure", "portable-lab"), "StatefulSet", "shardshop-rabbitmq");
        JsonNode pod = broker.path("spec").path("template").path("spec");
        JsonNode security = pod.path("securityContext");
        assertEquals(1000780001, security.path("runAsUser").asInt());
        assertEquals(1000780002, security.path("fsGroup").asInt());
        assertTrue(security.path("runAsNonRoot").asBoolean());
        assertEquals("RuntimeDefault", security.path("seccompProfile").path("type").asText());
        assertFalse(pod.path("automountServiceAccountToken").asBoolean());
        assertFalse(pod.path("enableServiceLinks").asBoolean());
        assertFalse(pod.has("initContainers"), "The pod must not need a privileged volume ownership helper");
        JsonNode container = pod.path("containers").get(0);
        assertTrue(container.path("securityContext").path("runAsNonRoot").asBoolean());
        assertFalse(container.path("securityContext").path("allowPrivilegeEscalation").asBoolean());
        assertTrue(container.path("securityContext").path("readOnlyRootFilesystem").asBoolean());
        assertEquals(JSON.valueToTree(List.of("ALL")), container.path("securityContext").path("capabilities").path("drop"));
        assertEquals(List.of("/var/lib/rabbitmq", "/tmp"), elements(container.path("volumeMounts")).stream()
                .filter(mount -> !mount.path("readOnly").asBoolean()).map(mount -> mount.path("mountPath").asText()).toList());
        assertEquals("/tmp/rabbitmq", env(container, "HOME"));
        assertEquals("/var/lib/rabbitmq/mnesia", env(container, "RABBITMQ_MNESIA_BASE"));
        assertEquals("/tmp/rabbitmq/plugins", env(container, "RABBITMQ_PLUGINS_EXPAND_DIR"));
        assertEquals("/tmp/rabbitmq/rabbitmq.pid", env(container, "RABBITMQ_PID_FILE"));
        assertEquals("-", env(container, "RABBITMQ_LOGS"));
        assertEquals("true", env(container, "RABBITMQ_USE_LONGNAME"));
        assertEquals("rabbit@shardshop-rabbitmq-0.shardshop-rabbitmq-headless.portable-lab.svc.cluster.local", env(container, "RABBITMQ_NODENAME"));
        String command = container.path("command").get(2).asText();
        assertTrue(command.contains("umask 0007"));
        assertTrue(command.contains("mkdir -p /tmp/rabbitmq/definitions"));
        assertTrue(command.contains("cp /etc/rabbitmq/definitions/10-topology.json /tmp/rabbitmq/definitions/10-topology.json"));
        assertTrue(command.contains("cp /etc/rabbitmq/definitions/20-users.json /tmp/rabbitmq/definitions/20-users.json"));
        assertFalse(command.contains("cp -r"), "Projected volume metadata directories must never reach the definitions importer");
        assertTrue(command.contains("cp /etc/rabbitmq/credentials/erlang-cookie /tmp/rabbitmq/.erlang.cookie"));
        assertTrue(command.contains("chmod 600 /tmp/rabbitmq/.erlang.cookie"));
        assertTrue(command.endsWith("exec rabbitmq-server\n"));
        assertEquals("1Gi", container.path("resources").path("limits").path("memory").asText());
        assertTrue(container.path("startupProbe").path("failureThreshold").asInt() >= 60);
        assertTrue(container.path("readinessProbe").path("exec").path("command").toString().contains("check_local_alarms"));
        assertTrue(container.path("livenessProbe").path("exec").path("command").toString().contains("ping"));
        assertFalse(container.path("livenessProbe").toString().contains("check_local_alarms"));
        for (String probe : List.of("startupProbe", "readinessProbe", "livenessProbe")) {
            assertTrue(container.path(probe).path("timeoutSeconds").asInt() > 0);
            assertTrue(container.path(probe).path("exec").path("command").toString().contains("5"));
        }
    }

    @Test
    void publicTopologyAndSecretUsersAreSeparateAndReimportedAfterRestart() throws Exception {
        List<JsonNode> resources = render("infrastructure", "shardshop");
        JsonNode data = named(resources, "ConfigMap", "shardshop-rabbitmq-config").path("data");
        assertEquals(3, data.size());
        assertTrue(data.path("rabbitmq.conf").asText().contains("definitions.local.path = /tmp/rabbitmq/definitions"));
        assertTrue(data.path("rabbitmq.conf").asText().contains("definitions.skip_if_unchanged = false"));
        assertTrue(data.path("rabbitmq.conf").asText().contains("vm_memory_high_watermark.absolute = 512MiB"));
        assertTrue(data.path("rabbitmq.conf").asText().contains("disk_free_limit.absolute = 100MB"));
        assertEquals("[rabbitmq_management].\n", data.path("enabled_plugins").asText());
        JsonNode topology = JSON.readTree(data.path("definitions.json").asText());
        for (String secretField : List.of("users", "permissions", "password", "password_hash")) assertFalse(topology.has(secretField));
        JsonNode pod = named(resources, "StatefulSet", "shardshop-rabbitmq").path("spec").path("template").path("spec");
        JsonNode definitions = namedElement(pod.path("volumes"), "definitions").path("projected").path("sources");
        assertEquals("10-topology.json", definitions.get(0).path("configMap").path("items").get(0).path("path").asText());
        assertEquals("shardshop-rabbitmq", definitions.get(1).path("secret").path("name").asText());
        assertEquals("users.json", definitions.get(1).path("secret").path("items").get(0).path("key").asText());
        assertEquals("20-users.json", definitions.get(1).path("secret").path("items").get(0).path("path").asText());
        JsonNode cookie = namedElement(pod.path("volumes"), "cookie").path("secret");
        assertEquals(288, cookie.path("defaultMode").asInt());
        assertEquals("erlang-cookie", cookie.path("items").get(0).path("key").asText());
        assertTrue(resources.stream().noneMatch(resource -> resource.path("kind").asText().equals("Secret")));
    }

    @Test
    void startupCopiesOnlyDefinitionFilesFromKubernetesProjection() throws Exception {
        List<JsonNode> resources = render("infrastructure", "shardshop");
        JsonNode container = named(resources, "StatefulSet", "shardshop-rabbitmq").path("spec").path("template")
                .path("spec").path("containers").get(0);
        Path configuration = temporary.resolve("container-etc");
        Path projected = configuration.resolve("definitions");
        Path generation = projected.resolve("..2026-acceptance");
        Files.createDirectories(generation);
        Files.writeString(generation.resolve("10-topology.json"), "{\"vhosts\":[{\"name\":\"shardshop\"}]}");
        Files.writeString(generation.resolve("20-users.json"), "{\"users\":[]}");
        Files.createSymbolicLink(projected.resolve("..data"), generation.getFileName());
        for (String file : List.of("10-topology.json", "20-users.json")) {
            Files.createSymbolicLink(projected.resolve(file), Path.of("..data", file));
        }
        Files.createDirectories(configuration.resolve("credentials"));
        Files.writeString(configuration.resolve("credentials/erlang-cookie"), "test-cookie");
        Path writable = temporary.resolve("container-tmp");
        // Run the chart's real preparation under temporary paths; the broker process is outside this unit test.
        String preparation = container.path("command").get(2).asText()
                .replace("/etc/rabbitmq", configuration.toString()).replace("/tmp/rabbitmq", writable.toString())
                .replace("exec rabbitmq-server", "exec /usr/bin/true");
        Path output = temporary.resolve("startup-output.txt");
        Process process = new ProcessBuilder("/bin/sh", "-ec", preparation).redirectErrorStream(true)
                .redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Broker file preparation exceeded its deadline");
            assertEquals(0, process.exitValue(), Files.readString(output));
        } finally {
            process.destroyForcibly();
        }
        try (var files = Files.list(writable.resolve("definitions"))) {
            assertEquals(List.of("10-topology.json", "20-users.json"), files.map(file -> file.getFileName().toString()).sorted().toList(),
                    "RabbitMQ imports every directory entry, so Kubernetes metadata directories must be absent");
        }
        for (String file : List.of("10-topology.json", "20-users.json")) {
            assertEquals(Files.readString(generation.resolve(file)), Files.readString(writable.resolve("definitions").resolve(file)));
        }
        assertTrue(named(resources, "ConfigMap", "shardshop-rabbitmq-config").path("data").path("rabbitmq.conf")
                .asText().contains("definitions.local.path = /tmp/rabbitmq/definitions"));
    }

    @Test
    void quorumSourcesAndParkingQueuesHaveTheCompleteDeliveryAndCapacityContract() throws Exception {
        JsonNode topology = JSON.readTree(named(render("infrastructure", "shardshop"), "ConfigMap", "shardshop-rabbitmq-config")
                .path("data").path("definitions.json").asText());
        assertEquals(JSON.valueToTree(List.of(Map.of("name", "shardshop"))), topology.path("vhosts"));
        assertEquals(List.of("ledger.record-order.dlq", "order.ledger-results.dlq", "ledger.record-order", "order.ledger-results"),
                elements(topology.path("queues")).stream().map(queue -> queue.path("name").asText()).toList());
        for (JsonNode queue : topology.path("queues")) {
            assertEquals("shardshop", queue.path("vhost").asText());
            assertTrue(queue.path("durable").asBoolean());
            assertFalse(queue.path("auto_delete").asBoolean());
            JsonNode arguments = queue.path("arguments");
            assertEquals("quorum", arguments.path("x-queue-type").asText());
            assertEquals(1, arguments.path("x-quorum-initial-group-size").asInt());
            if (queue.path("name").asText().endsWith(".dlq")) {
                assertEquals(-1, arguments.path("x-delivery-limit").asInt());
                assertEquals(3, arguments.size(), "Parking queues must have no TTL, expiry or onward dead lettering");
            } else assertEquals(2, arguments.size(), "Each source has one complete, recoverable policy");
        }
        assertEquals(3, topology.path("exchanges").size());
        for (JsonNode exchange : topology.path("exchanges")) {
            assertEquals("direct", exchange.path("type").asText());
            assertTrue(exchange.path("durable").asBoolean());
            assertFalse(exchange.path("auto_delete").asBoolean());
        }
        assertEquals(4, topology.path("bindings").size());
        for (Map.Entry<String, String> route : Map.of("ledger.record-order", "ledger.commands", "order.ledger-results", "order.saga-results",
                "ledger.record-order.dlq", "shardshop.dlx", "order.ledger-results.dlq", "shardshop.dlx").entrySet()) {
            JsonNode binding = elements(topology.path("bindings")).stream().filter(item -> item.path("destination").asText().equals(route.getKey())).findFirst().orElseThrow();
            assertEquals(route.getValue(), binding.path("source").asText());
            assertEquals(route.getKey(), binding.path("routing_key").asText());
            assertEquals("queue", binding.path("destination_type").asText());
        }
        assertEquals(3, topology.path("policies").size());
        for (Map.Entry<String, String> sourceQueue : Map.of("ledger-command-delivery", "ledger.record-order", "order-result-delivery", "order.ledger-results").entrySet()) {
            JsonNode policy = namedElement(topology.path("policies"), sourceQueue.getKey());
            assertEquals("^" + sourceQueue.getValue().replace(".", "\\.") + "$", policy.path("pattern").asText());
            assertEquals("queues", policy.path("apply-to").asText());
            assertEquals(JSON.valueToTree(Map.ofEntries(Map.entry("delivery-limit", 5), Map.entry("delayed-retry-type", "failed"),
                    Map.entry("delayed-retry-min", 1000), Map.entry("delayed-retry-max", 5000), Map.entry("dead-letter-strategy", "at-least-once"),
                    Map.entry("overflow", "reject-publish"), Map.entry("max-length", 1000), Map.entry("max-length-bytes", 16777216),
                    Map.entry("dead-letter-exchange", "shardshop.dlx"), Map.entry("dead-letter-routing-key", sourceQueue.getValue() + ".dlq"))), policy.path("definition"));
        }
        JsonNode parking = namedElement(topology.path("policies"), "parking-capacity");
        assertEquals("^(ledger\\.record-order|order\\.ledger-results)\\.dlq$", parking.path("pattern").asText());
        assertEquals(JSON.valueToTree(Map.of("overflow", "reject-publish", "max-length", 1000, "max-length-bytes", 16777216)), parking.path("definition"));
    }

    @Test
    void mountedConfigurationChangesRollTheBrokerWithTheSameImage() throws Exception {
        JsonNode before = named(render("infrastructure", "shardshop"), "StatefulSet", "shardshop-rabbitmq").path("spec").path("template");
        Path configuration = chart.resolve("files/rabbitmq/rabbitmq.conf");
        Files.writeString(configuration, Files.readString(configuration) + "\n# broker configuration changed\n");
        JsonNode after = named(render("infrastructure", "shardshop"), "StatefulSet", "shardshop-rabbitmq").path("spec").path("template");
        assertNotEquals(before.path("metadata").path("annotations"), after.path("metadata").path("annotations"));
        assertEquals(before.path("spec"), after.path("spec"));
    }

    @Test
    void unsafeScalingRootUsersFloatingImagesAndEphemeralStorageAreRejected() throws Exception {
        for (String invalid : List.of("{\"replicas\":2}", "{\"runAsUser\":0}", "{\"fsGroup\":0}", "{\"enabled\":false}",
                "{\"persistence\":false}", "{\"storage\":{\"enabled\":false}}", "{\"storage\":{\"size\":\"0Gi\"}}",
                "{\"storage\":{\"storageClass\":\"\"}}", "{\"image\":\"docker.io/library/rabbitmq:latest\"}")) {
            values.set("rabbitmq", JSON.readTree(invalid));
            assertNotEquals(0, helm("infrastructure", "shardshop").exit(), invalid);
        }
    }

    private List<JsonNode> render(String mode, String namespace) throws Exception {
        Render result = helm(mode, namespace);
        assertEquals(0, result.exit(), result.output());
        List<JsonNode> resources = new ArrayList<>();
        for (Object document : new Yaml(new SafeConstructor(new LoaderOptions())).loadAll(result.output())) {
            if (document != null) resources.add(JSON.valueToTree(document));
        }
        return resources;
    }

    private Render helm(String mode, String namespace) throws Exception {
        Path overrides = temporary.resolve("overrides.json");
        Files.writeString(overrides, values.toString());
        Path output = temporary.resolve("helm-output.txt");
        Process process = new ProcessBuilder("helm", "template", "shardshop-infrastructure", chart.toString(), "--namespace", namespace,
                "--values", source.resolve("infra/shards.yaml").toString(), "--values", chart.resolve("kind-values.yaml").toString(),
                "--values", overrides.toString(), "--set-string", "render=" + mode)
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        process.getOutputStream().close();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Helm broker rendering exceeded its deadline");
            return new Render(process.exitValue(), Files.readString(output));
        } finally {
            process.destroyForcibly();
        }
    }

    private static JsonNode named(List<JsonNode> resources, String kind, String name) {
        return resources.stream().filter(resource -> resource.path("kind").asText().equals(kind))
                .filter(resource -> resource.path("metadata").path("name").asText().equals(name)).findFirst().orElseThrow();
    }

    private static JsonNode namedElement(JsonNode elements, String name) {
        return elements(elements).stream().filter(element -> element.path("name").asText().equals(name)).findFirst().orElseThrow();
    }

    private static List<JsonNode> elements(JsonNode array) {
        List<JsonNode> elements = new ArrayList<>();
        array.forEach(elements::add);
        return elements;
    }

    private static String env(JsonNode container, String name) {
        return namedElement(container.path("env"), name).path("value").asText();
    }

    private record Render(int exit, String output) { }
}
