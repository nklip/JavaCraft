package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** The manual reset credential belongs only to the operator, outside application releases. */
class DatabaseRoleChartTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> SHARDS = List.of("shard-a", "shard-b", "shard-c");
    @TempDir Path temporary;

    @Test
    void reconcilerHasOneRestrictedLoginAndNoApplicationCredentialMount() throws Exception {
        List<JsonNode> infrastructure = render("infrastructure");
        assertEquals(46, infrastructure.stream().filter(resource -> "DatabaseRole".equals(resource.path("kind").asText())).count());
        assertTrue(infrastructure.stream().noneMatch(resource -> "Secret".equals(resource.path("kind").asText())),
                "cluster-create.sh must provision the password outside Helm");
        for (String shard : SHARDS) {
            JsonNode group = role(infrastructure, shard + "-ordering-reconciler").path("spec");
            assertEquals("ordering_reconciler", group.path("name").asText());
            assertFalse(group.path("login").asBoolean());
            assertTrue(group.path("disablePassword").asBoolean());
            assertEquals(Set.of("cluster", "name", "login", "disablePassword", "databaseRoleReclaimPolicy"), fields(group));

            JsonNode operator = role(infrastructure, shard + "-ordering-operator").path("spec");
            assertEquals("ordering_operator", operator.path("name").asText());
            assertTrue(operator.path("login").asBoolean());
            assertTrue(operator.path("inherit").asBoolean());
            assertEquals(JSON.valueToTree(List.of("ordering_reconciler")), operator.path("inRoles"));
            assertEquals("ordering-operator", operator.path("passwordSecret").path("name").asText());
            assertEquals(Set.of("cluster", "name", "login", "inherit", "inRoles", "passwordSecret",
                    "databaseRoleReclaimPolicy"), fields(operator),
                    "Operator must have no replication, ownership, superuser or role-creation attributes");
        }
        assertFalse(render("applications").toString().contains("ordering-operator"),
                "No application pod may reference the manual operator credential");
    }

    private List<JsonNode> render(String mode) throws Exception {
        Path source = Path.of(System.getProperty("shardshop.directory", "..")).toAbsolutePath().normalize();
        Path chart = source.resolve("infra/helm/shardshop");
        String digest = "0".repeat(64);
        Map<String, String> images = Map.of(
                "product", "docker.io/library/shardshop-product@sha256:" + digest,
                "product-seeder", "docker.io/library/shardshop-product-seeder@sha256:" + digest,
                "product-reader", "docker.io/library/shardshop-product-reader@sha256:" + digest,
                "order", "docker.io/library/shardshop-order@sha256:" + digest,
                "ledger", "docker.io/library/shardshop-ledger@sha256:" + digest,
                "order-producer", "docker.io/library/shardshop-order-producer@sha256:" + digest);
        Path overrides = temporary.resolve("images.json");
        Files.writeString(overrides, JSON.writeValueAsString(Map.of("images", images)));
        Path output = temporary.resolve(mode + ".yaml");
        Process process = new ProcessBuilder("helm", "template", "shardshop", chart.toString(), "--namespace", "shardshop",
                "--values", source.resolve("infra/shards.yaml").toString(), "--values", chart.resolve("kind-values.yaml").toString(),
                "--values", overrides.toString(), "--set-string", "render=" + mode)
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        process.getOutputStream().close();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Helm rendering exceeded its deadline");
            assertEquals(0, process.exitValue(), Files.readString(output));
            return KindSafety.infrastructureManifest(Files.readString(output));
        } finally {
            process.destroyForcibly();
        }
    }

    private static JsonNode role(List<JsonNode> resources, String name) {
        return resources.stream().filter(resource -> "DatabaseRole".equals(resource.path("kind").asText()))
                .filter(resource -> name.equals(resource.path("metadata").path("name").asText())).findFirst().orElseThrow();
    }

    private static Set<String> fields(JsonNode node) {
        return node.properties().stream().map(Map.Entry::getKey).collect(Collectors.toSet());
    }
}
