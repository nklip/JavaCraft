package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KindReleaseTest {
    private static final String IMAGE = "docker.io/library/shardshop-product@sha256:" + "a".repeat(64);
    private static ObjectNode json(String text) { return (ObjectNode) KindLab.parse(text); }
    private static ObjectNode object(JsonNode node) { return (ObjectNode) node; }

    private record Deployment(ObjectNode desired, ObjectNode live, ObjectNode replicaSet, List<JsonNode> pods) {
        void verify() throws IOException { KindRelease.verifyDeployment(desired, live, List.of(replicaSet), pods); }
    }

    private static Deployment deployment() { return deployment("product", 2); }

    private static Deployment deployment(String application, int count) {
        ObjectNode desired = json("""
                {"kind":"Deployment","metadata":{"name":"shardshop-product"},"spec":{"replicas":2,
                "selector":{"matchLabels":{"app.kubernetes.io/name":"shardshop-product"}},
                "template":{"metadata":{"labels":{"app.kubernetes.io/name":"shardshop-product"},
                "annotations":{"shardshop.javacraft/configuration":"desired-config","shardshop.javacraft/restart":"restart-current"}},
                "spec":{"containers":[{"name":"product","image":"IMAGE",
                "env":[{"name":"SETTING","value":"desired"}],"resources":{"limits":{"cpu":"2","memory":"1024Mi"}},
                "volumeMounts":[{"name":"config","mountPath":"/app/config","readOnly":true}]}],
                "volumes":[{"name":"config","configMap":{"name":"shardshop-product-config"}}]}}}}
                """.replace("IMAGE", IMAGE).replace("shardshop-product", "shardshop-" + application));
        object(desired.path("spec")).put("replicas", count);
        object(desired.path("spec").path("template").path("spec").path("containers").get(0))
                .put("name", application).put("image", IMAGE.replace("shardshop-product", "shardshop-" + application));
        ObjectNode live = desired.deepCopy();
        object(live.path("metadata")).put("uid", "deployment-uid").put("generation", 3)
                .putObject("annotations").put("deployment.kubernetes.io/revision", "2");
        live.putObject("status").put("observedGeneration", 3).put("replicas", count).put("updatedReplicas", count)
                .put("readyReplicas", count).put("availableReplicas", count);
        ObjectNode rs = json("{\"metadata\":{\"name\":\"product-rs\",\"uid\":\"rs-uid\",\"annotations\":{\"deployment.kubernetes.io/revision\":\"2\"}},\"spec\":{}}");
        object(rs.path("spec")).set("template", desired.path("spec").path("template").deepCopy());
        owner(rs, live, "Deployment");
        List<JsonNode> pods = new ArrayList<>();
        for (int number = 1; number <= count; number++)
            pods.add(pod(desired, rs, application + "-" + number, "10.0.0." + number));
        return new Deployment(desired, live, rs, List.copyOf(pods));
    }

    private static ObjectNode pod(JsonNode desired, JsonNode owner, String name, String ip) {
        ObjectNode pod = desired.path("spec").path("template").deepCopy();
        object(pod.path("metadata")).put("name", name).put("uid", name + "-uid");
        owner(pod, owner, "ReplicaSet");
        ObjectNode status = pod.putObject("status").put("phase", "Running").put("podIP", ip);
        status.putArray("conditions").addObject().put("type", "Ready").put("status", "True");
        JsonNode container = desired.path("spec").path("template").path("spec").path("containers").get(0);
        status.putArray("containerStatuses").addObject().put("name", container.path("name").asText())
                .put("imageID", container.path("image").asText());
        return pod;
    }

    private static void owner(ObjectNode child, JsonNode owner, String kind) {
        object(child.path("metadata")).putArray("ownerReferences").addObject().put("controller", true)
                .put("kind", kind).put("name", KindDeployment.name(owner)).put("uid", owner.path("metadata").path("uid").asText());
    }

    @Test
    void qualifiedImagesAreRequiredForEveryApplication() throws IOException {
        ObjectNode images = json("{\"images\":{}}");
        images.put("restartToken", "restart-" + "c".repeat(24));
        for (String name : List.of("product", "product-reader", "product-seeder", "order", "ledger", "order-producer"))
            object(images.path("images")).put(name, IMAGE.replace("shardshop-product", "shardshop-" + name));
        KindRelease.qualifiedImages(images);
        for (String name : List.of("product", "product-reader", "product-seeder", "order", "ledger", "order-producer")) {
            ObjectNode invalid = images.deepCopy();
            object(invalid.path("images")).remove(name);
            assertThrows(IOException.class, () -> KindRelease.qualifiedImages(invalid));
            object(invalid.path("images")).put(name, "shardshop-product:latest");
            assertThrows(IOException.class, () -> KindRelease.qualifiedImages(invalid));
        }
        for (String token : List.of("", "restart-old", "restart-" + "c".repeat(23))) {
            ObjectNode invalid = images.deepCopy();
            invalid.put("restartToken", token);
            assertThrows(IOException.class, () -> KindRelease.qualifiedImages(invalid));
        }
        images.remove("restartToken");
        assertThrows(IOException.class, () -> KindRelease.qualifiedImages(images));
    }

    @Test
    void failedOrPendingReleasesCannotBorrowAnOlderSuccessfulRevision() throws IOException {
        ObjectNode release = json("{\"name\":\"shardshop-application\",\"namespace\":\"shardshop\",\"version\":5,\"info\":{\"status\":\"deployed\"}}");
        assertEquals(5, KindRelease.deployedRevision(release));
        for (String state : List.of("failed", "pending-upgrade", "superseded", "uninstalled")) {
            object(release.path("info")).put("status", state);
            assertThrows(IOException.class, () -> KindRelease.deployedRevision(release), state);
        }
        object(release.path("info")).put("status", "deployed");
        release.put("version", 0);
        assertThrows(IOException.class, () -> KindRelease.deployedRevision(release));
    }

    @Test
    void currentOwnedPodsAndEquivalentApiResourceQuantitiesPass() throws IOException {
        Deployment fixture = deployment();
        for (JsonNode pod : fixture.pods()) {
            ObjectNode container = object(pod.path("spec").path("containers").get(0));
            object(container.path("resources").path("limits")).put("cpu", "2000m").put("memory", "1Gi");
            object(pod.path("spec")).put("dnsPolicy", "ClusterFirst").put("schedulerName", "default-scheduler");
            object(pod.path("metadata").path("labels")).put("pod-template-hash", "generated");
        }
        fixture.verify();
    }

    @Test
    void oldObservedGenerationOrIncompleteRolloutsFail() {
        for (String field : List.of("observedGeneration", "replicas", "updatedReplicas", "readyReplicas", "availableReplicas")) {
            Deployment fixture = deployment();
            object(fixture.live().path("status")).put(field, 1);
            IOException failure = assertThrows(IOException.class, fixture::verify, field);
            assertTrue(failure.getMessage().contains("generation=3"));
        }
        Deployment fixture = deployment();
        object(fixture.live().path("status")).put("unavailableReplicas", 1);
        assertThrows(IOException.class, fixture::verify);
    }

    @Test
    void oldImagesConfigurationAndControllerOwnershipFail() {
        List<Consumer<Deployment>> changes = List.of(
                f -> object(f.live().path("spec").path("template").path("spec").path("containers").get(0)).put("image", IMAGE.replace('a', 'b')),
                f -> object(f.live().path("spec").path("template").path("metadata").path("annotations")).put("shardshop.javacraft/configuration", "old"),
                f -> object(f.replicaSet().path("metadata").path("annotations")).put("deployment.kubernetes.io/revision", "1"),
                f -> object(f.replicaSet().path("metadata").path("ownerReferences").get(0)).put("uid", "foreign-deployment"),
                f -> object(f.pods().getFirst().path("metadata").path("ownerReferences").get(0)).put("uid", "old-replicaset"),
                f -> object(f.pods().getFirst().path("spec").path("containers").get(0).path("env").get(0)).put("value", "old"),
                f -> object(f.pods().getFirst().path("status").path("containerStatuses").get(0)).put("imageID", "sha256:" + "b".repeat(64)),
                f -> object(f.pods().getFirst().path("status").path("conditions").get(0)).put("status", "False"));
        for (Consumer<Deployment> change : changes) {
            Deployment fixture = deployment();
            change.accept(fixture);
            assertThrows(IOException.class, fixture::verify);
        }
    }

    @Test
    void currentGenerationAndChecksumCannotHideAnOldMountedConfiguration() throws IOException {
        for (String stage : List.of("Deployment", "ReplicaSet", "Pod")) {
            Deployment fixture = deployment();
            fixture.verify();
            JsonNode spec = switch (stage) {
                case "Deployment" -> fixture.live().path("spec").path("template").path("spec");
                case "ReplicaSet" -> fixture.replicaSet().path("spec").path("template").path("spec");
                default -> fixture.pods().getFirst().path("spec");
            };
            object(spec.path("volumes").get(0).path("configMap")).put("name", "old-product-config");
            assertThrows(IOException.class, fixture::verify, stage + " must use the desired configuration volume");
        }
    }

    @Test
    void runtimeDigestAllowsTheKindsCriIdentityRepresentations() throws IOException {
        String digest = "sha256:" + "a".repeat(64);
        for (String identity : List.of(digest, "containerd://" + digest, IMAGE)) {
            Deployment fixture = deployment();
            object(fixture.pods().getFirst().path("status").path("containerStatuses").get(0)).put("imageID", identity);
            fixture.verify();
        }
    }

    private static final String ARCHIVE_DIGEST = "sha256:" + "b".repeat(64);

    private static ObjectNode index() {
        return json("""
                {"schemaVersion":2,"mediaType":"application/vnd.oci.image.index.v1+json","manifests":[
                {"mediaType":"application/vnd.oci.image.manifest.v1+json","digest":"DIGEST","size":1234}]}
                """.replace("DIGEST", "sha256:" + "a".repeat(64)));
    }

    @Test
    void nativeArchiveIndexResolvesToTheQualifiedPlatformManifest() throws Exception {
        Deployment fixture = deployment();
        ObjectNode pod = object(fixture.pods().getFirst());
        object(pod.path("spec")).put("nodeName", "unit-lab-worker");
        object(pod.path("status").path("containerStatuses").get(0)).put("imageID", "docker.io/library/import-2026-10-13@" + ARCHIVE_DIGEST);
        // The former direct matcher rejects this real CRI representation despite its exact platform identity.
        assertThrows(IOException.class, fixture::verify);
        KindLab lab = mock(KindLab.class);
        when(lab.clusterName()).thenReturn("unit-lab");
        when(lab.command("docker", "exec", "unit-lab-worker", "ctr", "--namespace=k8s.io", "content", "get", ARCHIVE_DIGEST))
                .thenReturn(index().toString());
        Map<String, JsonNode> indexes = new KindRelease(lab).runtimeIndexes(fixture.pods(), fixture.desired());
        KindRelease.verifyDeployment(fixture.desired(), fixture.live(), List.of(fixture.replicaSet()), fixture.pods(), indexes);
        verify(lab).command("docker", "exec", "unit-lab-worker", "ctr", "--namespace=k8s.io", "content", "get", ARCHIVE_DIGEST);
    }

    @Test
    void directRuntimeDigestsNeedNoNodeCommands() throws Exception {
        Deployment fixture = deployment();
        KindLab lab = mock(KindLab.class);
        assertTrue(new KindRelease(lab).runtimeIndexes(fixture.pods(), fixture.desired()).isEmpty());
        verifyNoInteractions(lab);
    }

    @Test
    void mismatchedMultipleAndMalformedArchiveIndexesCannotQualifyAnImage() {
        String digest = "sha256:" + "a".repeat(64);
        List<Consumer<ObjectNode>> changes = List.of(
                i -> object(i.path("manifests").get(0)).put("digest", ARCHIVE_DIGEST),
                i -> ((com.fasterxml.jackson.databind.node.ArrayNode) i.path("manifests")).add(i.path("manifests").get(0).deepCopy()),
                i -> object(i.path("manifests").get(0)).put("mediaType", "application/vnd.oci.image.index.v1+json"),
                i -> object(i.path("manifests").get(0)).put("size", -1),
                i -> object(i.path("manifests").get(0)).put("size", "1234"),
                i -> i.remove("mediaType"),
                i -> i.put("schemaVersion", 1),
                i -> i.putObject("manifests"));
        for (Consumer<ObjectNode> change : changes) {
            ObjectNode index = index();
            change.accept(index);
            assertFalse(KindRelease.indexReferences(index, digest));
            Deployment fixture = deployment();
            object(fixture.pods().getFirst().path("status").path("containerStatuses").get(0)).put("imageID", ARCHIVE_DIGEST);
            assertThrows(IOException.class, () -> KindRelease.verifyDeployment(fixture.desired(), fixture.live(), List.of(fixture.replicaSet()),
                    fixture.pods(), Map.of("product-1", index)));
        }
        assertFalse(KindRelease.indexReferences(null, digest));
        assertFalse(KindRelease.indexReferences(json("{}"), digest));
    }

    @Test
    void aSingleDockerManifestListCanIdentifyTheSameQualifiedPlatform() {
        ObjectNode index = index();
        index.put("mediaType", "application/vnd.docker.distribution.manifest.list.v2+json");
        object(index.path("manifests").get(0)).put("mediaType", "application/vnd.docker.distribution.manifest.v2+json");
        assertTrue(KindRelease.indexReferences(index, "sha256:" + "a".repeat(64)));
    }

    @Test
    void runtimeInspectionRejectsForeignNodesBeforeExecutingAnyCommand() throws Exception {
        for (String node : List.of("foreign-worker", "unit-lab-worker4", "unit-lab-worker;command", "", "unit-lab-worker/foreign")) {
            Deployment fixture = deployment();
            ObjectNode pod = object(fixture.pods().getFirst());
            object(pod.path("spec")).put("nodeName", node);
            object(pod.path("status").path("containerStatuses").get(0)).put("imageID", ARCHIVE_DIGEST);
            KindLab lab = mock(KindLab.class);
            when(lab.clusterName()).thenReturn("unit-lab");
            assertThrows(IOException.class, () -> new KindRelease(lab).runtimeIndexes(fixture.pods(), fixture.desired()));
            verify(lab, never()).command(any(String[].class));
        }
    }

    @Test
    void malformedNativeIndexDiagnosticsDoNotExposeItsContent() throws Exception {
        Deployment fixture = deployment();
        ObjectNode pod = object(fixture.pods().getFirst());
        object(pod.path("spec")).put("nodeName", "unit-lab-worker");
        object(pod.path("status").path("containerStatuses").get(0)).put("imageID", ARCHIVE_DIGEST);
        KindLab lab = mock(KindLab.class);
        when(lab.clusterName()).thenReturn("unit-lab");
        when(lab.command(any(String[].class))).thenReturn("{private-metadata");
        IOException failure = assertThrows(IOException.class, () -> new KindRelease(lab).runtimeIndexes(fixture.pods(), fixture.desired()));
        assertFalse(failure.getMessage().contains("private-metadata"));
        assertNull(failure.getCause());
    }

    @Test
    void matchingImageReferenceWithoutRuntimeIdentityCannotPass() {
        Deployment fixture = deployment();
        object(fixture.pods().getFirst().path("status").path("containerStatuses").get(0)).remove("imageID");
        assertThrows(IOException.class, fixture::verify);
    }

    @Test
    void failuresNameTheMismatchWithoutExposingConfigurationValues() {
        Deployment fixture = deployment();
        object(fixture.pods().getFirst().path("spec").path("containers").get(0).path("env").get(0)).put("value", "private-setting");
        IOException failure = assertThrows(IOException.class, fixture::verify);
        assertTrue(failure.getMessage().contains("product-1"));
        assertFalse(failure.getMessage().contains("private-setting"));
    }

    @Test
    void onlyTheCurrentRevisionSuccessfulOwnedSeederPodPasses() throws IOException {
        Deployment fixture = deployment();
        ObjectNode reader = json("{\"metadata\":{\"annotations\":{\"shardshop.javacraft/seeder-job\":\"shardshop-application-seeder-5\"}}}");
        ObjectNode job = fixture.desired().deepCopy();
        object(job.path("metadata")).put("name", "shardshop-application-seeder-5").put("uid", "job-uid");
        job.putObject("status").putArray("conditions").addObject().put("type", "Complete").put("status", "True");
        ObjectNode pod = pod(job, job, "seeder-5", "10.0.0.5");
        owner(pod, job, "Job");
        object(pod.path("status")).put("phase", "Succeeded");
        object(pod.path("status").path("containerStatuses").get(0)).putObject("state").putObject("terminated").put("exitCode", 0);
        KindRelease.currentSeeder(reader, job, fixture.desired(), 5, List.of(pod));
        object(pod.path("status").path("containerStatuses").get(0)).put("imageID", ARCHIVE_DIGEST);
        assertThrows(IOException.class, () -> KindRelease.currentSeeder(reader, job, fixture.desired(), 5, List.of(pod)));
        KindRelease.currentSeeder(reader, job, fixture.desired(), 5, List.of(pod), Map.of("seeder-5", index()));
        ObjectNode foreignIndex = index();
        object(foreignIndex.path("manifests").get(0)).put("digest", ARCHIVE_DIGEST);
        assertThrows(IOException.class, () -> KindRelease.currentSeeder(reader, job, fixture.desired(), 5, List.of(pod), Map.of("seeder-5", foreignIndex)));
        object(pod.path("status").path("containerStatuses").get(0)).put("imageID", IMAGE);
        assertThrows(IOException.class, () -> KindRelease.currentSeeder(reader, job, fixture.desired(), 6, List.of(pod)));
        object(pod.path("metadata").path("ownerReferences").get(0)).put("uid", "old-job-uid");
        assertThrows(IOException.class, () -> KindRelease.currentSeeder(reader, job, fixture.desired(), 5, List.of(pod)));
        owner(pod, job, "Job");
        object(pod.path("status").path("containerStatuses").get(0)).putObject("state").putObject("terminated").put("exitCode", 1);
        assertThrows(IOException.class, () -> KindRelease.currentSeeder(reader, job, fixture.desired(), 5, List.of(pod)));
        object(job.path("status").path("conditions").get(0)).put("status", "False");
        assertThrows(IOException.class, () -> KindRelease.currentSeeder(reader, job, fixture.desired(), 5, List.of(pod)));
    }

    private static ObjectNode service() { return service("product"); }

    private static ObjectNode service(String application) {
        return json("""
                {"kind":"Service","metadata":{"name":"shardshop-product"},"spec":{"selector":{"app.kubernetes.io/name":"shardshop-product"},"sessionAffinity":"None",
                "clusterIP":"10.96.0.1","ports":[{"name":"http","port":8080,"targetPort":"http","protocol":"TCP"}]}}
                """.replace("shardshop-product", "shardshop-" + application));
    }

    private static ObjectNode slice() {
        return json("""
                {"ports":[{"name":"http","port":8080,"protocol":"TCP"}],"endpoints":[
                {"conditions":{"ready":true},"addresses":["10.0.0.1"],
                "targetRef":{"kind":"Pod","namespace":"shardshop","name":"product-1","uid":"product-1-uid"}},
                {"conditions":{"ready":true},"addresses":["10.0.0.2"],
                "targetRef":{"kind":"Pod","namespace":"shardshop","name":"product-2","uid":"product-2-uid"}}]}
                """);
    }

    @Test
    void serviceRequiresBothIntendedReadyProductIdentities() throws IOException {
        Deployment fixture = deployment();
        KindRelease.service(service(), service(), List.of(slice()), fixture.pods());
        assertThrows(IOException.class, () -> KindRelease.service(service(), service(), List.of(), fixture.pods()));
        List<Consumer<ObjectNode>> changes = List.of(
                s -> object(s.path("endpoints").get(0).path("conditions")).put("ready", false),
                s -> object(s.path("endpoints").get(0).path("conditions")).put("terminating", true),
                s -> object(s.path("endpoints").get(0).path("targetRef")).put("uid", "old-product-uid"),
                s -> object(s.path("endpoints").get(0).path("targetRef")).put("namespace", "foreign"),
                s -> ((com.fasterxml.jackson.databind.node.ArrayNode) s.path("endpoints").get(0).path("addresses")).set(0, KindLab.parse("\"10.0.0.99\"")),
                s -> object(s.path("ports").get(0)).put("port", 9090));
        for (Consumer<ObjectNode> change : changes) {
            ObjectNode slice = slice();
            change.accept(slice);
            assertThrows(IOException.class, () -> KindRelease.service(service(), service(), List.of(slice), fixture.pods()));
        }
        ObjectNode wrongSelector = service();
        object(wrongSelector.path("spec")).putObject("selector").put("app.kubernetes.io/name", "foreign");
        assertThrows(IOException.class, () -> KindRelease.service(service(), wrongSelector, List.of(slice()), fixture.pods()));
        ObjectNode publishUnready = service();
        object(publishUnready.path("spec")).put("publishNotReadyAddresses", true);
        assertThrows(IOException.class, () -> KindRelease.service(service(), publishUnready, List.of(slice()), fixture.pods()));
    }

    private record ReleaseFixture(KindRelease observer, List<JsonNode> desired, Map<String, JsonNode> live) {
        void verify() throws IOException, InterruptedException { observer.observe(desired, 5); }
        JsonNode resource(String kind, String name) { return live.get("get " + kind + " " + name + " -o json"); }
    }

    private static ReleaseFixture release() throws IOException, InterruptedException {
        Map<String, JsonNode> live = new HashMap<>();
        List<JsonNode> desired = new ArrayList<>();
        for (String application : List.of("product", "product-reader", "order", "order-saga", "ledger", "order-producer",
                "product-seeder-control")) {
            Deployment deployment = deployment(application, List.of("product", "order").contains(application) ? 2 : 1);
            desired.add(deployment.desired());
            live.put("get deployment shardshop-" + application + " -o json", deployment.live());
            live.put("get pods -l app.kubernetes.io/name=shardshop-" + application + " -o json",
                    json("{\"items\":" + deployment.pods() + "}"));
            live.put("get replicasets -l app.kubernetes.io/name=shardshop-" + application + " -o json",
                    json("{\"items\":[" + deployment.replicaSet() + "]}"));
            if (List.of("product-reader", "order-producer").contains(application)) {
                object(deployment.live().path("metadata").path("annotations"))
                        .put("shardshop.javacraft/seeder-job", "shardshop-application-seeder-5");
            }
            if (List.of("product", "order", "ledger").contains(application)) {
                ObjectNode config = json("{\"kind\":\"ConfigMap\",\"metadata\":{},\"data\":{\"application.properties\":\"expected-setting=value\"}}");
                object(config.path("metadata")).put("name", "shardshop-" + application + "-config");
                desired.add(config);
                live.put("get configmap shardshop-" + application + "-config -o json", config.deepCopy());
            }
            ObjectNode service = service(application);
            desired.add(service);
            live.put("get service shardshop-" + application + " -o json", service.deepCopy());
            ObjectNode endpoints = slice();
            var entries = (com.fasterxml.jackson.databind.node.ArrayNode) endpoints.path("endpoints");
            entries.removeAll();
            for (JsonNode pod : deployment.pods()) {
                ObjectNode endpoint = entries.addObject();
                endpoint.putObject("conditions").put("ready", true);
                endpoint.putArray("addresses").add(pod.path("status").path("podIP").asText());
                endpoint.putObject("targetRef").put("kind", "Pod").put("namespace", "shardshop")
                        .put("name", KindDeployment.name(pod)).put("uid", pod.path("metadata").path("uid").asText());
            }
            live.put("get endpointslices -l kubernetes.io/service-name=shardshop-" + application + " -o json",
                    json("{\"items\":[" + endpoints + "]}"));
        }
        Deployment seeder = deployment("product-seeder", 1);
        object(seeder.desired().path("metadata")).put("name", "shardshop-application-seeder-1");
        seeder.desired().put("kind", "Job");
        desired.add(seeder.desired());
        ObjectNode job = seeder.desired().deepCopy();
        object(job.path("metadata")).put("name", "shardshop-application-seeder-5").put("uid", "current-job");
        job.putObject("status").putArray("conditions").addObject().put("type", "Complete").put("status", "True");
        ObjectNode pod = pod(job, job, "seeder-5", "10.0.0.5");
        owner(pod, job, "Job");
        object(pod.path("status")).put("phase", "Succeeded");
        object(pod.path("status").path("containerStatuses").get(0)).putObject("state").putObject("terminated").put("exitCode", 0);
        live.put("get job shardshop-application-seeder-5 -o json", job);
        live.put("get pods -l job-name=shardshop-application-seeder-5 -o json", json("{\"items\":[" + pod + "]}"));
        KindLab lab = mock(KindLab.class);
        when(lab.kubectl(any(String[].class))).thenAnswer(invocation -> {
            String command = Arrays.stream(invocation.getArguments()).map(String.class::cast).collect(Collectors.joining(" "));
            JsonNode response = live.get(command);
            if (response == null) throw new IOException("Unexpected release observation command");
            return response.toString();
        });
        return new ReleaseFixture(new KindRelease(lab), List.copyOf(desired), live);
    }

    @Test
    void releaseRequiresCurrentSagaDeploymentsAndRuntimeDigests() throws Exception {
        release().verify();
        for (String application : List.of("order", "ledger", "order-producer")) {
            ReleaseFixture oldGeneration = release();
            object(oldGeneration.resource("deployment", "shardshop-" + application).path("status")).put("observedGeneration", 2);
            assertThrows(IOException.class, oldGeneration::verify, application);
            ReleaseFixture wrongDigest = release();
            JsonNode pods = wrongDigest.live().get("get pods -l app.kubernetes.io/name=shardshop-" + application + " -o json");
            object(pods.path("items").get(0).path("status").path("containerStatuses").get(0)).remove("imageID");
            assertThrows(IOException.class, wrongDigest::verify, application);
            ReleaseFixture oldRestart = release();
            JsonNode deployment = oldRestart.resource("deployment", "shardshop-" + application);
            object(deployment.path("spec").path("template").path("metadata").path("annotations"))
                    .put("shardshop.javacraft/restart", "restart-previous");
            assertThrows(IOException.class, oldRestart::verify, application);
        }
    }

    @Test
    void releaseRequiresReadyEndpointsForBothMetricsServices() throws Exception {
        for (String application : List.of("order-saga", "ledger")) {
            ReleaseFixture fixture = release();
            JsonNode slices = fixture.live().get("get endpointslices -l kubernetes.io/service-name=shardshop-"
                    + application + " -o json");
            object(slices.path("items").get(0).path("endpoints").get(0).path("conditions")).put("ready", false);
            assertThrows(IOException.class, fixture::verify, application);
        }
    }

    @Test
    void releaseRejectsStaleServiceConfigurationWithoutExposingIt() throws Exception {
        for (String application : List.of("product", "order", "ledger")) {
            ReleaseFixture fixture = release();
            String name = "shardshop-" + application + "-config";
            object(fixture.resource("configmap", name).path("data")).put("application.properties", "private-setting");
            IOException failure = assertThrows(IOException.class, fixture::verify);
            assertTrue(failure.getMessage().contains(name));
            assertFalse(failure.getMessage().contains("private-setting"));
        }
    }

    @Test
    void releaseRequiresProducerToIdentifyTheCurrentSeederAndOrderEndpoint() throws Exception {
        ReleaseFixture oldSeeder = release();
        object(oldSeeder.resource("deployment", "shardshop-order-producer").path("metadata").path("annotations"))
                .put("shardshop.javacraft/seeder-job", "shardshop-application-seeder-4");
        assertThrows(IOException.class, oldSeeder::verify);
        ReleaseFixture missingOrderEndpoint = release();
        missingOrderEndpoint.live().put("get endpointslices -l kubernetes.io/service-name=shardshop-order -o json", json("{\"items\":[]}"));
        assertThrows(IOException.class, missingOrderEndpoint::verify);
        ReleaseFixture wrongOrderSelector = release();
        object(wrongOrderSelector.resource("service", "shardshop-order").path("spec").path("selector"))
                .put("app.kubernetes.io/name", "shardshop-product");
        assertThrows(IOException.class, wrongOrderSelector::verify);
    }

    @Test
    void releaseRequiresEachWorkloadControlServiceAndTheInteractiveSeeder() throws Exception {
        for (String application : List.of("product-reader", "order-producer", "product-seeder-control")) {
            ReleaseFixture missingEndpoint = release();
            missingEndpoint.live().put("get endpointslices -l kubernetes.io/service-name=shardshop-" + application
                    + " -o json", json("{\"items\":[]}"));
            assertThrows(IOException.class, missingEndpoint::verify, application);
            ReleaseFixture wrongSelector = release();
            object(wrongSelector.resource("service", "shardshop-" + application).path("spec").path("selector"))
                    .put("app.kubernetes.io/name", "shardshop-product-seeder");
            assertThrows(IOException.class, wrongSelector::verify, application);
        }
        ReleaseFixture oldSeederControl = release();
        object(oldSeederControl.resource("deployment", "shardshop-product-seeder-control").path("status"))
                .put("observedGeneration", 2);
        assertThrows(IOException.class, oldSeederControl::verify);
    }

}
