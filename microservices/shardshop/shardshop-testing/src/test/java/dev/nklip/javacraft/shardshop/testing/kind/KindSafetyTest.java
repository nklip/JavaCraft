package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.Json;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KindSafetyTest {
    private static final String UID = "01234567-89ab-cdef-0123-456789abcdef";
    private static final String IMAGE = "docker.io/kindest/node@sha256:" + "a".repeat(64);

    @Test
    void configurationRequiresTheReviewedShapeAndNetwork() {
        assertDoesNotThrow(() -> KindSafety.validateConfiguration(configuration(), "shardshop"));
        List<Consumer<ObjectNode>> changes = List.of(
                config -> config.put("name", "other"),
                config -> config.put("apiVersion", "kind.x-k8s.io/v1alpha3"),
                config -> config.put("kind", "Other"),
                config -> config.put("extra", true),
                config -> ((ObjectNode) config.path("networking")).put("apiServerAddress", "0.0.0.0"),
                config -> ((ObjectNode) config.path("networking")).put("apiServerPort", 6443),
                config -> ((ArrayNode) config.path("nodes")).remove(3),
                config -> ((ObjectNode) config.path("nodes").get(1)).put("role", "control-plane"),
                config -> ((ObjectNode) config.path("nodes").get(0)).put("image", "kindest/node:latest"),
                config -> ((ObjectNode) config.path("nodes").get(0)).putArray("extraMounts"));
        for (Consumer<ObjectNode> change : changes) {
            ObjectNode config = configuration();
            change.accept(config);
            AssertionError error = assertThrows(AssertionError.class, () -> KindSafety.validateConfiguration(config, "shardshop"));
            assertTrue(error.getMessage().contains("explicitly reviewed recreation"));
        }
    }

    @Test
    void retainedNodesIncludeStoppedContainersAndRequireExactNamesImagesRolesAndPorts() {
        assertDoesNotThrow(() -> KindSafety.validateNodes(configuration(), nodes(), "shardshop"));
        List<Consumer<ArrayNode>> changes = List.of(
                nodes -> nodes.remove(3),
                nodes -> nodes.add(nodes.get(0).deepCopy()),
                nodes -> ((ObjectNode) nodes.get(3)).put("Name", "/shardshop-worker4"),
                nodes -> ((ObjectNode) nodes.get(0).path("Config")).put("Image", "kindest/node:latest"),
                nodes -> ((ObjectNode) nodes.get(1).path("Config").path("Labels")).put("io.x-k8s.kind.role", "control-plane"),
                nodes -> ((ObjectNode) nodes.get(1).path("Config").path("Labels")).put("io.x-k8s.kind.cluster", "other"),
                nodes -> ((ObjectNode) nodes.get(0).path("HostConfig").path("PortBindings").path("6443/tcp").get(0))
                        .put("HostIp", "0.0.0.0"),
                nodes -> ((ObjectNode) nodes.get(1).path("HostConfig").path("PortBindings")).putArray("8080/tcp"));
        for (Consumer<ArrayNode> change : changes) {
            ArrayNode actual = nodes();
            change.accept(actual);
            assertThrows(AssertionError.class, () -> KindSafety.validateNodes(configuration(), actual, "shardshop"));
        }
        assertThrows(AssertionError.class, () -> KindSafety.validateNodes(configuration(), Json.newObject().putArray("items"), "shardshop"));
    }

    @Test
    void retainedTopologyRequiresBothMembershipOrderAndRegionHashes() {
        ObjectNode inventory = Json.object("{\"version\":\"membership-order\",\"regionVersion\":\"regions\"}");
        ObjectNode published = Json.newObject().set("data", inventory.deepCopy());
        assertDoesNotThrow(() -> KindSafety.validateTopology(inventory, Json.newObject(), "shardshop-routing"));
        assertDoesNotThrow(() -> KindSafety.validateTopology(inventory, published, "shardshop-routing"));
        for (String key : List.of("version", "regionVersion")) {
            ObjectNode changed = published.deepCopy();
            ((ObjectNode) changed.path("data")).put(key, "different");
            AssertionError error = assertThrows(AssertionError.class,
                    () -> KindSafety.validateTopology(inventory, changed, "shardshop-migration-topology"));
            assertTrue(error.getMessage().contains("explicit reset/reseed"));
        }
    }

    @Test
    void freshInitializationRequiresBothAllocatorObjectsAbsentAndRetentionRequiresBothPresent() {
        assertNull(KindSafety.validateGeneratorState(List.of(), 0));
        assertThrows(AssertionError.class, () -> KindSafety.validateGeneratorState(List.of(), 2));
        assertThrows(AssertionError.class, () -> KindSafety.validateGeneratorState(List.of(registry("0")), 2));
        assertThrows(AssertionError.class, () -> KindSafety.validateGeneratorState(List.of(identity()), 0));
        assertThrows(AssertionError.class, () -> KindSafety.validateGeneratorState(List.of(registry("0"), identity()), 0));
        AssertionError error = assertThrows(AssertionError.class,
                () -> KindSafety.validateGeneratorState(List.of(registry("0")), 2));
        assertTrue(error.getMessage().contains("latest independent allocator backup; never reset"));
    }

    @Test
    void allocatorCounterAcceptsCanonicalZeroThroughTheExhaustedBoundary() {
        for (String mark : List.of("0", "1", "1023")) {
            assertEquals(new KindSafety.GeneratorState(UID, mark),
                    KindSafety.validateGeneratorState(List.of(registry(mark), identity()), 2));
        }
        for (String mark : List.of("-1", "01", "1024", "10000", "1.0", " 1", "", "+1")) {
            assertThrows(AssertionError.class, () -> KindSafety.validateGeneratorState(List.of(registry(mark), identity()), 2));
        }
        ObjectNode numeric = registry("1");
        ((ObjectNode) numeric.path("data")).put("highWaterMark", 1);
        assertThrows(AssertionError.class, () -> KindSafety.validateGeneratorState(List.of(numeric, identity()), 2));
    }

    @Test
    void allocatorRejectsReplacedImmutableDeletingAndAdditionalDataState() {
        List<Consumer<ObjectNode>> registryChanges = List.of(
                node -> ((ObjectNode) node.path("metadata")).put("uid", "invalid"),
                node -> ((ObjectNode) node.path("metadata")).put("uid", "01234567-89ab-cdef-0123-456789abcdee"),
                node -> ((ObjectNode) node.path("metadata")).put("deletionTimestamp", "2026-10-13T00:00:00Z"),
                node -> node.put("immutable", true),
                node -> ((ObjectNode) node.path("data")).put("other", "data"),
                node -> node.putObject("binaryData").put("value", "AA=="));
        for (Consumer<ObjectNode> change : registryChanges) {
            ObjectNode registry = registry("0");
            change.accept(registry);
            AssertionError error = assertThrows(AssertionError.class,
                    () -> KindSafety.validateGeneratorState(List.of(registry, identity()), 2));
            assertTrue(error.getMessage().contains("never reset the counter"));
        }
        List<Consumer<ObjectNode>> identityChanges = List.of(
                node -> node.put("immutable", false),
                node -> node.put("immutable", "true"),
                node -> ((ObjectNode) node.path("data")).put("registryUid", "different"),
                node -> ((ObjectNode) node.path("data")).put("other", "data"),
                node -> ((ObjectNode) node.path("metadata")).put("deletionTimestamp", "2026-10-13T00:00:00Z"),
                node -> node.putObject("binaryData").put("value", "AA=="));
        for (Consumer<ObjectNode> change : identityChanges) {
            ObjectNode identity = identity();
            change.accept(identity);
            assertThrows(AssertionError.class, () -> KindSafety.validateGeneratorState(List.of(registry("0"), identity), 2));
        }
        assertThrows(AssertionError.class,
                () -> KindSafety.validateGeneratorState(List.of(registry("0"), registry("0")), 2));
    }

    @Test
    void admissionProbeRequiresPolicyDenialAndRejectsOtherFailuresAndUnexpectedSuccess() {
        assertTrue(KindSafety.admissionDenied(1, "shardshop-generator-retention denied request"));
        assertFalse(KindSafety.admissionDenied(0, "shardshop-generator-retention denied request"));
        assertFalse(KindSafety.admissionDenied(1, "Forbidden: denied request"));
        assertFalse(KindSafety.admissionDenied(1, "shardshop-generator-retention: connection refused"));
    }

    @Test
    void infrastructurePermitsResourceParameterAndPostgresMinorChangesWithApiDefaults() {
        ObjectNode desired = cluster();
        ObjectNode retained = cluster();
        retainAppliedSpec(retained);
        ((ObjectNode) retained.path("spec").path("storage")).put("resizeInUseVolumes", true);
        ((ObjectNode) retained.path("spec").path("bootstrap").path("initdb")).put("encoding", "UTF8");
        ((ObjectNode) retained.path("spec").path("affinity")).put("topologyKey", "kubernetes.io/hostname");
        ((ObjectNode) desired.path("spec")).put("imageName", "postgres:18.5@sha256:new");
        ((ObjectNode) desired.path("spec")).putObject("resources").putObject("limits").put("cpu", "2");
        ((ObjectNode) desired.path("spec")).putObject("postgresql").putObject("parameters").put("max_connections", "200");
        assertDoesNotThrow(() -> KindSafety.validateInfrastructure(List.of(desired), List.of(retained)));
        assertDoesNotThrow(() -> KindSafety.validateInfrastructure(List.of(desired), List.of()));
    }

    @Test
    void infrastructureRejectsChangedOrRemovedDeclarativeStoragePlacementAndBootstrap() {
        List<Consumer<ObjectNode>> changes = List.of(
                node -> ((ObjectNode) node.path("spec").path("storage")).put("size", "2Gi"),
                node -> ((ObjectNode) node.path("spec").path("storage")).put("storageClass", "other"),
                node -> ((ObjectNode) node.path("spec").path("storage")).remove("size"),
                node -> ((ObjectNode) node.path("spec").path("affinity")).put("enablePodAntiAffinity", false),
                node -> ((ObjectNode) node.path("spec")).remove("affinity"),
                node -> ((ObjectNode) node.path("spec").path("bootstrap").path("initdb")).put("database", "other"),
                node -> ((ObjectNode) node.path("spec").path("bootstrap").path("initdb")).remove("owner"),
                node -> ((ObjectNode) node.path("spec")).put("instances", 2),
                node -> ((ObjectNode) node.path("spec")).put("imageName", "postgres:19.1@sha256:new"),
                node -> ((ObjectNode) node.path("spec")).put("imageName", "postgres:latest"));
        for (Consumer<ObjectNode> change : changes) {
            ObjectNode desired = cluster();
            ObjectNode retained = cluster();
            retainAppliedSpec(retained);
            change.accept(desired);
            AssertionError error = assertThrows(AssertionError.class,
                    () -> KindSafety.validateInfrastructure(List.of(desired), List.of(retained)));
            assertTrue(error.getMessage().contains("does not migrate or reset database storage"));
        }
    }

    @Test
    void infrastructureRejectsLiveDriftAndFallsBackToLiveSpecWithoutAppliedAnnotation() {
        ObjectNode desired = cluster();
        ObjectNode retained = cluster();
        assertTrue(KindSafety.compatibleInfrastructure(desired, retained));
        retainAppliedSpec(retained);
        ((ObjectNode) retained.path("spec").path("storage")).put("size", "2Gi");
        assertFalse(KindSafety.compatibleInfrastructure(desired, retained));
        ObjectNode defaultsWithoutHistory = cluster();
        ((ObjectNode) defaultsWithoutHistory.path("spec").path("storage")).put("resizeInUseVolumes", true);
        assertFalse(KindSafety.compatibleInfrastructure(desired, defaultsWithoutHistory));
    }

    @Test
    void helmOwnedInfrastructureAcceptsApiDefaultsUsingItsStoredDeclaration() {
        ObjectNode declared = cluster();
        ObjectNode retained = helmOwnedCluster();
        ((ObjectNode) retained.path("spec").path("storage")).put("resizeInUseVolumes", true);
        ((ObjectNode) retained.path("spec").path("bootstrap").path("initdb")).put("encoding", "UTF8");
        ((ObjectNode) retained.path("spec").path("affinity")).put("topologyKey", "kubernetes.io/hostname");
        assertFalse(KindSafety.compatibleInfrastructure(declared, retained), "Live defaults are not a declaration");
        assertDoesNotThrow(() -> KindSafety.validateInfrastructure(List.of(declared), List.of(retained), List.of(declared)));
        // A stale kubectl annotation left by adoption must not replace Helm's current declaration.
        ObjectNode stale = cluster();
        ((ObjectNode) stale.path("spec").path("storage")).put("size", "2Gi");
        ((ObjectNode) retained.path("metadata").path("annotations"))
                .put("kubectl.kubernetes.io/last-applied-configuration", stale.toString());
        assertDoesNotThrow(() -> KindSafety.validateInfrastructure(List.of(declared), List.of(retained), List.of(declared)));
    }

    @Test
    void helmHistoryStillRejectsProtectedDeclarationChangesAndLiveDrift() {
        List<Consumer<ObjectNode>> changes = List.of(
                node -> ((ObjectNode) node.path("spec").path("storage")).put("size", "2Gi"),
                node -> ((ObjectNode) node.path("spec").path("storage")).remove("size"),
                node -> ((ObjectNode) node.path("spec")).remove("affinity"),
                node -> ((ObjectNode) node.path("spec").path("affinity")).put("enablePodAntiAffinity", false),
                node -> ((ObjectNode) node.path("spec").path("bootstrap").path("initdb")).remove("owner"),
                node -> ((ObjectNode) node.path("spec").path("bootstrap").path("initdb")).put("database", "other"));
        for (Consumer<ObjectNode> change : changes) {
            ObjectNode desired = cluster();
            ObjectNode retained = helmOwnedCluster();
            change.accept(desired);
            assertThrows(AssertionError.class,
                    () -> KindSafety.validateInfrastructure(List.of(desired), List.of(retained), List.of(cluster())));
            change.accept(retained);
            assertThrows(AssertionError.class,
                    () -> KindSafety.validateInfrastructure(List.of(cluster()), List.of(retained), List.of(cluster())));
        }
    }

    @Test
    void helmOwnedInfrastructureRequiresMatchingOwnershipAndUnambiguousHistory() {
        assertThrows(AssertionError.class,
                () -> KindSafety.validateInfrastructure(List.of(cluster()), List.of(helmOwnedCluster())));
        for (Consumer<ObjectNode> change : List.<Consumer<ObjectNode>>of(
                node -> ((ObjectNode) node.path("metadata")).put("namespace", "other"),
                node -> ((ObjectNode) node.path("metadata").path("labels")).put("app.kubernetes.io/managed-by", "other"),
                node -> ((ObjectNode) node.path("metadata").path("annotations")).put("meta.helm.sh/release-name", "other"),
                node -> ((ObjectNode) node.path("metadata").path("annotations")).put("meta.helm.sh/release-namespace", "other"))) {
            ObjectNode retained = helmOwnedCluster();
            change.accept(retained);
            assertThrows(AssertionError.class,
                    () -> KindSafety.validateInfrastructure(List.of(cluster()), List.of(retained), List.of(cluster())));
        }
        for (List<JsonNode> history : List.of(List.<JsonNode>of(), List.<JsonNode>of(cluster(), cluster()),
                List.<JsonNode>of(Json.object("{\"kind\":\"Cluster\",\"metadata\":{\"name\":\"shard-a\"}}")))) {
            assertThrows(AssertionError.class,
                    () -> KindSafety.validateInfrastructure(List.of(cluster()), List.of(helmOwnedCluster()), history));
        }
    }

    @Test
    void retainedGateReadsPinnedHelmHistoryAndRejectsPendingOrChangingReleases() throws Exception {
        KindLab lab = retainedLab();
        String status = "{\"name\":\"shardshop-infrastructure\",\"namespace\":\"shardshop\",\"version\":7,\"info\":{\"status\":\"deployed\"}}";
        String[] statusCommand = {"helm", "status", "shardshop-infrastructure", "--kube-context", "kind-unit-lab",
                "--namespace", "shardshop", "--output", "json"};
        when(lab.command(statusCommand)).thenReturn(status);
        when(lab.command("helm", "get", "manifest", "shardshop-infrastructure", "--kube-context", "kind-unit-lab",
                "--namespace", "shardshop", "--revision", "7"))
                .thenReturn("---\n# prior infrastructure\n" + cluster() + "\n---\n");
        assertDoesNotThrow(() -> new KindSafety(lab).retention());
        // Failed completed revisions remain retryable after fixing their original reconciliation failure.
        when(lab.command(statusCommand)).thenReturn(status.replace("deployed", "failed"));
        assertDoesNotThrow(() -> new KindSafety(lab).retention());
        for (String rejected : List.of("pending-install", "pending-upgrade", "pending-rollback", "uninstalled")) {
            when(lab.command(statusCommand)).thenReturn(status.replace("deployed", rejected));
            assertThrows(AssertionError.class, () -> new KindSafety(lab).retention());
        }
        when(lab.command(statusCommand)).thenReturn(status, status.replace("7", "8"));
        assertThrows(AssertionError.class, () -> new KindSafety(lab).retention());
        assertThrows(IOException.class, () -> KindSafety.infrastructureManifest("kind: [broken"));
    }

    @Test
    void retainedGateIgnoresUnrelatedHelmOwnedDatabaseClusters() throws Exception {
        KindLab lab = retainedLab();
        ObjectNode unrelated = helmOwnedCluster();
        ((ObjectNode) unrelated.path("metadata")).put("name", "another-application");
        ((ObjectNode) unrelated.path("metadata").path("annotations")).put("meta.helm.sh/release-name", "another-release");
        when(lab.kubectl("get", "clusters.postgresql.cnpg.io", "-o", "json"))
                .thenReturn("{\"items\":[" + cluster() + "," + unrelated + "]}");
        when(lab.command(any(String[].class))).thenThrow(new IOException("Unrelated Helm history must not be needed"));
        assertDoesNotThrow(() -> new KindSafety(lab).retention());
    }

    private static KindLab retainedLab() throws Exception {
        KindLab lab = mock(KindLab.class);
        when(lab.inventory()).thenReturn(Json.newObject());
        when(lab.clusterName()).thenReturn("unit-lab");
        when(lab.snapshot()).thenReturn(Path.of("/snapshot"));
        when(lab.kubectl(any(String[].class))).thenReturn("");
        when(lab.kubectl("get", "configmap", "shardshop-snowflake-generators", "shardshop-generator-identity",
                "--ignore-not-found", "-o", "json"))
                .thenReturn("{\"items\":[" + registry("0") + "," + identity() + "]}");
        when(lab.kubectl("create", "--dry-run=client", "--validate=false", "-f", "/snapshot/infrastructure.yaml", "-o", "json"))
                .thenReturn(cluster().toString());
        ObjectNode live = helmOwnedCluster();
        ((ObjectNode) live.path("spec").path("storage")).put("resizeInUseVolumes", true);
        when(lab.kubectl("get", "clusters.postgresql.cnpg.io", "-o", "json"))
                .thenReturn("{\"items\":[" + live + "]}");
        return lab;
    }

    private static ObjectNode helmOwnedCluster() {
        ObjectNode retained = cluster();
        ObjectNode metadata = (ObjectNode) retained.path("metadata");
        metadata.putObject("labels").put("app.kubernetes.io/managed-by", "Helm");
        metadata.putObject("annotations").put("meta.helm.sh/release-name", "shardshop-infrastructure")
                .put("meta.helm.sh/release-namespace", "shardshop");
        return retained;
    }

    private static ObjectNode configuration() {
        ObjectNode config = Json.object("{\"kind\":\"Cluster\",\"apiVersion\":\"kind.x-k8s.io/v1alpha4\","
                + "\"name\":\"shardshop\",\"networking\":{\"apiServerAddress\":\"127.0.0.1\"},\"nodes\":[]}");
        ArrayNode nodes = (ArrayNode) config.path("nodes");
        for (String role : List.of("control-plane", "worker", "worker", "worker")) {
            nodes.add(Json.newObject().put("role", role).put("image", IMAGE));
        }
        return config;
    }

    private static ArrayNode nodes() {
        ArrayNode nodes = Json.newObject().putArray("nodes");
        for (String suffix : List.of("control-plane", "worker", "worker2", "worker3")) {
            ObjectNode node = Json.newObject().put("Name", "/shardshop-" + suffix);
            ObjectNode config = node.putObject("Config").put("Image", IMAGE);
            config.putObject("Labels").put("io.x-k8s.kind.cluster", "shardshop")
                    .put("io.x-k8s.kind.role", suffix.equals("control-plane") ? "control-plane" : "worker");
            ObjectNode ports = node.putObject("HostConfig").putObject("PortBindings");
            if (suffix.equals("control-plane")) {
                ports.putArray("6443/tcp").add(Json.newObject().put("HostIp", "127.0.0.1").put("HostPort", "54321"));
            }
            node.putObject("State").put("Running", false);
            nodes.add(node);
        }
        return nodes;
    }

    private static ObjectNode registry(String mark) {
        ObjectNode registry = Json.newObject();
        registry.putObject("metadata").put("name", "shardshop-snowflake-generators").put("uid", UID);
        registry.putObject("data").put("highWaterMark", mark);
        return registry;
    }

    private static ObjectNode identity() {
        ObjectNode identity = Json.newObject().put("immutable", true);
        identity.putObject("metadata").put("name", "shardshop-generator-identity");
        identity.putObject("data").put("registryUid", UID);
        return identity;
    }

    private static ObjectNode cluster() {
        return Json.object("""
                {"apiVersion":"postgresql.cnpg.io/v1","kind":"Cluster","metadata":{"name":"shard-a","namespace":"shardshop"},"spec":{
                  "instances":3,"imageName":"postgres:18.4@sha256:old",
                  "storage":{"size":"1Gi","storageClass":"standard"},
                  "affinity":{"enablePodAntiAffinity":true},
                  "bootstrap":{"initdb":{"database":"shardshop","owner":"owner"}}
                }}
                """);
    }

    private static void retainAppliedSpec(ObjectNode retained) {
        String applied = retained.toString();
        ((ObjectNode) retained.path("metadata")).putObject("annotations")
                .put("kubectl.kubernetes.io/last-applied-configuration", applied);
    }
}
