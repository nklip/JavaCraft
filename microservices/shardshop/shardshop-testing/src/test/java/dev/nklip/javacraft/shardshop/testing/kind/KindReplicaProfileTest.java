package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class KindReplicaProfileTest {
    @TempDir Path snapshot;
    private KindLab lab;
    private final Map<String, ObjectNode> resources = new LinkedHashMap<>();
    private final List<String> events = new ArrayList<>();
    private int patches;
    private int failAfterPatch;
    private boolean lock;
    private boolean primaryPodFailure;

    @BeforeEach
    void api() throws Exception {
        lab = mock(KindLab.class);
        when(lab.snapshot()).thenReturn(snapshot);
        resources.put("shardshop-product-config", config("shardshop-product-config", "# exact comment\nshardshop.catalog.read-profile=primary\nsecret=never-print-me\n"));
        resources.put("shardshop-routing", config("shardshop-routing", "%prod.shardshop.catalog.read-profile=primary\nshards=a,b\n"));
        resources.put("shardshop-product", (ObjectNode) KindLab.parse("""
                {"metadata":{"name":"shardshop-product","uid":"deployment-uid","resourceVersion":"1","generation":1},
                "spec":{"replicas":2,"template":{"metadata":{"annotations":{"retained":"exact"}},"spec":{"containers":[
                {"name":"product","env":[{"name":"SHARDSHOP_CATALOG_READ_PROFILE","value":"primary"},
                {"name":"JAVA_TOOL_OPTIONS","value":"-Xmx128m -Dshardshop.catalog.read-profile=primary -Dunrelated=preserve"},
                {"name":"PASSWORD","value":"never-print-me"}],
                "command":["java","-D%prod.shardshop.catalog.read-profile=primary"],
                "args":["-Dshardshop.catalog.read-profile=primary","-jar","app.jar"]}]}}},
                "status":{"readyReplicas":2,"updatedReplicas":2,"observedGeneration":1}}
                """));
        when(lab.kubectl(any(String[].class))).thenAnswer(invocation -> command((String[]) invocation.getRawArguments()[0]));
        when(lab.kubectl(any(Duration.class), any(String[].class))).thenAnswer(invocation -> {
            String[] args = (String[]) invocation.getRawArguments()[1];
            if (args[0].equals("delete")) {
                events.add("unlock");
                lock = false;
            } else {
                assertEquals(List.of("rollout", "status", "deployment/shardshop-product", "--timeout=180s"), List.of(args));
                events.add("rollout");
            }
            return "";
        });
        doAnswer(invocation -> {
            assertTrue(((KindLab.Probe) invocation.getArgument(2)).check());
            events.add("await");
            return null;
        }).when(lab).await(any(String.class), any(Duration.class), any(KindLab.Probe.class));
    }

    private String command(String[] args) throws Exception {
        switch (args[0]) {
            case "create" -> { lock = true; return "created"; }
            case "get" -> {
                if (args[1].equals("pods")) return pods();
                return resources.get(args[2]).toString();
            }
            case "patch" -> {
                assertTrue(lock, "The guard must own the drill before a configuration mutation");
                JsonNode patch = KindLab.parse(Files.readString(Path.of(args[5])));
                ObjectNode current = resources.get(args[2]);
                for (JsonNode operation : patch) {
                    String path = operation.path("path").asText();
                    if (operation.path("op").asText().equals("test")) {
                        assertEquals(current.at(path), operation.path("value"), "Conditional patch must match observed state");
                    } else {
                        String parent = path.substring(0, path.lastIndexOf('/'));
                        ((ObjectNode) current.at(parent)).set(path.substring(path.lastIndexOf('/') + 1), operation.path("value").deepCopy());
                    }
                }
                assertEquals(List.of("/metadata/uid", "/metadata/resourceVersion"),
                        List.of(patch.get(0).path("path").asText(), patch.get(1).path("path").asText()));
                ((ObjectNode) current.path("metadata")).put("resourceVersion", Integer.toString(++patches + 1));
                events.add("patch:" + args[2]);
                if (patches == failAfterPatch) throw new IOException("Lost patch response");
                return "patched";
            }
            default -> throw new AssertionError(List.of(args));
        }
    }

    private String pods() {
        ObjectNode root = (ObjectNode) KindLab.parse("{\"items\":[]}");
        for (int index = 0; index < 2; index++) {
            ObjectNode pod = root.withArray("items").addObject();
            pod.putObject("metadata").put("name", "product-" + index);
            pod.set("spec", resources.get("shardshop-product").path("spec").path("template").path("spec").deepCopy());
            pod.putObject("status").putArray("conditions").addObject().put("type", "Ready").put("status", "True");
            if (primaryPodFailure && patches >= 6 && index == 1)
                ((ObjectNode) pod.path("spec").path("containers").get(0).path("env").get(0)).put("value", "replica");
        }
        return root.toString();
    }

    @Test
    void switchesEverySourceAndRestoresExactSnapshotsBeforeWorkloadsAndUnlock() throws Exception {
        Map<String, JsonNode> before = snapshots();
        try (var guard = new KindDrillGuard(lab).acquire()) {
            guard.onClose("restore workload", () -> { events.add("workload"); return true; });
            new KindReplicaProfile(lab).activate(guard);
            JsonNode template = resources.get("shardshop-product").path("spec").path("template");
            KindReplicaProfile.checkProfile(template.path("spec"), false, properties("shardshop-product-config"), properties("shardshop-routing"));
            assertEquals("-D%prod.shardshop.catalog.read-profile=replica", template.path("spec").path("containers").get(0).path("command").get(1).asText());
            assertEquals("-Xmx128m -Dshardshop.catalog.read-profile=replica -Dunrelated=preserve", template.path("spec").path("containers").get(0).path("env").get(1).path("value").asText());
        }
        assertEquals(before, snapshots(), "Comments, formatting, env, arguments and annotations must restore exactly");
        assertEquals(List.of("patch:shardshop-product-config", "patch:shardshop-routing", "patch:shardshop-product", "rollout", "await",
                "patch:shardshop-product-config", "patch:shardshop-routing", "patch:shardshop-product", "rollout", "await", "workload", "unlock"), events);
        try (var files = Files.list(snapshot)) {
            List<Path> evidence = files.toList();
            assertEquals(1, evidence.size(), "Patch files must be removed");
            assertFalse(Files.readString(evidence.getFirst()).contains("never-print-me"));
            assertTrue(Files.readString(evidence.getFirst()).contains("originalSha256"));
        }
    }

    @Test
    void lostMutationResponseRestoresBeforeGuardReleases() throws Exception {
        Map<String, JsonNode> before = snapshots();
        failAfterPatch = 1;
        assertThrows(IOException.class, () -> {
            try (var guard = new KindDrillGuard(lab).acquire()) { new KindReplicaProfile(lab).activate(guard); }
        });
        assertEquals(before, snapshots());
        assertFalse(lock);
        assertEquals("unlock", events.getLast());
    }

    @Test
    void failedDrillAfterCompleteSwitchStillRestoresAllSources() throws Exception {
        Map<String, JsonNode> before = snapshots();
        assertThrows(AssertionError.class, () -> {
            try (var guard = new KindDrillGuard(lab).acquire()) {
                new KindReplicaProfile(lab).activate(guard);
                throw new AssertionError("strict read assertion failed");
            }
        });
        assertEquals(before, snapshots());
        assertFalse(lock);
    }

    @Test
    void concurrentTemplateEditIsPreservedAndRetainsGuard() throws Exception {
        var guard = new KindDrillGuard(lab).acquire();
        new KindReplicaProfile(lab).activate(guard);
        ObjectNode template = (ObjectNode) resources.get("shardshop-product").path("spec").path("template");
        ((ObjectNode) template.path("metadata").path("annotations")).put("concurrent", "preserve");
        assertThrows(IOException.class, guard::close);
        assertEquals("preserve", template.path("metadata").path("annotations").path("concurrent").asText());
        assertTrue(lock);
    }

    @Test
    void replacementResourceCannotBeOverwrittenDuringRestore() throws Exception {
        var guard = new KindDrillGuard(lab).acquire();
        new KindReplicaProfile(lab).activate(guard);
        ((ObjectNode) resources.get("shardshop-routing").path("metadata")).put("uid", "new-resource");
        assertThrows(IOException.class, guard::close);
        assertEquals("new-resource", resources.get("shardshop-routing").path("metadata").path("uid").asText());
        assertTrue(lock);
    }

    @Test
    void readyPodThatRetainsReplicaOverridePreventsUnlockAfterRestoration() throws Exception {
        var guard = new KindDrillGuard(lab).acquire();
        new KindReplicaProfile(lab).activate(guard);
        primaryPodFailure = true;
        assertThrows(IOException.class, guard::close);
        assertTrue(lock);
    }

    @Test
    void externalAndIndirectConfigurationRejectsBeforeAnyMutation() throws Exception {
        for (String env : List.of("{\"name\":\"JAVA_OPTS\",\"valueFrom\":{\"secretKeyRef\":{\"name\":\"external\",\"key\":\"options\"}}}",
                "{\"name\":\"QUARKUS_CONFIG_LOCATIONS\",\"value\":\"elsewhere.properties\"}")) {
            ObjectNode container = (ObjectNode) resources.get("shardshop-product").path("spec").path("template").path("spec").path("containers").get(0);
            var original = container.path("env").deepCopy();
            container.withArray("env").add(KindLab.parse(env));
            try (var guard = new KindDrillGuard(lab).acquire()) {
                assertThrows(IOException.class, () -> new KindReplicaProfile(lab).activate(guard));
                assertEquals(0, patches);
            }
            container.set("env", original);
        }
    }

    @Test
    void profileAbsentFromTemplateGetsExplicitReplicaAndScopedEnvIsConverted() throws Exception {
        JsonNode spec = KindLab.parse("{\"containers\":[{\"name\":\"product\"}]}");
        JsonNode converted = KindReplicaProfile.convertSpec(spec, "primary", "replica", true);
        assertEquals("replica", converted.path("containers").get(0).path("env").get(0).path("value").asText());
        JsonNode scoped = KindLab.parse("{\"containers\":[{\"name\":\"product\",\"env\":[{\"name\":\"_PROD_SHARDSHOP_CATALOG_READ_PROFILE\",\"value\":\"primary\"}]}]}");
        JsonNode result = KindReplicaProfile.convertSpec(scoped, "primary", "replica", true);
        assertEquals(1, result.path("containers").get(0).path("env").size());
        assertEquals("replica", result.path("containers").get(0).path("env").get(0).path("value").asText());
    }

    @Test
    void convertsAllJvmEnvironmentSourcesAndProfileScopedProperties() throws Exception {
        ObjectNode spec = (ObjectNode) KindLab.parse("{\"containers\":[{\"name\":\"product\",\"env\":[]}]}");
        var env = ((ObjectNode) spec.path("containers").get(0)).withArray("env");
        for (String name : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "JAVA_OPTS", "JAVA_OPTS_APPEND", "_JAVA_OPTIONS"))
            env.addObject().put("name", name).put("value", "-D%prod.shardshop.catalog.read-profile=primary -Dkeep=value");
        JsonNode converted = KindReplicaProfile.convertSpec(spec, "primary", "replica", true);
        for (int index = 0; index < 5; index++)
            assertEquals("-D%prod.shardshop.catalog.read-profile=replica -Dkeep=value",
                    converted.path("containers").get(0).path("env").get(index).path("value").asText());
        String properties = KindReplicaProfile.convertProperties(
                "shardshop.catalog.read-profile=primary\n%prod.shardshop.catalog.read-profile=primary\nunrelated=retained\n",
                "primary", "replica", true);
        KindReplicaProfile.checkProfile(converted, false, properties);
        assertThrows(IOException.class, () -> KindReplicaProfile.checkProfile(spec, false, properties),
                "An overlooked primary override must fail strict replica validation");
    }

    private static ObjectNode config(String name, String properties) {
        ObjectNode config = (ObjectNode) KindLab.parse("{}");
        config.putObject("metadata").put("name", name).put("uid", name + "-uid").put("resourceVersion", "1");
        config.putObject("data").put("application.properties", properties).put("other-key", "exact");
        return config;
    }

    private String properties(String name) { return resources.get(name).path("data").path("application.properties").asText(); }

    private Map<String, JsonNode> snapshots() {
        Map<String, JsonNode> state = new LinkedHashMap<>();
        resources.forEach((name, resource) -> state.put(name, resource.at(name.equals("shardshop-product") ? "/spec/template" : "/data").deepCopy()));
        return state;
    }
}
