package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Exercise the real chart with a local Helm lifecycle fixture, without Kubernetes or Docker. */
class HelmReleaseTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temporary;
    private Path source;
    private Path chart;
    private ObjectNode values;

    @BeforeEach
    void snapshotDesiredInputs() throws IOException {
        source = Path.of(System.getProperty("shardshop.directory", "..")).toAbsolutePath().normalize();
        chart = temporary.resolve("chart");
        copy(source.resolve("infra/helm/shardshop"), chart);
        copy(source.resolve("database"), chart.resolve("migrations"));
        values = JSON.createObjectNode();
        ObjectNode images = values.putObject("images");
        for (String application : List.of("product", "product-seeder", "product-reader")) {
            images.put(application, image(application, 'a'));
        }
    }

    @Test
    void dnsProbesGiveColdStartupItsOwnBudgetAndBoundDnsExecution() throws Exception {
        values.putObject("probe").put("run", "cold-start").put("nodes", "control-plane,worker,worker2,worker3");
        List<JsonNode> probes = KindLab.items(render("dns-probes").getFirst());
        JsonNode postgresqlImage = render("inventory").getFirst().path("data").path("postgresqlImage");
        assertEquals(List.of("control-plane", "worker", "worker2", "worker3"), probes.stream()
                .map(pod -> pod.path("spec").path("nodeName").asText()).toList());
        for (JsonNode pod : probes) {
            JsonNode spec = pod.path("spec");
            assertEquals(15 * 60 + 120, spec.path("activeDeadlineSeconds").asInt(),
                    "A cold image pull must not consume the DNS execution budget");
            assertEquals("Never", spec.path("restartPolicy").asText());
            JsonNode container = spec.path("containers").get(0);
            assertEquals("IfNotPresent", container.path("imagePullPolicy").asText());
            assertEquals(postgresqlImage, container.path("image"));
            assertEquals(JSON.valueToTree(List.of("timeout", "--kill-after=5s", "120s", "sh", "-ec",
                    "until getent hosts kubernetes.default.svc.cluster.local; do sleep 2; done")), container.path("command"),
                    "DNS must still resolve successfully within 120 seconds after the container starts");
        }
    }

    @Test
    void seedingRunsAfterApplicationReadinessAndRetainsVerificationEvidence() throws Exception {
        List<JsonNode> resources = render("application-release");
        assertEquals(resources, render("application-release"));
        JsonNode product = named(resources, "shardshop-product");
        JsonNode reader = named(resources, "shardshop-product-reader");
        JsonNode job = jobs(resources).getFirst();
        assertEquals("post-install,post-upgrade", annotation(job, "helm.sh/hook"));
        assertEquals("before-hook-creation", annotation(job, "helm.sh/hook-delete-policy"));
        assertEquals(86400, job.path("spec").path("ttlSecondsAfterFinished").asInt());
        assertEquals(300, job.path("spec").path("activeDeadlineSeconds").asInt());
        assertEquals(job.path("metadata").path("name").asText(), annotation(reader, "shardshop.javacraft/seeder-job"));
        assertEquals("catalog-v1", env(job, "SHARDSHOP_SEEDER_CATALOG_VERSION"));
        assertEquals("catalog-v1", env(reader, "SHARDSHOP_READER_CATALOG_VERSION"));
        assertEquals("http", product.path("spec").path("template").path("spec").path("containers").get(0)
                .path("readinessProbe").path("tcpSocket").path("port").asText());
        assertTrue(resources.stream().anyMatch(resource -> resource.path("metadata").path("name").asText().equals("shardshop-routing")));
        KindWorkloadPolicy.httpOnly(reader.path("spec").path("template").path("spec"));
        KindWorkloadPolicy.httpOnly(job.path("spec").path("template").path("spec"));
    }

    @Test
    void releaseAcceptanceUsesTheNativeDesiredTemplatesAndIgnoresUnrelatedApiDefaults() throws Exception {
        List<JsonNode> resources = render("application-release");
        for (String name : List.of("shardshop-product", "shardshop-product-reader", "shardshop-application-seeder-1")) {
            JsonNode desired = named(resources, name).path("spec").path("template");
            ObjectNode live = desired.deepCopy();
            ((ObjectNode) live.path("spec")).put("dnsPolicy", "ClusterFirst").put("schedulerName", "default-scheduler");
            ((ObjectNode) live.path("metadata").path("labels")).put("pod-template-hash", "api-generated");
            assertTrue(KindRelease.templateMatches(desired, live), name);
            ((ObjectNode) live.path("spec").path("containers").get(0)).put("image", image("product", 'b'));
            assertFalse(KindRelease.templateMatches(desired, live), name + " must use the qualified invocation image");
        }
    }

    @Test
    void releaseAcceptanceRejectsStaleConfigurationBindingsAndMounts() throws Exception {
        JsonNode desired = named(render("application-release"), "shardshop-product").path("spec").path("template");
        List<Consumer<ObjectNode>> changes = List.of(
                live -> object(namedField(live.path("spec").path("volumes"), "config").path("configMap"))
                        .put("name", "old-product-config"),
                live -> object(namedField(container(live).path("volumeMounts"), "config")).put("mountPath", "/old/config"),
                live -> object(namedField(container(live).path("volumeMounts"), "config")).put("readOnly", false),
                live -> object(namedField(live.path("spec").path("volumes"), "catalog-ca").path("projected")
                        .path("sources").get(0).path("secret")).put("name", "old-catalog-ca"));
        assertRejectedChanges(desired, changes);
    }

    @Test
    void releaseAcceptanceRejectsChangedReadinessAndSecurity() throws Exception {
        JsonNode desired = named(render("application-release"), "shardshop-product").path("spec").path("template");
        List<Consumer<ObjectNode>> changes = List.of(
                live -> object(container(live).path("readinessProbe").path("tcpSocket")).put("port", 9090),
                live -> container(live).remove("readinessProbe"),
                live -> object(container(live).path("securityContext")).put("allowPrivilegeEscalation", true),
                live -> object(container(live).path("securityContext")).put("readOnlyRootFilesystem", false),
                live -> object(live.path("spec").path("securityContext")).put("runAsNonRoot", false),
                live -> object(live.path("spec")).put("serviceAccountName", "foreign-account"));
        assertRejectedChanges(desired, changes);
    }

    @Test
    void releaseAcceptanceRejectsInjectedRuntimeOverridesAndExtraConfiguration() throws Exception {
        List<JsonNode> resources = render("application-release");
        for (String name : List.of("shardshop-product", "shardshop-product-reader", "shardshop-application-seeder-1")) {
            JsonNode desired = named(resources, name).path("spec").path("template");
            List<Consumer<ObjectNode>> changes = List.of(
                    live -> container(live).putArray("command").add("/bin/sh"),
                    live -> container(live).putArray("args").add("-Dquarkus.http.port=9090"),
                    live -> container(live).putArray("envFrom").addObject().putObject("configMapRef").put("name", "old-settings"),
                    live -> ((ArrayNode) live.path("spec").path("volumes")).addObject().put("name", "extra-config")
                            .putObject("configMap").put("name", "old-settings"),
                    live -> object(live.path("spec")).putArray("initContainers").addObject().put("name", "rewrite-config")
                            .put("image", image("product", 'b')));
            assertRejectedChanges(desired, changes);
        }
    }

    @Test
    void releaseAcceptanceAllowsDefaultedProductPodAndItsAdmittedServiceAccountToken() throws Exception {
        JsonNode desired = named(render("application-release"), "shardshop-product").path("spec").path("template");
        ObjectNode live = desired.deepCopy();
        live.putObject("status");
        ObjectNode spec = object(live.path("spec"));
        spec.put("dnsPolicy", "ClusterFirst").put("schedulerName", "default-scheduler").put("restartPolicy", "Always")
                .put("terminationGracePeriodSeconds", 30).put("serviceAccount", "shardshop-product").put("nodeName", "unit-lab-worker");
        spec.putArray("tolerations").addObject().put("key", "node.kubernetes.io/not-ready").put("operator", "Exists")
                .put("effect", "NoExecute").put("tolerationSeconds", 300);
        ((ArrayNode) spec.path("tolerations")).addObject().put("key", "node.kubernetes.io/unreachable").put("operator", "Exists")
                .put("effect", "NoExecute").put("tolerationSeconds", 300);
        ObjectNode container = container(live);
        container.put("imagePullPolicy", "IfNotPresent").put("terminationMessagePath", "/dev/termination-log")
                .put("terminationMessagePolicy", "File");
        object(container.path("ports").get(0)).put("protocol", "TCP");
        object(container.path("readinessProbe")).put("timeoutSeconds", 1).put("successThreshold", 1).put("failureThreshold", 3);
        for (JsonNode volume : spec.path("volumes")) {
            for (String type : List.of("configMap", "projected"))
                if (volume.has(type)) object(volume.path(type)).put("defaultMode", 420);
        }
        ((ArrayNode) spec.path("volumes")).add(JSON.readTree("""
                {"name":"kube-api-access-abc12","projected":{"defaultMode":420,"sources":[
                {"serviceAccountToken":{"expirationSeconds":3607,"path":"token"}},
                {"configMap":{"name":"kube-root-ca.crt","items":[{"key":"ca.crt","path":"ca.crt"}]}},
                {"downwardAPI":{"items":[{"path":"namespace","fieldRef":{"apiVersion":"v1","fieldPath":"metadata.namespace"}}]}}]}}
                """));
        ((ArrayNode) container.path("volumeMounts")).addObject().put("name", "kube-api-access-abc12")
                .put("mountPath", "/var/run/secrets/kubernetes.io/serviceaccount").put("readOnly", true);
        object(live.path("metadata").path("labels")).put("pod-template-hash", "api-generated");
        assertTrue(KindRelease.templateMatches(desired, live));
        object(spec.path("volumes").get(spec.path("volumes").size() - 1).path("projected").path("sources").get(1)
                .path("configMap")).put("name", "old-settings");
        assertFalse(KindRelease.templateMatches(desired, live), "A token-like volume must not hide arbitrary configuration");
    }

    private static void assertRejectedChanges(JsonNode desired, List<Consumer<ObjectNode>> changes) {
        assertAll(changes.stream().<org.junit.jupiter.api.function.Executable>map(change -> () -> {
            ObjectNode live = desired.deepCopy();
            change.accept(live);
            assertFalse(KindRelease.templateMatches(desired, live), "Changed chart pod specification must be rejected");
        }));
    }

    private static ObjectNode object(JsonNode node) { return (ObjectNode) node; }

    private static ObjectNode container(JsonNode template) {
        return object(template.path("spec").path("containers").get(0));
    }

    private static JsonNode namedField(JsonNode fields, String name) {
        for (JsonNode field : fields) if (field.path("name").asText().equals(name)) return field;
        throw new AssertionError("Missing field " + name);
    }

    @Test
    void aNewHelmRevisionRerunsSeedingWithoutRollingUnchangedApplicationPods() throws Exception {
        List<JsonNode> before = render("application-release");
        releaseRevision(2);
        List<JsonNode> after = render("application-release");
        JsonNode oldJob = jobs(before).getFirst();
        JsonNode newJob = jobs(after).getFirst();
        assertNotEquals(oldJob.path("metadata").path("name"), newJob.path("metadata").path("name"));
        assertEquals("shardshop-application-seeder-2", newJob.path("metadata").path("name").asText());
        for (String application : List.of("shardshop-product", "shardshop-product-reader")) {
            assertEquals(named(before, application).path("spec"), named(after, application).path("spec"));
        }
        JsonNode reader = named(after, "shardshop-product-reader");
        assertEquals(newJob.path("metadata").path("name").asText(), annotation(reader, "shardshop.javacraft/seeder-job"));
        assertFalse(reader.path("spec").path("template").path("metadata").path("labels").has("shardshop.javacraft/run"));
    }

    @Test
    void changingRestartTokenChangesOnlyProductAndReaderPodTemplatesAndSameTokenIsStable() throws Exception {
        List<JsonNode> before = render("application-release");
        List<JsonNode> infrastructureBefore = render("infrastructure");
        String firstToken = "restart-" + "a".repeat(24);
        values.put("restartToken", firstToken);
        List<JsonNode> restarted = render("application-release");
        assertEquals(restarted, render("application-release"), "Rendering the same token must preserve pod templates");
        values.put("restartToken", "restart-" + "b".repeat(24));
        List<JsonNode> restartedAgain = render("application-release");
        for (String application : List.of("shardshop-product", "shardshop-product-reader")) {
            JsonNode initialTemplate = named(before, application).path("spec").path("template");
            JsonNode restartedTemplate = named(restarted, application).path("spec").path("template");
            JsonNode nextTemplate = named(restartedAgain, application).path("spec").path("template");
            assertFalse(initialTemplate.path("metadata").path("annotations").has("shardshop.javacraft/restart"));
            assertEquals(firstToken, annotation(restartedTemplate, "shardshop.javacraft/restart"));
            assertNotEquals(initialTemplate, restartedTemplate);
            assertNotEquals(restartedTemplate, nextTemplate);
            assertEquals(initialTemplate.path("spec"), restartedTemplate.path("spec"));
            ObjectNode withoutRestart = restartedTemplate.deepCopy();
            object(withoutRestart.path("metadata").path("annotations")).remove("shardshop.javacraft/restart");
            // Reader has no other pod annotations; absence and an empty map have equal Kubernetes behavior.
            if (withoutRestart.path("metadata").path("annotations").isEmpty()) {
                object(withoutRestart.path("metadata")).remove("annotations");
            }
            assertEquals(initialTemplate, withoutRestart, "The explicit restart must only change its annotation");
        }
        assertEquals(jobs(before), jobs(restarted), "A restart token must not change seeding inputs");
        assertEquals(before.stream().filter(resource -> !resource.path("kind").asText().equals("Deployment")).toList(),
                restarted.stream().filter(resource -> !resource.path("kind").asText().equals("Deployment")).toList());
        assertEquals(infrastructureBefore, render("infrastructure"), "Restarting applications must preserve infrastructure");
    }

    @Test
    void malformedRestartTokensAreRejectedBeforeDeployment() throws Exception {
        for (String invalid : List.of("restart-", "restart-" + "a".repeat(23), "restart-" + "a".repeat(25),
                "restart-" + "A".repeat(24), "arbitrary-token", "restart-" + "a".repeat(24) + "\n")) {
            values.put("restartToken", invalid);
            assertRejected("application-release");
        }
        values.put("restartToken", "");
        render("application-release");
    }

    @Test
    void changedConfigurationAndImagesRollOnlyTheirAffectedApplicationPods() throws Exception {
        List<JsonNode> before = render("application-release");
        ((ObjectNode) values.path("images")).put("product-seeder", image("product-seeder", 'b'));
        List<JsonNode> seederChanged = render("application-release");
        for (String application : List.of("shardshop-product", "shardshop-product-reader")) {
            assertEquals(named(before, application).path("spec"), named(seederChanged, application).path("spec"));
        }
        assertNotEquals(jobs(before).getFirst().path("spec"), jobs(seederChanged).getFirst().path("spec"));
        values.putObject("product").putObject("configuration").put("shardshop.catalog.max-pool-size", "6");
        List<JsonNode> productChanged = render("application-release");
        assertNotEquals(named(before, "shardshop-product").path("spec"), named(productChanged, "shardshop-product").path("spec"));
        assertTrue(named(productChanged, "shardshop-product-config").path("data").path("application.properties")
                .asText().contains("shardshop.catalog.max-pool-size=6\n"));
        values.putObject("productReader").putObject("configuration").put("SHARDSHOP_READER_REQUEST_RATE", "220");
        List<JsonNode> readerChanged = render("application-release");
        assertEquals("220", env(named(readerChanged, "shardshop-product-reader"), "SHARDSHOP_READER_REQUEST_RATE"));
        assertEquals(named(productChanged, "shardshop-product").path("spec"), named(readerChanged, "shardshop-product").path("spec"));
        assertNotEquals(named(productChanged, "shardshop-product-reader").path("spec"), named(readerChanged, "shardshop-product-reader").path("spec"));
        ((ObjectNode) values.path("images")).put("product", image("product", 'c'));
        assertNotEquals(named(readerChanged, "shardshop-product").path("spec"), named(render("application-release"), "shardshop-product").path("spec"));
    }


    @Test
    void hardcodedMountedConfigurationChangesRollProductWithoutImageOrValueChanges() throws Exception {
        List<JsonNode> before = render("application-release");
        Path helper = chart.resolve("templates/_helpers.tpl");
        String definition = "{{- define \"shardshop.productConfiguration\" -}}\n";
        Files.writeString(helper, Files.readString(helper).replace(definition,
                definition + "shardshop.catalog.max-pool-size=6\n"));
        List<JsonNode> after = render("application-release");
        assertNotEquals(named(before, "shardshop-product-config").path("data"),
                named(after, "shardshop-product-config").path("data"));
        assertTrue(named(after, "shardshop-product-config").path("data").path("application.properties")
                .asText().startsWith("shardshop.catalog.max-pool-size=6\n"));
        JsonNode beforeTemplate = named(before, "shardshop-product").path("spec").path("template");
        JsonNode afterTemplate = named(after, "shardshop-product").path("spec").path("template");
        assertNotEquals(beforeTemplate.path("metadata").path("annotations").path("shardshop.javacraft/configuration"),
                afterTemplate.path("metadata").path("annotations").path("shardshop.javacraft/configuration"));
        assertEquals(beforeTemplate.path("spec"), afterTemplate.path("spec"), "Only the config checksum changes in the pod spec");
        assertEquals(named(before, "shardshop-product-reader").path("spec"), named(after, "shardshop-product-reader").path("spec"));
    }

    @Test
    void migrationsRunInSequentialStreamOrderWithNormalImmutableSqlConfigMaps() throws Exception {
        List<JsonNode> resources = render("migrations");
        List<JsonNode> jobs = orderedJobs(resources);
        assertEquals(List.of("shard-a/catalog", "shard-b/catalog", "shard-c/catalog", "shard-a/ordering", "shard-b/ordering",
                "shard-c/ordering", "ledger-db/ledger"), jobs.stream().map(job -> job.path("metadata").path("labels")
                .path("shardshop.javacraft/migration-target").asText() + "/" + job.path("metadata").path("labels")
                .path("app.kubernetes.io/component").asText()).toList());
        assertEquals(3, resources.stream().filter(resource -> resource.path("kind").asText().equals("ConfigMap")).count());
        for (int index = 0; index < jobs.size(); index++) {
            JsonNode job = jobs.get(index);
            assertEquals(Integer.toString(index), annotation(job, "helm.sh/hook-weight"));
            assertEquals("post-install,post-upgrade", annotation(job, "helm.sh/hook"));
            assertEquals("hook-succeeded", annotation(job, "helm.sh/hook-delete-policy"));
            assertEquals(86400, job.path("spec").path("ttlSecondsAfterFinished").asInt());
            assertEquals(180, job.path("spec").path("activeDeadlineSeconds").asInt());
            assertEquals(0, job.path("spec").path("backoffLimit").asInt());
            String configName = job.path("spec").path("template").path("spec").path("volumes").get(0).path("configMap").path("name").asText();
            JsonNode config = named(resources, configName);
            assertTrue(config.path("immutable").asBoolean());
            assertFalse(config.path("metadata").path("annotations").has("helm.sh/hook"), "SQL must exist before post-hooks execute");
            String stream = job.path("metadata").path("labels").path("app.kubernetes.io/component").asText();
            Path directory = source.resolve(stream.equals("ledger") ? "database/ledger/ledger" : "database/shard/" + stream);
            try (var files = Files.list(directory)) {
                for (Path file : files.toList()) {
                    assertEquals(Files.readString(file), config.path("data").path(file.getFileName().toString()).asText());
                }
            }
        }
    }

    @Test
    void everyHelmRevisionRunsFlywayEvenWhenSqlReturnsToAnEarlierState() throws Exception {
        List<JsonNode> first = render("migrations");
        Path sql = chart.resolve("migrations/shard/catalog/V1__catalog.sql");
        Files.writeString(sql, Files.readString(sql) + "\n-- checksum change must be validated\n");
        releaseRevision(2);
        List<JsonNode> second = render("migrations");
        Files.writeString(sql, Files.readString(source.resolve("database/shard/catalog/V1__catalog.sql")));
        releaseRevision(3);
        List<JsonNode> third = render("migrations");
        for (int index = 0; index < 7; index++) {
            assertNotEquals(orderedJobs(first).get(index).path("metadata").path("name"), orderedJobs(second).get(index).path("metadata").path("name"));
            assertNotEquals(orderedJobs(first).get(index).path("metadata").path("name"), orderedJobs(third).get(index).path("metadata").path("name"));
        }
        assertNotEquals(mountedConfig(orderedJobs(first).getFirst()), mountedConfig(orderedJobs(second).getFirst()));
        assertEquals(mountedConfig(orderedJobs(first).getFirst()), mountedConfig(orderedJobs(third).getFirst()));
        assertFalse(orderedJobs(third).getFirst().path("metadata").path("annotations").has("shardshop.javacraft/revision"));
    }

    @Test
    void infrastructureRetainsDurableResourcesAndDoesNotManageLiveAllocatorState() throws Exception {
        List<JsonNode> resources = render("infrastructure");
        assertTrue(resources.stream().noneMatch(resource -> resource.path("kind").asText().equals("Namespace")),
                "The lifecycle creates the namespace before Helm, so it must remain unmanaged");
        assertEquals("Namespace", render("namespace").getFirst().path("kind").asText());
        for (JsonNode resource : resources) {
            assertEquals("keep", annotation(resource, "helm.sh/resource-policy"), resource.path("kind") + "/" + resource.path("metadata").path("name"));
            String name = resource.path("metadata").path("name").asText();
            assertNotEquals("shardshop-snowflake-generators", name);
            assertNotEquals("shardshop-generator-identity", name);
        }
        JsonNode topology = named(resources, "shardshop-migration-topology");
        assertTrue(topology.path("immutable").asBoolean());
        JsonNode inventory = render("inventory").getFirst().path("data");
        assertEquals(inventory.path("version"), topology.path("data").path("version"));
        assertEquals(inventory.path("regionVersion"), topology.path("data").path("regionVersion"));
    }

    @Test
    void longestAllowedShardNamesHaveDistinctValidHookNames() throws Exception {
        String prefix = "long-shard-" + "a".repeat(32);
        var shards = values.putArray("shards");
        shards.addObject().put("name", prefix + "a").put("region", "US");
        shards.addObject().put("name", prefix + "b").put("region", "EU");
        List<String> names = jobs(render("migrations")).stream().map(job -> job.path("metadata").path("name").asText()).toList();
        assertEquals(5, names.size());
        assertEquals(names.size(), names.stream().distinct().count());
        for (String name : names) assertTrue(name.length() <= 63 && name.matches("[a-z0-9]([a-z0-9-]*[a-z0-9])?"));
    }

    @Test
    void explicitLegacyRunsAndMigrationRenderingRemainAvailableWithoutExecutionCaches() throws Exception {
        values.put("run", "drill-1");
        JsonNode seed = render("seeding").getFirst();
        assertEquals("shardshop-product-seeder-drill-1", seed.path("metadata").path("name").asText());
        assertFalse(seed.path("metadata").path("annotations").has("helm.sh/hook"));
        assertEquals("drill-1", named(render("applications"), "shardshop-product-reader").path("spec").path("template")
                .path("metadata").path("labels").path("shardshop.javacraft/run").asText());
        delete(chart.resolve("migrations"));
        values.put("migrationShard", "shard-a");
        List<JsonNode> legacy = render("migration");
        assertEquals(1, legacy.size());
        assertEquals("shard-a-catalog-migration", legacy.getFirst().path("metadata").path("name").asText());
        assertRejected("migrations");
        assertRejected("migration-plan");
        assertRejected("release");
    }

    @Test
    void unsafeConfigurationInvalidDigestsAndPartialSnapshotsFailDuringRendering() throws Exception {
        for (String key : List.of("JAVA_TOOL_OPTIONS", "SHARDSHOP_READER_PRODUCT_URL", "SHARDSHOP_READER_CATALOG_VERSION")) {
            values.putObject("productReader").putObject("configuration").put(key, "invalid");
            assertRejected("application-release");
        }
        values.remove("productReader");
        values.put("datasetVersion", "catalog-v2");
        assertRejected("application-release");
        values.put("datasetVersion", "catalog-v1");
        ((ObjectNode) values.path("images")).put("product", "docker.io/library/shardshop-product:latest");
        assertRejected("application-release");
        ((ObjectNode) values.path("images")).put("product", image("product", 'a'));
        Files.delete(chart.resolve("migrations/shard/catalog/flyway.toml"));
        assertRejected("migrations");
        values.put("migrationShard", "shard-a").put("migrationStream", "ledger");
        assertRejected("migration");
    }

    private void releaseRevision(int revision) throws IOException {
        // Helm template normally represents revision 1; this fixture supplies the native lifecycle input.
        Files.writeString(chart.resolve("templates/zz-release-fixture.yaml"), "{{- $_ := set .Release \"Revision\" " + revision + " -}}\n");
    }

    private List<JsonNode> render(String mode) throws Exception {
        Render result = helm(mode);
        assertEquals(0, result.exit(), result.output());
        List<JsonNode> documents = new ArrayList<>();
        for (Object document : new Yaml(new SafeConstructor(new LoaderOptions())).loadAll(result.output())) {
            if (document != null) documents.add(JSON.valueToTree(document));
        }
        return documents;
    }

    private void assertRejected(String mode) throws Exception {
        assertNotEquals(0, helm(mode).exit(), "Invalid desired inputs must be rejected by Helm");
    }

    private Render helm(String mode) throws Exception {
        Path override = temporary.resolve("overrides.json");
        Files.writeString(override, values.toString());
        Path output = temporary.resolve("helm-output.txt");
        String release = mode.equals("migrations") ? "shardshop-migrations" : "shardshop-application";
        Process process = new ProcessBuilder("helm", "template", release, chart.toString(), "--namespace", "shardshop",
                "--values", source.resolve("infra/shards.yaml").toString(), "--values", override.toString(),
                "--set-string", "render=" + mode).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        process.getOutputStream().close();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Helm rendering exceeded its deadline");
            return new Render(process.exitValue(), Files.readString(output));
        } finally {
            process.destroyForcibly();
        }
    }

    private static List<JsonNode> jobs(List<JsonNode> resources) {
        return resources.stream().filter(resource -> resource.path("kind").asText().equals("Job")).toList();
    }

    private static List<JsonNode> orderedJobs(List<JsonNode> resources) {
        return jobs(resources).stream().sorted(Comparator.comparingInt(job -> Integer.parseInt(annotation(job, "helm.sh/hook-weight")))).toList();
    }

    private static String mountedConfig(JsonNode job) {
        return job.path("spec").path("template").path("spec").path("volumes").get(0).path("configMap").path("name").asText();
    }

    private static JsonNode named(List<JsonNode> resources, String name) {
        return resources.stream().filter(resource -> resource.path("metadata").path("name").asText().equals(name))
                .filter(resource -> !resource.path("kind").asText().equals("Service")).findFirst().orElseThrow();
    }

    private static String annotation(JsonNode resource, String key) {
        return resource.path("metadata").path("annotations").path(key).asText();
    }

    private static String env(JsonNode resource, String name) {
        for (JsonNode variable : resource.path("spec").path("template").path("spec").path("containers").get(0).path("env")) {
            if (variable.path("name").asText().equals(name)) return variable.path("value").asText();
        }
        throw new AssertionError("Missing setting " + name);
    }

    private static String image(String application, char digest) {
        return "docker.io/library/shardshop-" + application + "@sha256:" + String.valueOf(digest).repeat(64);
    }

    private static void copy(Path source, Path destination) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path target = destination.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(target);
                else Files.copy(path, target);
            }
        }
    }

    private static void delete(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    private record Render(int exit, String output) { }
}
