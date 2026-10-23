package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Acceptance of this invocation's application release, without changing any live resource. */
public final class KindRelease {
    private static final String RELEASE = "shardshop-application";
    private static final String PRODUCT = "shardshop-product";
    private static final String READER = "shardshop-product-reader";
    private static final String ORDER = "shardshop-order";
    private static final String PRODUCER = "shardshop-order-producer";
    private static final String SEEDER_CONTROL = "shardshop-product-seeder-control";
    private static final List<String> DEPLOYMENTS = List.of(PRODUCT, READER, ORDER, "shardshop-order-saga", "shardshop-ledger", PRODUCER,
            SEEDER_CONTROL);
    /** The two provider APIs and the three workload control pages (step 4.13). */
    private static final List<String> SERVICES = List.of(PRODUCT, ORDER, READER, PRODUCER, SEEDER_CONTROL);
    private static final String SEEDER_ANNOTATION = "shardshop.javacraft/seeder-job";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final KindLab lab;

    public KindRelease() { this(new KindLab()); }
    KindRelease(KindLab lab) { this.lab = lab; }

    public void accepted() throws IOException, InterruptedException {
        qualifiedImages(lab.json("images.json"));
        long revision = revision();
        List<JsonNode> desired = render();
        lab.await("the qualified application release, current pods and API Services", Duration.ofSeconds(180), () -> {
            observe(desired, revision);
            require(revision() == revision, "Application release revision changed during acceptance");
            return true;
        });
    }

    private long revision() throws IOException, InterruptedException {
        return deployedRevision(parse(lab.command("helm", "status", RELEASE, "--kube-context", "kind-" + lab.clusterName(),
                "--namespace", "shardshop", "--output", "json")));
    }

    static long deployedRevision(JsonNode release) throws IOException {
        require(release.path("name").asText().equals(RELEASE)
                && release.path("namespace").asText().equals("shardshop")
                && release.path("info").path("status").asText().equals("deployed")
                && release.path("version").isIntegralNumber() && release.path("version").canConvertToLong() && release.path("version").asLong() > 0,
                "The application Helm release must have a deployed current revision");
        return release.path("version").asLong();
    }

    private List<JsonNode> render() throws IOException, InterruptedException {
        String output = lab.command("helm", "template", RELEASE, lab.snapshot().resolve("chart").toString(),
                "--namespace", "shardshop", "--values", lab.snapshot().resolve("environment.yaml").toString(),
                "--values", lab.snapshot().resolve("inventory.yaml").toString(),
                "--values", lab.snapshot().resolve("images.json").toString(), "--set-string", "render=application-release");
        List<JsonNode> desired = new ArrayList<>();
        try {
            for (Object document : new Yaml(new SafeConstructor(new LoaderOptions())).loadAll(output)) {
                if (document != null) desired.add(JSON.valueToTree(document));
            }
        } catch (RuntimeException failure) {
            // Parser messages can quote configuration values; expose no manifest or parser cause.
            throw new IOException("Cannot parse the desired application Helm rendering");
        }
        return List.copyOf(desired);
    }

    void observe(List<JsonNode> desired, long revision) throws IOException, InterruptedException {
        Map<String, JsonNode> deployments = new HashMap<>();
        Map<String, List<JsonNode>> activePods = new HashMap<>();
        for (String name : DEPLOYMENTS) {
            JsonNode live = resource("deployment", name);
            deployments.put(name, live);
            activePods.put(name, deployment(named(desired, "Deployment", name), live));
        }
        for (String name : List.of("shardshop-product-config", "shardshop-order-config", "shardshop-ledger-config")) {
            require(named(desired, "ConfigMap", name).path("data").equals(resource("configmap", name).path("data")),
                    name + " differs from this invocation's desired configuration");
        }
        String jobName = RELEASE + "-seeder-" + revision;
        JsonNode desiredSeeder = named(desired, "Job", RELEASE + "-seeder-1");
        List<JsonNode> seeders = KindLab.items(resourceList("pods", "job-name=" + jobName));
        JsonNode job = resource("job", jobName);
        JsonNode reader = deployments.get(READER);
        JsonNode successful = currentSeederPod(reader, job, desiredSeeder, revision, seeders);
        currentSeeder(reader, job, desiredSeeder, revision, seeders, runtimeIndexes(List.of(successful), desiredSeeder));
        seederReference(deployments.get(PRODUCER), revision);
        for (String name : SERVICES) {
            service(named(desired, "Service", name), resource("service", name),
                    KindLab.items(resourceList("endpointslices", "kubernetes.io/service-name=" + name)), activePods.get(name));
        }
    }

    private List<JsonNode> deployment(JsonNode desired, JsonNode live) throws IOException, InterruptedException {
        String name = KindDeployment.name(desired);
        List<JsonNode> pods = KindLab.items(resourceList("pods", "app.kubernetes.io/name=" + name));
        List<JsonNode> replicaSets = KindLab.items(resourceList("replicasets", "app.kubernetes.io/name=" + name));
        List<JsonNode> active = currentPods(desired, live, replicaSets, pods);
        verifyDeployment(desired, live, replicaSets, pods, runtimeIndexes(active, desired));
        return active;
    }

    static void qualifiedImages(JsonNode snapshot) throws IOException {
        require(snapshot.path("restartToken").isTextual()
                        && snapshot.path("restartToken").textValue().matches("restart-[0-9a-f]{24}"),
                "Invocation images.json requires its application restart token");
        for (String application : List.of("product", "product-reader", "product-seeder", "order", "ledger", "order-producer")) {
            JsonNode image = snapshot.path("images").path(application);
            require(image.isTextual() && image.textValue().matches("[^\\s@]+@sha256:[0-9a-f]{64}"),
                    "Invocation images.json requires a qualified digest-pinned " + application + " image");
        }
    }

    static void verifyDeployment(JsonNode desired, JsonNode live, List<JsonNode> replicaSets, List<JsonNode> pods)
            throws IOException {
        verifyDeployment(desired, live, replicaSets, pods, Map.of());
    }

    static void verifyDeployment(JsonNode desired, JsonNode live, List<JsonNode> replicaSets, List<JsonNode> pods,
                                 Map<String, JsonNode> indexes) throws IOException {
        for (JsonNode pod : currentPods(desired, live, replicaSets, pods))
            imageIdentity(pod, desired.path("spec").path("template").path("spec").path("containers").get(0), indexes.get(KindDeployment.name(pod)));
    }

    private static List<JsonNode> currentPods(JsonNode desired, JsonNode live, List<JsonNode> replicaSets, List<JsonNode> pods)
            throws IOException {
        String name = KindDeployment.name(desired);
        JsonNode spec = live.path("spec");
        JsonNode status = live.path("status");
        int count = desired.path("spec").path("replicas").asInt();
        long generation = live.path("metadata").path("generation").asLong(-1);
        require(generation > 0 && status.path("observedGeneration").asLong(-1) == generation
                && spec.path("replicas").asInt(-1) == count
                && status.path("replicas").asInt(-1) == count
                && status.path("updatedReplicas").asInt(-1) == count
                && status.path("readyReplicas").asInt(-1) == count
                && status.path("availableReplicas").asInt(-1) == count
                && status.path("unavailableReplicas").asInt(0) == 0,
                name + " is not current: generation=" + generation + ", observed=" + status.path("observedGeneration").asLong(-1)
                        + ", replicas=" + status.path("replicas").asInt(0) + ", updated=" + status.path("updatedReplicas").asInt(0)
                        + ", ready=" + status.path("readyReplicas").asInt(0) + ", available=" + status.path("availableReplicas").asInt(0));
        require(spec.path("selector").equals(desired.path("spec").path("selector"))
                && templateMatches(desired.path("spec").path("template"), spec.path("template")),
                name + " Deployment differs from this invocation's desired template");
        String controllerRevision = live.path("metadata").path("annotations").path("deployment.kubernetes.io/revision").asText();
        require(!controllerRevision.isBlank(), name + " has no current controller revision");
        List<JsonNode> current = replicaSets.stream().filter(rs -> ownedBy(rs, "Deployment", live))
                .filter(rs -> rs.path("metadata").path("annotations").path("deployment.kubernetes.io/revision").asText().equals(controllerRevision))
                .filter(rs -> templateMatches(desired.path("spec").path("template"), rs.path("spec").path("template"))).toList();
        require(current.size() == 1, name + " lacks one current ReplicaSet for the desired template");
        List<JsonNode> active = pods.stream().filter(pod -> !pod.path("metadata").hasNonNull("deletionTimestamp")).toList();
        require(active.size() == count, name + " has an unexpected active pod count: " + active.size());
        for (JsonNode pod : active) {
            require(ownedBy(pod, "ReplicaSet", current.getFirst()) && KindDeployment.ready(pod)
                    && pod.path("status").path("phase").asText().equals("Running")
                    && templateMatches(desired.path("spec").path("template"), pod),
                    name + " has a stale, foreign or unready pod: " + KindDeployment.name(pod));
        }
        return active;
    }

    static void currentSeeder(JsonNode reader, JsonNode job, JsonNode desired, long revision, List<JsonNode> pods)
            throws IOException {
        currentSeeder(reader, job, desired, revision, pods, Map.of());
    }

    static void currentSeeder(JsonNode reader, JsonNode job, JsonNode desired, long revision, List<JsonNode> pods,
                              Map<String, JsonNode> indexes) throws IOException {
        JsonNode pod = currentSeederPod(reader, job, desired, revision, pods);
        imageIdentity(pod, desired.path("spec").path("template").path("spec").path("containers").get(0), indexes.get(KindDeployment.name(pod)));
    }

    private static JsonNode currentSeederPod(JsonNode reader, JsonNode job, JsonNode desired, long revision, List<JsonNode> pods)
            throws IOException {
        String name = RELEASE + "-seeder-" + revision;
        seederReference(reader, revision);
        require(KindDeployment.name(job).equals(name), "Seeder does not identify the current Helm revision");
        require(KindDeployment.completed(job) && templateMatches(desired.path("spec").path("template"), job.path("spec").path("template")),
                "Current revision seeder is incomplete or differs from the desired template: " + name);
        List<JsonNode> successful = pods.stream().filter(pod -> pod.path("status").path("phase").asText().equals("Succeeded")).toList();
        require(successful.size() == 1, "Current revision seeder needs one successful pod: " + name);
        JsonNode pod = successful.getFirst();
        require(ownedBy(pod, "Job", job) && !pod.path("metadata").hasNonNull("deletionTimestamp")
                && templateMatches(desired.path("spec").path("template"), pod), "Current seeder pod has stale or foreign ownership/template");
        JsonNode terminated = pod.path("status").path("containerStatuses").path(0).path("state").path("terminated");
        require(terminated.path("exitCode").asInt(-1) == 0, "Current seeder container did not exit successfully");
        return pod;
    }

    private static void seederReference(JsonNode workload, long revision) throws IOException {
        require(workload.path("metadata").path("annotations").path(SEEDER_ANNOTATION).asText()
                        .equals(RELEASE + "-seeder-" + revision),
                KindDeployment.name(workload) + " does not identify the current Helm revision seeder");
    }

    static void service(JsonNode desired, JsonNode live, List<JsonNode> slices, List<JsonNode> pods) throws IOException {
        String name = KindDeployment.name(desired);
        JsonNode spec = live.path("spec");
        require(spec.path("selector").equals(desired.path("spec").path("selector"))
                && spec.path("sessionAffinity").asText().equals("None") && !spec.path("publishNotReadyAddresses").asBoolean(false)
                && !spec.path("clusterIP").asText().isBlank() && !spec.path("clusterIP").asText().equals("None"),
                name + " Service does not select the intended ready pods");
        JsonNode port = spec.path("ports").path(0);
        require(spec.path("ports").size() == 1 && port.path("name").asText().equals("http")
                && port.path("port").asInt() == 8080 && port.path("targetPort").asText().equals("http")
                && port.path("protocol").asText("TCP").equals("TCP"), name + " Service HTTP port differs from the desired port");
        Set<String> expected = new HashSet<>();
        for (JsonNode pod : pods) {
            require(KindDeployment.ready(pod), name + " Service has an unready intended pod");
            expected.add(pod.path("metadata").path("uid").asText());
        }
        require(!pods.isEmpty() && expected.size() == pods.size() && !expected.contains(""), name + " Service requires distinct intended pod identities");
        Set<String> actual = new HashSet<>();
        for (JsonNode slice : slices) {
            require(!slice.path("metadata").hasNonNull("deletionTimestamp"), name + " EndpointSlice is terminating");
            JsonNode http = slice.path("ports").path(0);
            require(slice.path("ports").size() == 1 && http.path("name").asText().equals("http")
                    && http.path("port").asInt() == 8080 && http.path("protocol").asText("TCP").equals("TCP"),
                    name + " EndpointSlice does not expose the intended HTTP port");
            for (JsonNode endpoint : slice.path("endpoints")) {
                JsonNode target = endpoint.path("targetRef");
                String uid = target.path("uid").asText();
                require(endpoint.path("conditions").path("ready").asBoolean(false)
                        && !endpoint.path("conditions").path("terminating").asBoolean(false)
                        && target.path("kind").asText().equals("Pod") && target.path("namespace").asText().equals("shardshop")
                        && expected.contains(uid), name + " Service has an unready, terminating or foreign endpoint");
                JsonNode pod = pods.stream().filter(p -> p.path("metadata").path("uid").asText().equals(uid)).findFirst().orElseThrow();
                require(target.path("name").asText().equals(KindDeployment.name(pod)) && !endpoint.path("addresses").isEmpty(),
                        name + " Service endpoint does not identify its intended pod");
                Set<String> addresses = new HashSet<>();
                for (JsonNode address : pod.path("status").path("podIPs")) addresses.add(address.path("ip").asText());
                addresses.add(pod.path("status").path("podIP").asText());
                for (JsonNode address : endpoint.path("addresses"))
                    require(addresses.contains(address.asText()) && !address.asText().isBlank(), name + " Service endpoint address differs from its intended pod");
                actual.add(uid);
            }
        }
        require(actual.equals(expected), name + " Service does not expose all intended ready pod identities");
    }

    static boolean templateMatches(JsonNode desired, JsonNode live) {
        for (String section : List.of("labels", "annotations")) {
            for (var field : desired.path("metadata").path(section).properties())
                if (!live.path("metadata").path(section).path(field.getKey()).equals(field.getValue())) return false;
        }
        JsonNode wanted = desired.path("spec");
        JsonNode actual = live.path("spec");
        if (!actual.path("containers").isArray() || !wanted.path("containers").isArray()
                || actual.path("containers").size() != 1 || wanted.path("containers").size() != 1) return false;
        try {
            ObjectNode expected = normalizedPodSpec(wanted, false);
            ObjectNode observed = normalizedPodSpec(actual, live.has("status"));
            // Scheduling assigns nodeName to Pods, never to Deployment/ReplicaSet templates.
            if (live.has("status") && !wanted.has("nodeName")) observed.remove("nodeName");
            return expected.equals(observed);
        } catch (IllegalArgumentException | IOException invalidSpecification) {
            return false;
        }
    }

    /** Compare every setting, tolerating only known API defaults and the lab's standard Pod admission. */
    private static ObjectNode normalizedPodSpec(JsonNode spec, boolean pod) throws IOException {
        ObjectNode normalized = defaults(spec.deepCopy(), Map.of(
                "restartPolicy", "Always", "dnsPolicy", "ClusterFirst", "schedulerName", "default-scheduler",
                "terminationGracePeriodSeconds", 30, "serviceAccountName", "default", "enableServiceLinks", true,
                "securityContext", Map.of(), "priority", 0, "preemptionPolicy", "PreemptLowerPriority"));
        // serviceAccount is the API's deprecated alias, not another independently configurable account.
        if (normalized.path("serviceAccount").equals(normalized.path("serviceAccountName"))) normalized.remove("serviceAccount");
        ObjectNode container = defaults(normalized.path("containers").get(0), Map.of(
                "imagePullPolicy", "IfNotPresent", "terminationMessagePath", "/dev/termination-log",
                "terminationMessagePolicy", "File"));
        container.set("resources", JSON.valueToTree(KindReadiness.resources(container.path("resources"))));
        for (JsonNode port : container.path("ports")) defaults(port, Map.of("protocol", "TCP"));
        for (String name : List.of("readinessProbe", "livenessProbe", "startupProbe")) {
            if (container.has(name)) defaults(container.get(name), Map.of(
                    "initialDelaySeconds", 0, "timeoutSeconds", 1, "periodSeconds", 10,
                    "successThreshold", 1, "failureThreshold", 3));
        }
        for (JsonNode volume : normalized.path("volumes")) {
            for (String source : List.of("configMap", "secret", "projected", "downwardAPI")) {
                if (volume.has(source)) defaults(volume.get(source), Map.of("defaultMode", 420));
            }
            if (volume.path("emptyDir").has("sizeLimit")) {
                ((ObjectNode) volume.get("emptyDir")).put("sizeLimit",
                        KindReadiness.quantity(volume.path("emptyDir").path("sizeLimit").asText()));
            }
        }
        if (pod) {
            if (!normalized.path("automountServiceAccountToken").equals(JSON.getNodeFactory().booleanNode(false)))
                removeServiceAccountMount(normalized, container);
            removeAdmissionTolerations(normalized);
        }
        return normalized;
    }

    private static ObjectNode defaults(JsonNode node, Map<String, ?> values) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("Invalid pod specification object");
        ObjectNode object = (ObjectNode) node;
        values.forEach((name, value) -> object.putIfAbsent(name, JSON.valueToTree(value)));
        return object;
    }

    private static void removeServiceAccountMount(ObjectNode spec, ObjectNode container) throws IOException {
        if (!spec.path("volumes").isArray() || !container.path("volumeMounts").isArray()) return;
        ArrayNode volumes = (ArrayNode) spec.get("volumes");
        ArrayNode mounts = (ArrayNode) container.get("volumeMounts");
        ObjectNode expected = (ObjectNode) JSON.readTree("""
                {"projected":{"defaultMode":420,"sources":[
                  {"serviceAccountToken":{"expirationSeconds":3607,"path":"token"}},
                  {"configMap":{"name":"kube-root-ca.crt","items":[{"key":"ca.crt","path":"ca.crt"}]}},
                  {"downwardAPI":{"items":[{"fieldRef":{"apiVersion":"v1","fieldPath":"metadata.namespace"},"path":"namespace"}]}}
                ]}}
                """);
        for (int i = 0; i < volumes.size(); i++) {
            String name = volumes.get(i).path("name").asText();
            if (!name.matches("kube-api-access-[a-z0-9]{5}")) continue;
            expected.put("name", name);
            JsonNode mount = JSON.valueToTree(Map.of("name", name, "readOnly", true,
                    "mountPath", "/var/run/secrets/kubernetes.io/serviceaccount"));
            if (!volumes.get(i).equals(expected)) continue;
            for (int j = 0; j < mounts.size(); j++) {
                if (mounts.get(j).equals(mount)) {
                    volumes.remove(i);
                    mounts.remove(j);
                    return;
                }
            }
        }
    }

    private static void removeAdmissionTolerations(ObjectNode spec) {
        if (!spec.path("tolerations").isArray()) return;
        ArrayNode remaining = JSON.createArrayNode();
        for (JsonNode toleration : spec.get("tolerations")) {
            boolean admission = List.of("node.kubernetes.io/not-ready", "node.kubernetes.io/unreachable").stream()
                    .anyMatch(key -> toleration.equals(JSON.valueToTree(Map.of(
                            "key", key, "operator", "Exists", "effect", "NoExecute", "tolerationSeconds", 300))));
            if (!admission) remaining.add(toleration);
        }
        if (remaining.isEmpty()) spec.remove("tolerations");
        else spec.set("tolerations", remaining);
    }

    Map<String, JsonNode> runtimeIndexes(List<JsonNode> pods, JsonNode desired) throws IOException, InterruptedException {
        JsonNode intended = desired.path("spec").path("template").path("spec").path("containers").get(0);
        String qualified = imageDigest(intended.path("image").asText());
        Map<String, JsonNode> indexes = new HashMap<>();
        for (JsonNode pod : pods) {
            String actual = runtimeDigest(pod, intended);
            if (actual.equals(qualified)) continue;
            String node = pod.path("spec").path("nodeName").asText();
            validateKindNode(lab.clusterName(), node);
            // CRI may identify Docker's import archive index instead of its single platform manifest.
            indexes.put(KindDeployment.name(pod), parse(lab.command("docker", "exec", node, "ctr", "--namespace=k8s.io",
                    "content", "get", actual)));
        }
        return Map.copyOf(indexes);
    }

    static void validateKindNode(String cluster, String node) throws IOException {
        require(List.of("-control-plane", "-worker", "-worker2", "-worker3").stream().anyMatch(suffix -> node.equals(cluster + suffix)),
                "Runtime image inspection requires a node belonging to the selected kind lab");
    }

    private static String imageDigest(String image) {
        return image.substring(image.lastIndexOf("sha256:"));
    }

    private static String runtimeDigest(JsonNode pod, JsonNode intended) throws IOException {
        JsonNode statuses = pod.path("status").path("containerStatuses");
        require(statuses.size() == 1, "Pod lacks one application image identity: " + KindDeployment.name(pod));
        JsonNode status = statuses.get(0);
        String identity = status.path("imageID").asText();
        require(status.path("name").equals(intended.path("name"))
                && identity.matches("(?:[a-z]+://)?(?:[^@\\s]+@)?sha256:[0-9a-f]{64}"),
                "Pod lacks a valid runtime image digest: " + KindDeployment.name(pod));
        return imageDigest(identity);
    }

    private static void imageIdentity(JsonNode pod, JsonNode intended, JsonNode index) throws IOException {
        String digest = imageDigest(intended.path("image").asText());
        require(runtimeDigest(pod, intended).equals(digest) || indexReferences(index, digest),
                "Pod does not run its qualified image digest: " + KindDeployment.name(pod));
    }

    static boolean indexReferences(JsonNode index, String qualified) {
        if (index == null || !index.path("schemaVersion").isIntegralNumber() || index.path("schemaVersion").asInt() != 2
                || !List.of("application/vnd.oci.image.index.v1+json", "application/vnd.docker.distribution.manifest.list.v2+json")
                .contains(index.path("mediaType").asText()) || !index.path("manifests").isArray() || index.path("manifests").size() != 1) return false;
        JsonNode manifest = index.path("manifests").get(0);
        return List.of("application/vnd.oci.image.manifest.v1+json", "application/vnd.docker.distribution.manifest.v2+json")
                .contains(manifest.path("mediaType").asText()) && manifest.path("digest").asText().equals(qualified)
                && manifest.path("size").isIntegralNumber() && manifest.path("size").asLong() > 0;
    }

    private static boolean ownedBy(JsonNode child, String kind, JsonNode owner) {
        String uid = owner.path("metadata").path("uid").asText();
        if (uid.isBlank()) return false;
        for (JsonNode reference : child.path("metadata").path("ownerReferences"))
            if (reference.path("controller").asBoolean(false) && reference.path("kind").asText().equals(kind)
                    && reference.path("name").asText().equals(KindDeployment.name(owner)) && reference.path("uid").asText().equals(uid)) return true;
        return false;
    }

    private JsonNode resource(String kind, String name) throws IOException, InterruptedException {
        return parse(lab.kubectl("get", kind, name, "-o", "json"));
    }

    private JsonNode resourceList(String kind, String selector) throws IOException, InterruptedException {
        return parse(lab.kubectl("get", kind, "-l", selector, "-o", "json"));
    }

    private static JsonNode parse(String text) throws IOException {
        try {
            JsonNode result = JSON.readTree(text);
            if (result == null || !result.isObject()) throw new IOException();
            return result;
        }
        catch (IOException failure) { throw new IOException("Cannot parse application release metadata"); }
    }

    private static JsonNode named(List<JsonNode> resources, String kind, String name) throws IOException {
        List<JsonNode> found = resources.stream().filter(resource -> resource.path("kind").asText().equals(kind)
                && KindDeployment.name(resource).equals(name)).toList();
        require(found.size() == 1, "Desired application rendering lacks one " + kind + "/" + name);
        return found.getFirst();
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }
}
