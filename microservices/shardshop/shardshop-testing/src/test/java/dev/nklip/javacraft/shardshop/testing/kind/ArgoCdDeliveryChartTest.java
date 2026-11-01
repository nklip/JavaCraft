package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** The Argo CD delivery contract of the database layer on the AWS deployment; Helm on kind stays unchanged. */
class ArgoCdDeliveryChartTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path SOURCE = Path.of(System.getProperty("shardshop.directory", "..")).toAbsolutePath().normalize();
    private static final String REGISTRY = "123456789012.dkr.ecr.eu-west-2.amazonaws.com";
    private static final String DIGEST = "sha256:" + "0".repeat(64);
    private static final String VERSION = "0.3.0-run20261101190000";
    @TempDir Path temporary;
    private Path shared;
    private Path delivery;

    @BeforeEach
    void stageCharts() throws Exception {
        // The runbook packages the shared chart with the migrations and the delivery chart with the vendored values.
        shared = temporary.resolve("shardshop");
        copy(SOURCE.resolve("infra/helm/shardshop"), shared);
        copy(SOURCE.resolve("database"), shared.resolve("migrations"));
        delivery = temporary.resolve("delivery");
        copy(SOURCE.resolve("infra/helm/delivery"), delivery);
        Files.createDirectories(delivery.resolve("files"));
        Files.copy(SOURCE.resolve("infra/helm/cnpg/values.yaml"), delivery.resolve("files/cnpg-values.yaml"));
        Files.copy(SOURCE.resolve("infra/helm/barman-cloud/values.yaml"), delivery.resolve("files/barman-cloud-values.yaml"));
    }

    @Test
    void argoCdGivesEachMigrationAttemptANewJobAndKeepsFailedAttempts() throws Exception {
        List<JsonNode> jobs = ofKind(shared("shardshop-migrations", "migrations", "argocd"), "Job");
        assertEquals(7, jobs.size());
        for (int index = 0; index < jobs.size(); index++) {
            JsonNode metadata = jobs.get(index).path("metadata");
            assertFalse(metadata.has("name"), "A fixed name collides with an earlier attempt or makes Argo CD delete it");
            assertTrue(metadata.path("generateName").asText().startsWith("shardshop-migrations-"));
            JsonNode annotations = metadata.path("annotations");
            assertEquals("PostSync", annotations.path("argocd.argoproj.io/hook").asText());
            assertEquals("HookSucceeded", annotations.path("argocd.argoproj.io/hook-delete-policy").asText(),
                    "Argo CD must never delete a failed or interrupted migration");
            assertEquals(String.valueOf(index), annotations.path("argocd.argoproj.io/sync-wave").asText(), "Migrations run one at a time");
            assertFalse(annotations.has("helm.sh/hook"));
            assertTrue(jobs.get(index).path("spec").path("ttlSecondsAfterFinished").asInt() >= 86400, "A failed attempt stays for an audit");
        }

        for (JsonNode configMap : ofKind(shared("shardshop-migrations", "migrations", "argocd"), "ConfigMap")) {
            assertEquals("IgnoreExtraneous", configMap.path("metadata").path("annotations").path("argocd.argoproj.io/compare-options").asText(),
                    "An earlier migration ConfigMap stays without prune and must not stop the later waves");
        }
        for (JsonNode job : ofKind(shared("shardshop-migrations", "migrations", "helm"), "Job")) {
            assertTrue(job.path("metadata").path("name").asText().endsWith("-r1"), "Helmfile keeps its revision-based names");
            assertEquals("post-install,post-upgrade", job.path("metadata").path("annotations").path("helm.sh/hook").asText());
        }
    }

    @Test
    void argoCdCreatesTheObjectStoreBeforeTheClusters() throws Exception {
        JsonNode argocd = ofKind(shared("shardshop-infrastructure", "infrastructure", "argocd"), "ObjectStore").getFirst();
        assertEquals("-1", argocd.path("metadata").path("annotations").path("argocd.argoproj.io/sync-wave").asText(),
                "The plugin stops a cluster that refers to a missing ObjectStore");
        JsonNode helm = ofKind(shared("shardshop-infrastructure", "infrastructure", "helm"), "ObjectStore").getFirst();
        assertFalse(helm.path("metadata").has("annotations"));
    }

    @Test
    void childApplicationsDeliverTheLayersInOrderFromPinnedChartsAndNeverDeleteData() throws Exception {
        List<JsonNode> applications = delivery("children");
        assertEquals(List.of("cnpg", "barman-cloud", "shardshop-namespace", "shardshop-infrastructure", "shardshop-migrations",
                "shardshop-routing"), applications.stream().map(application -> application.path("metadata").path("name").asText()).toList());
        for (int index = 0; index < applications.size(); index++) {
            JsonNode application = applications.get(index);
            String name = application.path("metadata").path("name").asText();
            assertEquals(String.valueOf(index), application.path("metadata").path("annotations").path("argocd.argoproj.io/sync-wave").asText(),
                    "The routing publication must come after the migrations");
            JsonNode source = application.path("spec").path("source");
            if (name.equals("shardshop-migrations")) {
                // A digest revision makes invalid names for the generated hook Jobs; ECR keeps the version immutable.
                assertEquals(REGISTRY + "/shardshop-charts", source.path("repoURL").asText());
                assertEquals("shardshop", source.path("chart").asText());
                assertEquals(VERSION, source.path("targetRevision").asText());
            } else {
                assertTrue(source.path("repoURL").asText().startsWith("oci://" + REGISTRY + "/shardshop-charts/"), name);
                assertEquals(DIGEST, source.path("targetRevision").asText(), name + " must have a digest pin");
            }
            assertFalse(application.path("spec").path("syncPolicy").path("automated").path("prune").asBoolean(true), name);
            assertFalse(application.path("metadata").has("finalizers"), name + " must not delete its resources with the Application");
        }
        assertEquals(Files.readString(SOURCE.resolve("infra/helm/cnpg/values.yaml")),
                applications.getFirst().path("spec").path("source").path("helm").path("values").asText(),
                "The operator uses the same vendored values as kind");
        assertEquals(JSON.readTree("[{\"group\":\"postgresql.cnpg.io\",\"kind\":\"DatabaseRole\",\"jsonPointers\":[\"/spec/login\"]}]"),
                applications.get(3).path("spec").path("ignoreDifferences"),
                "The webhook removes login: false, and an endless difference stops the later waves");
        JsonNode migrations = applications.get(4).path("spec").path("source").path("helm");
        assertEquals(JSON.readTree("[\"shards.yaml\",\"eks-values.yaml\"]"), migrations.path("valueFiles"));
        assertEquals("migrations", migrations.path("valuesObject").path("render").asText());
        assertEquals("argocd", migrations.path("valuesObject").path("delivery").asText());
        assertEquals("s3://shardshop-backups-123456789012-eu-west-2/",
                migrations.path("valuesObject").path("backup").path("destinationPath").asText());
    }

    @Test
    void rootApplicationKeepsTheChildrenInSyncFromTheDeliveryChart() throws Exception {
        List<JsonNode> root = delivery("root");
        assertEquals(1, root.size());
        JsonNode spec = root.getFirst().path("spec");
        assertEquals("oci://" + REGISTRY + "/shardshop-charts/shardshop-delivery", spec.path("source").path("repoURL").asText());
        assertEquals(DIGEST, spec.path("source").path("targetRevision").asText());
        assertFalse(spec.path("source").path("helm").path("valuesObject").has("mode"), "The root renders the children");
        assertTrue(spec.path("syncPolicy").path("automated").path("selfHeal").asBoolean());

        Path tag = values("tag.json", Map.of("charts", Map.of("delivery", DIGEST, "cnpg", DIGEST, "barmanCloud", DIGEST, "shardshop", "0.3.0")));
        assertNotEquals(0, helm(delivery, "shardshop", tag, "--set-string", "mode=children").exit(), "A movable tag is not a pin");
    }

    private List<JsonNode> shared(String release, String mode, String deliveryMode) throws Exception {
        Path account = values("account.json", Map.of("delivery", deliveryMode,
                "backup", Map.of("destinationPath", "s3://shardshop-backups-123456789012-eu-west-2/")));
        Render result = helm(shared, release, null, "--namespace", "shardshop", "--values", SOURCE.resolve("infra/shards.yaml").toString(),
                "--values", shared.resolve("eks-values.yaml").toString(), "--values", account.toString(), "--set-string", "render=" + mode);
        assertEquals(0, result.exit(), result.output());
        return KindSafety.infrastructureManifest(result.output());
    }

    private List<JsonNode> delivery(String mode) throws Exception {
        Render result = helm(delivery, "shardshop", deliveryValues(), "--set-string", "mode=" + mode);
        assertEquals(0, result.exit(), result.output());
        return KindSafety.infrastructureManifest(result.output());
    }

    private Path deliveryValues() throws Exception {
        return values("delivery.json", Map.of("registry", REGISTRY,
                "charts", Map.of("delivery", DIGEST, "cnpg", DIGEST, "barmanCloud", DIGEST, "shardshop", DIGEST, "shardshopVersion", VERSION),
                "account", Map.of("backup", Map.of("destinationPath", "s3://shardshop-backups-123456789012-eu-west-2/"))));
    }

    private Render helm(Path chart, String release, Path extra, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("helm", "template", release, chart.toString()));
        if (chart.equals(delivery)) command.addAll(List.of("--values", deliveryValues().toString()));
        if (extra != null) command.addAll(List.of("--values", extra.toString()));
        command.addAll(List.of(arguments));
        Path output = Files.createTempFile(temporary, "render", ".out");
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

    private static List<JsonNode> ofKind(List<JsonNode> resources, String kind) {
        return resources.stream().filter(resource -> kind.equals(resource.path("kind").asText())).toList();
    }

    private static void copy(Path from, Path to) throws Exception {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path file : files.toList()) {
                Path target = to.resolve(from.relativize(file).toString());
                if (Files.isDirectory(file)) Files.createDirectories(target);
                else Files.copy(file, target);
            }
        }
    }

    private record Render(int exit, String output) { }
}
