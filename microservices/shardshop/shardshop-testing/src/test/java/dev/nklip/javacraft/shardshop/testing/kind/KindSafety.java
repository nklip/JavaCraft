package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.Json;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Read-only retained-lab guards; admission requests always use server dry-run. */
public final class KindSafety {
    private static final String INFRASTRUCTURE_RELEASE = "shardshop-infrastructure";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REGISTRY = "shardshop-snowflake-generators";
    private static final String IDENTITY = "shardshop-generator-identity";
    private static final String POLICY = "shardshop-generator-retention";
    private static final Pattern NODE_IMAGE = Pattern.compile("docker.io/kindest/node@sha256:[0-9a-f]{64}");
    private static final Pattern UUID = Pattern.compile("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    private static final Pattern HIGH_WATER = Pattern.compile("0|[1-9][0-9]{0,3}");
    private static final Pattern POSTGRES_MAJOR = Pattern.compile(":([0-9]+)\\.");
    private static final String CONFIG_RECOVERY = "Unsupported kind creation configuration. Keep one control plane, "
            + "three workers and loopback networking; node/network/mount/port changes need an explicitly reviewed "
            + "recreation, never an in-place reset.";
    private static final String MISSING_RECOVERY = "Generator registry is missing or partial; startup must remain stopped. "
            + "Recover partial or lost state from the latest independent allocator backup; never reset it.";
    private static final String MALFORMED_RECOVERY = "Generator registry is malformed or its identity changed; refusing "
            + "startup. Recover the latest independent allocator backup; never reset the counter. See README generator recovery.";

    private final KindLab lab;

    public KindSafety() {
        this(new KindLab());
    }

    public KindSafety(KindLab lab) {
        this.lab = lab;
    }

    public void config() throws IOException {
        validateConfiguration(lab.json("kind.json"), lab.clusterName());
        require("3".equals(lab.inventory().path("instances").asText()),
                "The local quorum lab requires three instances per shard.");
    }

    public void nodes() throws IOException, InterruptedException {
        String names = lab.command("kind", "get", "nodes", "--name", lab.clusterName()).strip();
        require(!names.isEmpty(), "Existing kind nodes differ from infra/kind.yaml; restore its creation configuration "
                + "or explicitly recreate the lab after retiring retained data.");
        List<String> inspect = new ArrayList<>(List.of("docker", "inspect"));
        inspect.addAll(List.of(names.split("\\s+")));
        validateNodes(lab.json("kind.json"), KindLab.parse(lab.command(inspect.toArray(String[]::new))), lab.clusterName());
    }

    public void retention() throws IOException, InterruptedException {
        for (String snapshot : List.of("shardshop-routing", "shardshop-migration-topology")) {
            validateTopology(lab.inventory(), KindLab.parse(lab.kubectl("get", "configmap", snapshot,
                    "--ignore-not-found", "-o", "json")), snapshot);
        }
        validateGeneratorState(generatorState(), lab.fresh() ? 0 : 2);
        if (lab.fresh()) {
            require(lab.kubectl("get", "namespace", "shardshop", "--ignore-not-found", "-o", "name").isBlank(),
                    "New cluster unexpectedly contains ShardShop state; refusing initialization.");
        } else {
            String desired = lab.kubectl("create", "--dry-run=client", "--validate=false", "-f",
                    lab.snapshot().resolve("infrastructure.yaml").toString(), "-o", "json");
            List<JsonNode> resources = Json.documents(desired).stream().flatMap(node -> KindLab.items(node).stream()).toList();
            List<JsonNode> current = KindLab.items(KindLab.parse(lab.kubectl("get", "clusters.postgresql.cnpg.io",
                    "-o", "json")));
            validateInfrastructure(resources, current, helmInfrastructure(resources, current));
        }
    }

    private List<JsonNode> helmInfrastructure(List<JsonNode> desired, List<JsonNode> current) throws IOException, InterruptedException {
        Set<String> names = new HashSet<>();
        desired.stream().filter(resource -> "Cluster".equals(text(resource.path("kind"))))
                .forEach(resource -> names.add(text(resource.path("metadata").path("name"))));
        if (current.stream().filter(resource -> names.contains(text(resource.path("metadata").path("name"))))
                .noneMatch(KindSafety::hasHelmOwnership)) return List.of();
        long revision = infrastructureRevision();
        String manifest = lab.command("helm", "get", "manifest", INFRASTRUCTURE_RELEASE,
                "--kube-context", "kind-" + lab.clusterName(), "--namespace", "shardshop",
                "--revision", Long.toString(revision));
        List<JsonNode> previous = infrastructureManifest(manifest);
        require(infrastructureRevision() == revision, "Infrastructure Helm revision changed during the retention check");
        return previous;
    }

    private long infrastructureRevision() throws IOException, InterruptedException {
        String status = lab.command("helm", "status", INFRASTRUCTURE_RELEASE,
                "--kube-context", "kind-" + lab.clusterName(), "--namespace", "shardshop", "--output", "json");
        JsonNode release;
        try {
            release = JSON.readTree(status);
        } catch (IOException failure) {
            throw new IOException("Cannot parse infrastructure Helm release metadata");
        }
        require(release != null && INFRASTRUCTURE_RELEASE.equals(release.path("name").asText())
                        && "shardshop".equals(release.path("namespace").asText())
                        && Set.of("deployed", "failed").contains(release.path("info").path("status").asText())
                        && release.path("version").isIntegralNumber() && release.path("version").canConvertToLong()
                        && release.path("version").asLong() > 0,
                "Infrastructure Helm release must have a completed current revision; inspect it before retrying startup");
        return release.path("version").asLong();
    }

    static List<JsonNode> infrastructureManifest(String manifest) throws IOException {
        List<JsonNode> resources = new ArrayList<>();
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try {
            for (Object document : new Yaml(new SafeConstructor(options)).loadAll(manifest)) {
                if (document != null) resources.add(JSON.valueToTree(document));
            }
        } catch (RuntimeException failure) {
            throw new IOException("Cannot parse the retained infrastructure Helm manifest");
        }
        return List.copyOf(resources);
    }

    public void bootstrapAdmission() throws IOException, InterruptedException {
        require(lab.fresh(), "Generator initialization is allowed only for a cluster created by this invocation.");
        validateGeneratorState(generatorState(), 0);
        denied("create", "configmap", REGISTRY, "--from-literal=highWaterMark=-1", "--dry-run=server");
        denied("create", "configmap", IDENTITY, "--from-literal=registryUid=invalid", "--dry-run=server");
    }

    public void allocator() throws IOException, InterruptedException {
        GeneratorState state = validateGeneratorState(generatorState(), 2);
        denied("patch", "configmap", REGISTRY, "--type=merge", "-p",
                "{\"data\":{\"highWaterMark\":\"" + state.highWaterMark() + "\"}}", "--dry-run=server");
        for (String name : List.of(REGISTRY, IDENTITY)) {
            denied("delete", "configmap", name, "--dry-run=server");
            denied("delete", "--raw", "/api/v1/namespaces/shardshop/configmaps?dryRun=All&fieldSelector=metadata.name%3D" + name);
        }
        denied("patch", "configmap", IDENTITY, "--type=merge", "-p",
                "{\"metadata\":{\"annotations\":{\"shardshop.javacraft/guard-check\":\"true\"}}}", "--dry-run=server");
        System.out.println("Generator registry verified: UID " + state.uid() + ", high-water " + state.highWaterMark() + "/1023.");
    }

    private List<JsonNode> generatorState() throws IOException, InterruptedException {
        return KindLab.items(KindLab.parse(lab.kubectl("get", "configmap", REGISTRY, IDENTITY,
                "--ignore-not-found", "-o", "json")));
    }

    private void denied(String... arguments) throws IOException, InterruptedException {
        String[] output = {"No admission response received."};
        try {
            lab.await("the generator retention policy to deny " + String.join(" ", arguments), Duration.ofSeconds(30), () -> {
                var result = lab.kubectlAttempt(Duration.ofSeconds(10), arguments);
                output[0] = result.stdout() + result.stderr();
                return admissionDenied(result.exitCode(), output[0]);
            });
        } catch (IOException failure) {
            throw new IOException("Could not verify the generator retention policy; refusing to continue.\n" + output[0], failure);
        }
    }

    static boolean admissionDenied(int exitCode, String output) {
        return exitCode != 0 && output.contains(POLICY) && output.contains("denied request");
    }

    static void validateConfiguration(JsonNode config, String clusterName) {
        require(fields(config).equals(Set.of("kind", "apiVersion", "name", "networking", "nodes"))
                        && "Cluster".equals(text(config.path("kind")))
                        && "kind.x-k8s.io/v1alpha4".equals(text(config.path("apiVersion")))
                        && clusterName.equals(text(config.path("name")))
                        && config.path("networking").equals(Json.object("{\"apiServerAddress\":\"127.0.0.1\"}"))
                        && config.path("nodes").isArray() && config.path("nodes").size() == 4,
                CONFIG_RECOVERY);
        List<String> roles = List.of("control-plane", "worker", "worker", "worker");
        for (int i = 0; i < roles.size(); i++) {
            JsonNode node = config.path("nodes").get(i);
            require(fields(node).equals(Set.of("role", "image")) && roles.get(i).equals(text(node.path("role")))
                    && NODE_IMAGE.matcher(text(node.path("image"))).matches(), CONFIG_RECOVERY);
        }
    }

    static void validateNodes(JsonNode configuration, JsonNode actual, String clusterName) {
        List<String> suffixes = List.of("-control-plane", "-worker", "-worker2", "-worker3");
        Map<String, JsonNode> expected = new HashMap<>();
        for (int i = 0; i < suffixes.size(); i++) {
            expected.put(clusterName + suffixes.get(i), configuration.path("nodes").get(i));
        }
        Set<String> names = new HashSet<>();
        actual.forEach(node -> names.add(text(node.path("Name")).replaceFirst("^/+", "")));
        require(actual.isArray() && actual.size() == expected.size() && names.equals(expected.keySet()),
                "Existing kind nodes differ from infra/kind.yaml; restore its creation configuration or explicitly "
                        + "recreate the lab after retiring retained data.");
        for (JsonNode node : actual) {
            String name = text(node.path("Name")).replaceFirst("^/+", "");
            JsonNode desired = expected.get(name);
            JsonNode labels = node.path("Config").path("Labels");
            JsonNode ports = node.path("HostConfig").path("PortBindings");
            Set<String> expectedPorts = "control-plane".equals(text(desired.path("role"))) ? Set.of("6443/tcp") : Set.of();
            boolean valid = clusterName.equals(text(labels.path("io.x-k8s.kind.cluster")))
                    && desired.path("role").equals(labels.path("io.x-k8s.kind.role"))
                    && desired.path("image").equals(node.path("Config").path("Image")) && fields(ports).equals(expectedPorts);
            for (JsonNode bindings : ports) {
                for (JsonNode binding : bindings) {
                    valid &= "127.0.0.1".equals(text(binding.path("HostIp")));
                }
            }
            require(valid, name + " has incompatible role, image or ports. Node changes require explicit cluster recreation; "
                    + "up.sh never recreates retained nodes.");
        }
    }

    static void validateTopology(JsonNode inventory, JsonNode published, String snapshot) {
        if (published.isEmpty()) {
            return;
        }
        require(inventory.path("version").equals(published.path("data").path("version"))
                        && inventory.path("regionVersion").equals(published.path("data").path("regionVersion")),
                "Shard membership/order or regions differ from " + snapshot + ". Online resharding is not implemented; "
                        + "restore the retained inventory or follow README's explicit reset/reseed procedure.");
    }

    static GeneratorState validateGeneratorState(List<JsonNode> state, int expectedCount) {
        require(state.size() == expectedCount, MISSING_RECOVERY);
        if (state.isEmpty()) {
            return null;
        }
        Map<String, JsonNode> objects = new HashMap<>();
        state.forEach(node -> objects.put(text(node.path("metadata").path("name")), node));
        JsonNode registry = objects.getOrDefault(REGISTRY, Json.newObject());
        JsonNode identity = objects.getOrDefault(IDENTITY, Json.newObject());
        String uid = text(registry.path("metadata").path("uid"));
        String highWater = text(registry.path("data").path("highWaterMark"));
        require(UUID.matcher(uid).matches() && HIGH_WATER.matcher(highWater).matches()
                        && Integer.parseInt(highWater) <= 1023 && registry.path("data").size() == 1
                        && !truthy(registry.path("binaryData")) && !truthy(registry.path("immutable"))
                        && identity.path("immutable").isBoolean() && identity.path("immutable").booleanValue()
                        && !truthy(identity.path("binaryData"))
                        && identity.path("data").equals(Json.newObject().put("registryUid", uid))
                        && !truthy(registry.path("metadata").path("deletionTimestamp"))
                        && !truthy(identity.path("metadata").path("deletionTimestamp")), MALFORMED_RECOVERY);
        return new GeneratorState(uid, highWater);
    }

    static void validateInfrastructure(List<JsonNode> desired, List<JsonNode> current) {
        validateInfrastructure(desired, current, List.of());
    }

    static void validateInfrastructure(List<JsonNode> desired, List<JsonNode> current, List<JsonNode> previous) {
        Map<String, JsonNode> stored = new HashMap<>();
        for (JsonNode resource : previous) {
            if (!"Cluster".equals(text(resource.path("kind")))) continue;
            String name = text(resource.path("metadata").path("name"));
            require("postgresql.cnpg.io/v1".equals(text(resource.path("apiVersion")))
                            && "shardshop".equals(text(resource.path("metadata").path("namespace")))
                            && !name.isEmpty() && resource.path("spec").isObject()
                            && stored.putIfAbsent(name, resource) == null,
                    "Retained infrastructure Helm manifest has invalid or duplicate database Cluster identities");
        }
        Map<String, JsonNode> live = new HashMap<>();
        current.forEach(node -> live.put(text(node.path("metadata").path("name")), node));
        for (JsonNode resource : desired) {
            String name = text(resource.path("metadata").path("name"));
            if ("Cluster".equals(text(resource.path("kind"))) && live.containsKey(name)) {
                JsonNode retained = live.get(name);
                JsonNode baseline = null;
                if (hasHelmOwnership(retained)) {
                    JsonNode metadata = retained.path("metadata");
                    require("postgresql.cnpg.io/v1".equals(text(retained.path("apiVersion")))
                                    && "shardshop".equals(text(metadata.path("namespace")))
                                    && "Helm".equals(text(metadata.path("labels").path("app.kubernetes.io/managed-by")))
                                    && INFRASTRUCTURE_RELEASE.equals(text(metadata.path("annotations").path("meta.helm.sh/release-name")))
                                    && "shardshop".equals(text(metadata.path("annotations").path("meta.helm.sh/release-namespace")))
                                    && stored.containsKey(name),
                            "Database Cluster " + name + " does not match the retained infrastructure Helm ownership/manifest");
                    baseline = stored.get(name);
                }
                require(compatibleInfrastructure(resource, retained, baseline), "Unsafe infrastructure change for " + name
                        + ": storage, placement, bootstrap, instance count or PostgreSQL major version differs. "
                        + "Restore the retained configuration; up.sh does not migrate or reset database storage.");
            }
        }
    }

    private static boolean hasHelmOwnership(JsonNode resource) {
        JsonNode metadata = resource.path("metadata");
        return "Helm".equals(text(metadata.path("labels").path("app.kubernetes.io/managed-by")))
                || metadata.path("annotations").has("meta.helm.sh/release-name")
                || metadata.path("annotations").has("meta.helm.sh/release-namespace");
    }

    static boolean compatibleInfrastructure(JsonNode desired, JsonNode current) {
        return compatibleInfrastructure(desired, current, null);
    }

    static boolean compatibleInfrastructure(JsonNode desired, JsonNode current, JsonNode declared) {
        JsonNode spec = desired.path("spec");
        JsonNode live = current.path("spec");
        String annotation = text(current.path("metadata").path("annotations").path("kubectl.kubernetes.io/last-applied-configuration"));
        // Helm's stored manifest is the declaration after ownership transfer, even if kubectl's annotation remains.
        JsonNode previous = declared != null ? declared.path("spec")
                : annotation.isEmpty() ? live : KindLab.parse(annotation).path("spec");
        if (previous.isMissingNode()) {
            previous = live;
        }
        boolean compatible = containsValues(live.path("storage"), spec.path("storage"))
                && live.path("storage").path("storageClass").equals(spec.path("storage").path("storageClass"))
                && live.path("instances").equals(spec.path("instances"))
                && containsValues(live.path("bootstrap").path("initdb"), spec.path("bootstrap").path("initdb"))
                && containsValues(live.path("affinity"), spec.path("affinity"));
        for (String key : List.of("storage", "affinity", "bootstrap")) {
            compatible &= objectOrEmpty(previous.path(key)).equals(objectOrEmpty(spec.path(key)));
        }
        Matcher oldMajor = POSTGRES_MAJOR.matcher(text(live.path("imageName")));
        Matcher newMajor = POSTGRES_MAJOR.matcher(text(spec.path("imageName")));
        return compatible && oldMajor.find() && newMajor.find() && oldMajor.group(1).equals(newMajor.group(1));
    }

    private static boolean containsValues(JsonNode live, JsonNode desired) {
        for (String key : fields(desired)) {
            if (!live.path(key).equals(desired.path(key))) {
                return false;
            }
        }
        return true;
    }

    private static JsonNode objectOrEmpty(JsonNode node) {
        return node.isMissingNode() ? Json.newObject() : node;
    }

    private static Set<String> fields(JsonNode node) {
        Set<String> fields = new HashSet<>();
        node.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    private static String text(JsonNode node) {
        return node.isTextual() ? node.textValue() : "";
    }

    private static boolean truthy(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return false;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isContainerNode()) {
            return !node.isEmpty();
        }
        return node.isTextual() ? !node.textValue().isEmpty() : node.asDouble() != 0;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    record GeneratorState(String uid, String highWaterMark) {
    }
}
