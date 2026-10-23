package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class KindWorkloadPolicyTest {
    private static ObjectNode spec() {
        return (ObjectNode) KindLab.parse("""
                {"automountServiceAccountToken":false,"containers":[{"name":"reader","env":[
                {"name":"SHARDSHOP_READER_PRODUCT_URL","value":"http://shardshop-product.shardshop.svc:8080"}]}],
                "volumes":[{"name":"tmp","emptyDir":{"sizeLimit":"64Mi"}}]}
                """);
    }

    private static ObjectNode container(ObjectNode spec, String group) {
        return group.equals("containers") ? (ObjectNode) spec.path(group).get(0)
                : spec.putArray(group).addObject().put("name", "helper");
    }

    @Test
    void rejectsJvmAndGenericConfigurationCredentialInjection() {
        for (String group : new String[]{"containers", "initContainers", "ephemeralContainers"}) {
            for (String name : new String[]{"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS",
                    "QUARKUS_CONFIG_LOCATIONS", "PGCONNINFO", "OTHER_SETTING"}) {
                ObjectNode invalid = spec();
                container(invalid, group).putArray("env").addObject().put("name", name)
                        .put("value", "-Dquarkus.datasource.password=credential");
                assertThrows(IOException.class, () -> KindDeployment.httpOnly(invalid), group + "/" + name);
            }
        }
    }

    @Test
    void rejectsCredentialAndDatabaseValuesUnderAnAllowedEnvironmentName() {
        for (String value : new String[]{"jdbc:postgresql://db/shardshop?password=credential",
                "http://product:credential@shardshop-product:8080", "http://shardshop-product:8080?password=credential",
                "http://shardshop-product:8080#credential", "${DATABASE_URL}"}) {
            ObjectNode invalid = spec();
            ((ObjectNode) invalid.path("containers").get(0).path("env").get(0)).put("value", value);
            assertThrows(IOException.class, () -> KindDeployment.httpOnly(invalid));
        }
    }

    @Test
    void producerRunNameRejectsEveryDollarFormThatSmallRyeConfigRewrites() {
        assertDoesNotThrow(() -> KindDeployment.httpOnly(producer("kind-saga-v1")));
        for (String runName : new String[]{"kind-${x}", "kind-${x:y}", "kind-$${x}", "kind-$$x", "kind-\\$x"}) {
            assertThrows(IOException.class, () -> KindDeployment.httpOnly(producer(runName)), runName);
        }
    }

    private static ObjectNode producer(String runName) {
        ObjectNode workload = spec();
        ((ObjectNode) workload.path("containers").get(0).path("env").get(0))
                .put("name", "SHARDSHOP_PRODUCER_RUN_NAME").put("value", runName);
        return workload;
    }

    @Test
    void rejectsCommandAndArgumentConfigurationOverridesInEveryContainerGroup() {
        for (String group : new String[]{"containers", "initContainers", "ephemeralContainers"}) {
            for (String field : new String[]{"command", "args"}) {
                ObjectNode invalid = spec();
                container(invalid, group).putArray(field).add("-Dquarkus.datasource.password=credential");
                assertThrows(IOException.class, () -> KindDeployment.httpOnly(invalid), group + "/" + field);
            }
        }
    }

    @Test
    void allowsTheChartWorkloadsAndDocumentedHttpConfiguration() {
        for (Map<String, String> settings : java.util.List.of(Map.ofEntries(
                Map.entry("SHARDSHOP_READER_PRODUCT_URL", "https://shardshop-product:8080/base"),
                Map.entry("SHARDSHOP_READER_CATALOG_VERSION", "catalog-v2"),
                Map.entry("SHARDSHOP_READER_REQUEST_RATE", "400"),
                Map.entry("SHARDSHOP_READER_WORKERS", "32"),
                Map.entry("SHARDSHOP_READER_CONNECTIONS", "32"),
                Map.entry("SHARDSHOP_READER_MAX_ATTEMPTS", "3"),
                Map.entry("SHARDSHOP_READER_CONNECT_TIMEOUT", "2s"),
                Map.entry("SHARDSHOP_READER_REQUEST_DEADLINE", "PT5S"),
                Map.entry("SHARDSHOP_READER_RETRY_INTERVAL", "0ms"),
                Map.entry("SHARDSHOP_READER_MAX_CONNECTION_LIFETIME", "30s"),
                Map.entry("SHARDSHOP_READER_IDLE_TIMEOUT", "10"),
                Map.entry("SHARDSHOP_READER_REPORT_INTERVAL", "PT1M30S")), Map.of(
                "SHARDSHOP_SEEDER_PRODUCT_URL", "http://shardshop-product.shardshop.svc:8080",
                "SHARDSHOP_SEEDER_CATALOG_VERSION", "catalog-v2",
                "SHARDSHOP_SEEDER_CONNECT_TIMEOUT", "2s",
                "SHARDSHOP_SEEDER_REQUEST_TIMEOUT", "5s",
                "SHARDSHOP_SEEDER_MAX_ATTEMPTS", "5",
                "SHARDSHOP_SEEDER_RETRY_BACKOFF", "500ms",
                "SHARDSHOP_SEEDER_MAX_RETRY_BACKOFF", "5s",
                "SHARDSHOP_SEEDER_REQUEST_RATE", "12.5",
                "SHARDSHOP_SEEDER_INTERACTIVE", "true"), Map.of(
                "SHARDSHOP_PRODUCER_ORDER_RATE", "0.5",
                "SHARDSHOP_PRODUCER_SUBMISSION_CONCURRENCY", "4",
                "SHARDSHOP_PRODUCER_OBSERVER_CONCURRENCY", "2",
                "SHARDSHOP_PRODUCER_MAX_NON_TERMINAL_ORDERS", "100",
                "SHARDSHOP_PRODUCER_POLL_INTERVAL", "250ms"))) {
            ObjectNode valid = spec();
            ObjectNode workload = (ObjectNode) valid.path("containers").get(0);
            ArrayNode env = workload.putArray("env");
            settings.forEach((name, value) -> env.addObject().put("name", name).put("value", value));
            workload.putArray("args");
            workload.putArray("command");
            assertDoesNotThrow(() -> KindDeployment.httpOnly(valid));
        }
    }

    @Test
    void rejectsRateAndModeValuesOutsideTheirLiteralForms() {
        for (String[] setting : new String[][]{{"SHARDSHOP_PRODUCER_ORDER_RATE", "-1"}, {"SHARDSHOP_PRODUCER_ORDER_RATE", "1e3"},
                {"SHARDSHOP_PRODUCER_ORDER_RATE", "0.0001"}, {"SHARDSHOP_SEEDER_REQUEST_RATE", "${RATE}"},
                {"SHARDSHOP_SEEDER_REQUEST_RATE", "01"}, {"SHARDSHOP_SEEDER_INTERACTIVE", "false"},
                {"SHARDSHOP_SEEDER_INTERACTIVE", "${MODE}"}, {"SHARDSHOP_PRODUCER_ORDER_INTERVAL", "1s"}}) {
            ObjectNode invalid = spec();
            ((ArrayNode) invalid.path("containers").get(0).path("env")).addObject()
                    .put("name", setting[0]).put("value", setting[1]);
            assertThrows(IOException.class, () -> KindDeployment.httpOnly(invalid), String.join("=", setting));
        }
    }

    @Test
    void rejectsConnectionsAndExpressionsDisguisedAsOtherHttpSettings() {
        for (String name : new String[]{"SHARDSHOP_READER_REQUEST_RATE", "SHARDSHOP_READER_CONNECT_TIMEOUT",
                "SHARDSHOP_SEEDER_CATALOG_VERSION"}) {
            for (String value : new String[]{"jdbc:postgresql://db/shardshop?password=credential",
                    "${PRODUCT_APP_PASSWORD}", "quarkus.datasource.password=credential"}) {
                ObjectNode invalid = spec();
                ((ArrayNode) invalid.path("containers").get(0).path("env")).addObject()
                        .put("name", name).put("value", value);
                assertThrows(IOException.class, () -> KindDeployment.httpOnly(invalid));
            }
        }
    }

    @Test
    void rejectsLifecycleCommandsThatBypassTheImageEntrypoint() {
        for (String group : new String[]{"containers", "initContainers", "ephemeralContainers"}) {
            ObjectNode invalid = spec();
            container(invalid, group).putObject("lifecycle").putObject("postStart").putObject("exec")
                    .putArray("command").add("psql").add("postgresql://user:credential@db/shardshop");
            assertThrows(IOException.class, () -> KindDeployment.httpOnly(invalid));
        }
    }

    @Test
    void rejectsExecutableProbesWhileAllowingHttpAndTcpProbes() {
        for (String field : new String[]{"startupProbe", "readinessProbe", "livenessProbe"}) {
            ObjectNode invalid = spec();
            container(invalid, "containers").putObject(field).putObject("exec").putArray("command")
                    .add("psql").add("postgresql://user:credential@db/shardshop");
            assertThrows(IOException.class, () -> KindDeployment.httpOnly(invalid));
        }
        ObjectNode valid = spec();
        ObjectNode workload = container(valid, "containers");
        workload.putObject("readinessProbe").putObject("httpGet").put("path", "/ready").put("port", 8080);
        workload.putObject("livenessProbe").putObject("tcpSocket").put("port", 8080);
        assertDoesNotThrow(() -> KindDeployment.httpOnly(valid));
    }

    @Test
    void urlValidationDoesNotIncludeCredentialInputInDiagnostics() {
        ObjectNode invalid = spec();
        ((ObjectNode) invalid.path("containers").get(0).path("env").get(0))
                .put("value", "http://user:credential@invalid host");
        IOException failure = assertThrows(IOException.class, () -> KindDeployment.httpOnly(invalid));
        assertFalse(failure.getMessage().contains("credential@"));
        assertNull(failure.getCause());
    }
}
