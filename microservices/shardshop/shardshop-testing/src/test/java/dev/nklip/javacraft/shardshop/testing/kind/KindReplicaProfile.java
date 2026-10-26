package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.Json;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

/** A reversible strict-replica profile; the caller must hold the shared drill guard. */
final class KindReplicaProfile {
    private static final String PRODUCT = "shardshop-product";
    private static final String PROFILE = "shardshop.catalog.read-profile";
    private static final List<String> JVM_ENV = List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS",
            "JAVA_OPTS", "JAVA_OPTS_APPEND", "_JAVA_OPTIONS");
    private static final Pattern JVM_SETTING = Pattern.compile("-D[\"']?([^=\\s\"']+)=([^\\s]+)");
    private final KindLab lab;
    private final KindDeployment deployment;
    private final List<Saved> saved = new ArrayList<>();
    private boolean activated;

    KindReplicaProfile(KindLab lab) {
        this.lab = lab;
        this.deployment = new KindDeployment(lab);
    }

    void activate(KindDrillGuard guard) throws IOException, InterruptedException {
        if (activated) throw new IllegalStateException("Replica profile cannot be reused");
        activated = true;
        JsonNode product = get("configmap", "shardshop-product-config");
        JsonNode routing = get("configmap", "shardshop-routing");
        JsonNode workload = get("deployment", PRODUCT);
        String productProperties = KindDeployment.productConfiguration(product, "shardshop-product-config");
        String routingProperties = KindDeployment.productConfiguration(routing, "shardshop-routing");
        KindDeployment.primaryReads(workload.path("spec").path("template").path("spec"),
                productProperties, routingProperties);
        for (JsonNode pod : deployment.pods("app.kubernetes.io/name=" + PRODUCT)) {
            if (KindDeployment.ready(pod)) KindDeployment.primaryReads(pod.path("spec"), productProperties, routingProperties);
        }
        saved.add(config(product));
        saved.add(config(routing));
        JsonNode template = workload.path("spec").path("template");
        require(template.isObject(), "Product Deployment has no pod template");
        ObjectNode replica = template.deepCopy();
        replica.set("spec", convertSpec(template.path("spec"), "primary", "replica", true));
        saved.add(new Saved("deployment", PRODUCT, identity(workload), "/spec/template", template.deepCopy(), replica));
        evidence();
        // One action owns all snapshots: even a failed/uncertain first PATCH is recoverable.
        guard.onClose("restore exact primary product configuration and ready pods", () -> {
            restore();
            return true;
        });
        for (Saved resource : saved) patch(resource, false);
        awaitProfile(false);
    }

    private Saved config(JsonNode config) throws IOException {
        ObjectNode replica = config.path("data").deepCopy();
        replica.put("application.properties", convertProperties(
                KindDeployment.productConfiguration(config, KindDeployment.name(config)), "primary", "replica", true));
        return new Saved("configmap", KindDeployment.name(config), identity(config), "/data", config.path("data").deepCopy(), replica);
    }

    private void restore() throws IOException, InterruptedException {
        IOException failure = null;
        boolean interrupted = false;
        // Restore both ConfigMaps before starting the rollout with the original template.
        try {
            for (Saved resource : saved) {
                try { patch(resource, true); }
                catch (IOException | InterruptedException problem) {
                    if (problem instanceof InterruptedException) {
                        interrupted = true;
                        Thread.interrupted();
                    }
                    IOException diagnostic = new IOException("Cannot restore " + resource.name(), problem);
                    if (failure == null) failure = diagnostic;
                    else failure.addSuppressed(diagnostic);
                }
            }
            if (failure != null) throw failure;
            awaitProfile(true);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private void patch(Saved saved, boolean restoring) throws IOException, InterruptedException {
        JsonNode resource = get(saved.type(), saved.name());
        require(identity(resource).equals(saved.uid()), "Resource identity changed during replica drill: " + saved.name());
        JsonNode wanted = restoring ? saved.original() : saved.replica();
        JsonNode expected = restoring ? saved.replica() : saved.original();
        JsonNode current = resource.at(saved.path());
        if (current.equals(wanted)) return; // Includes mutations whose API response was lost.
        require(current.equals(expected), "Concurrent configuration edit prevents replica drill patch: " + saved.name());
        String version = resource.path("metadata").path("resourceVersion").asText();
        require(!version.isBlank(), "Resource has no resourceVersion: " + saved.name());
        ArrayNode patch = Json.newObject().putArray("patch");
        operation(patch, "test", "/metadata/uid", resource.path("metadata").path("uid"));
        operation(patch, "test", "/metadata/resourceVersion", resource.path("metadata").path("resourceVersion"));
        operation(patch, "test", saved.path(), expected);
        operation(patch, "replace", saved.path(), wanted);
        // A file avoids placing a possibly secret-bearing pod template in process arguments.
        Path file = privateFile("replica-profile-patch-", ".json");
        try {
            Files.writeString(file, patch.toString());
            lab.kubectl("patch", saved.type(), saved.name(), "--type=json", "--patch-file", file.toString());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static void operation(ArrayNode patch, String operation, String path, JsonNode value) {
        patch.addObject().put("op", operation).put("path", path).set("value", value);
    }

    private void awaitProfile(boolean primary) throws IOException, InterruptedException {
        lab.kubectl(Duration.ofSeconds(190), "rollout", "status", "deployment/" + PRODUCT, "--timeout=180s");
        lab.await((primary ? "restored primary" : "strict replica") + " product configuration on every ready pod",
                Duration.ofSeconds(180), () -> {
                    String product = KindDeployment.productConfiguration(get("configmap", "shardshop-product-config"), "shardshop-product-config");
                    String routing = KindDeployment.productConfiguration(get("configmap", "shardshop-routing"), "shardshop-routing");
                    JsonNode current = get("deployment", PRODUCT);
                    checkProfile(current.path("spec").path("template").path("spec"), primary, product, routing);
                    int replicas = current.path("spec").path("replicas").asInt();
                    require(replicas > 0 && current.path("status").path("readyReplicas").asInt() == replicas
                                    && current.path("status").path("updatedReplicas").asInt() == replicas
                                    && current.path("status").path("observedGeneration").asLong(-1)
                                    >= current.path("metadata").path("generation").asLong(),
                            "Product Deployment has not completed its profile rollout");
                    int ready = 0;
                    for (JsonNode pod : deployment.pods("app.kubernetes.io/name=" + PRODUCT)) {
                        if (!KindDeployment.ready(pod)) continue;
                        checkProfile(pod.path("spec"), primary, product, routing);
                        ready++;
                    }
                    require(ready == replicas, "Expected every product replica ready after profile rollout");
                    return true;
                });
    }

    static void checkProfile(JsonNode spec, boolean primary, String... configurations) throws IOException {
        if (primary) {
            KindDeployment.primaryReads(spec, configurations);
            return;
        }
        String[] normalized = new String[configurations.length];
        for (int index = 0; index < configurations.length; index++)
            normalized[index] = convertProperties(configurations[index], "replica", "primary", false);
        KindDeployment.primaryReads(convertSpec(spec, "replica", "primary", false), normalized);
    }

    static String convertProperties(String text, String from, String to, boolean add) throws IOException {
        Properties properties = new Properties();
        try { properties.load(new StringReader(text)); }
        catch (IllegalArgumentException invalid) { throw new IOException("Invalid product properties", invalid); }
        for (String key : properties.stringPropertyNames()) {
            if (profileKey(key)) {
                require(properties.getProperty(key).equals(from), "Product read profile must be literal " + from);
                properties.setProperty(key, to);
            }
        }
        if (add) properties.setProperty(PROFILE, to);
        StringWriter output = new StringWriter();
        properties.store(output, null);
        return output.toString();
    }

    static JsonNode convertSpec(JsonNode spec, String from, String to, boolean add) throws IOException {
        ObjectNode result = spec.deepCopy();
        for (JsonNode item : result.path("containers")) {
            if (!item.path("name").asText().equals("product")) continue;
            ObjectNode container = (ObjectNode) item;
            boolean profile = false;
            for (JsonNode setting : container.path("env")) {
                ObjectNode env = (ObjectNode) setting;
                String name = env.path("name").asText();
                if (profileEnv(name)) {
                    require(!env.has("valueFrom") && env.path("value").asText().equals(from),
                            "Product environment read profile must be literal " + from);
                    env.put("value", to);
                    profile = true;
                }
                if (JVM_ENV.contains(name)) env.put("value", convertJvm(env.path("value").asText(), from, to));
            }
            if (add && !profile) {
                ArrayNode env = container.has("env") ? (ArrayNode) container.get("env") : container.putArray("env");
                env.addObject().put("name", "SHARDSHOP_CATALOG_READ_PROFILE").put("value", to);
            }
            for (String field : List.of("command", "args")) {
                if (!(container.get(field) instanceof ArrayNode args)) continue;
                for (int index = 0; index < args.size(); index++) args.set(index,
                        Json.newObject().textNode(convertJvm(args.get(index).asText(), from, to)));
            }
        }
        return result;
    }

    private static String convertJvm(String text, String from, String to) throws IOException {
        var matcher = JVM_SETTING.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            if (!profileKey(matcher.group(1))) continue;
            require(matcher.group(2).equals(from), "JVM product read profile must be literal " + from);
            String setting = matcher.group();
            matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(
                    setting.substring(0, setting.length() - from.length()) + to));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static boolean profileEnv(String key) {
        return key.equals("SHARDSHOP_CATALOG_READ_PROFILE") || key.endsWith("_SHARDSHOP_CATALOG_READ_PROFILE") || profileKey(key);
    }

    private static boolean profileKey(String key) {
        return key.equals(PROFILE) || key.endsWith("." + PROFILE);
    }

    private JsonNode get(String type, String name) throws IOException, InterruptedException {
        return KindLab.parse(lab.kubectl("get", type, name, "-o", "json"));
    }

    private static String identity(JsonNode resource) throws IOException {
        String uid = resource.path("metadata").path("uid").asText();
        require(!uid.isBlank(), "Profile resource has no UID");
        return uid;
    }

    /** Persist identities and hashes only; env/JVM settings can contain credentials. */
    private void evidence() throws IOException {
        ArrayNode resources = Json.newObject().putArray("resources");
        for (Saved resource : saved) resources.addObject().put("type", resource.type()).put("name", resource.name())
                .put("uid", resource.uid()).put("field", resource.path()).put("originalSha256", sha256(resource.original()))
                .put("replicaSha256", sha256(resource.replica()));
        Path file = privateFile("replica-profile-evidence-", ".json");
        Files.writeString(file, resources.toPrettyString());
    }

    private Path privateFile(String prefix, String suffix) throws IOException {
        return Files.createTempFile(lab.snapshot(), prefix, suffix, PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rw-------")));
    }

    private static String sha256(JsonNode value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Json.bytes(value))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }

    private record Saved(String type, String name, String uid, String path, JsonNode original, JsonNode replica) { }
}
