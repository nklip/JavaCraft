package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** The EKS environment values change only storage and placement of the shared chart (plan step 7.2). */
class EksValuesChartTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path SOURCE = Path.of(System.getProperty("shardshop.directory", "..")).toAbsolutePath().normalize();
    private static final Path CHART = SOURCE.resolve("infra/helm/shardshop");
    private static final String DIGEST = "0".repeat(64);
    private static final String ECR_FLYWAY = "123456789012.dkr.ecr.eu-west-2.amazonaws.com/shardshop-flyway@sha256:" + DIGEST;
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

    private List<JsonNode> render(String mode, String environment, Path... extra) throws Exception {
        Render result = helm(mode, environment, extra);
        assertEquals(0, result.exit(), result.output());
        return KindSafety.infrastructureManifest(result.output());
    }

    private Render helm(String mode, String environment, Path... extra) throws Exception {
        Path images = values("images.json", Map.of("images", Map.of(
                "product", image("product"), "product-seeder", image("product-seeder"), "product-reader", image("product-reader"),
                "order", image("order"), "ledger", image("ledger"), "order-producer", image("order-producer"))));
        List<String> command = new ArrayList<>(List.of("helm", "template", "shardshop", CHART.toString(), "--namespace", "shardshop",
                "--values", SOURCE.resolve("infra/shards.yaml").toString(), "--values", CHART.resolve(environment).toString(),
                "--values", images.toString()));
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
