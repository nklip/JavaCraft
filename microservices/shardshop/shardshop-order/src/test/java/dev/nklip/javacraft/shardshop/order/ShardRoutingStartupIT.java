package dev.nklip.javacraft.shardshop.order;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShardRoutingStartupIT {

    private static final String VALID_ROUTING = "shardshop.order.process=saga\n"
            + "shardshop.routing.shards=shard-b,shard-a\n"
            + "shardshop.routing.regions=EU,US\n";
    /** Normal starts enable CDC, which needs a replication password for each routing shard and a broker password. */
    private static final String VALID = VALID_ROUTING
            + "shardshop.order.cdc.connections.shard-a.password=cdc-secret-a\n"
            + "shardshop.order.cdc.connections.shard-b.password=cdc-secret-b\n"
            + "shardshop.order.cdc.broker.password=broker-secret\n";
    private static final String REGISTRY_UID = "3b439d70-a189-4a6e-8045-ab5b1fa140ac";

    @TempDir
    Path directory;

    @Test
    void startsWithAnExternalRoutingSnapshot() throws Exception {
        assertEquals(0, launch(VALID), output());
    }

    @Test
    void httpProcessStartsWithoutAnySagaCredentials() throws Exception {
        assertEquals(0, launch("shardshop.routing.shards=shard-a,shard-b\n"
                + "shardshop.routing.regions=US,EU\n"), output());
    }

    @Test
    void invalidProcessFailsBeforeGeneratorReservation() throws Exception {
        assertNotEquals(0, launch(VALID + "shardshop.order.process=combined\n", null, null,
                reservationWithoutCaFile()));
        assertTrue(output().contains("shardshop.order.process"), output());
        assertFalse(output().contains("Kubernetes API access is not configured correctly"), output());
    }

    @Test
    void failsWhenTheRoutingFileIsMissing() throws Exception {
        assertNotEquals(0, launch(null));
        assertTrue(output().contains(directory.resolve("external-routing-snapshot.properties").toUri().toString()), output());
    }

    @Test
    void failsWhenShardsAreMissing() throws Exception {
        assertNotEquals(0, launch("shardshop.routing.regions=US\n"));
        assertTrue(output().contains("shardshop.routing.shards"), output());
    }

    @Test
    void failsWhenRegionsAreMissing() throws Exception {
        assertNotEquals(0, launch("shardshop.routing.shards=shard-a\n"));
        assertTrue(output().contains("shardshop.routing.regions"), output());
    }

    @Test
    void failsWhenTheTopologyIsInvalid() throws Exception {
        assertNotEquals(0, launch("shardshop.routing.shards=shard-a,shard-a\nshardshop.routing.regions=US,EU\n"));
        assertTrue(output().contains("Shard topology must contain a nonempty list of distinct shards"), output());
    }

    @Test
    void startsWithTheHighestLiveGeneratorId() throws Exception {
        assertEquals(0, launch(VALID, "1023"), output());
    }

    @Test
    void failsWhenTheGeneratorIdIsMissing() throws Exception {
        assertNotEquals(0, launch(VALID, null));
        assertTrue(output().contains("shardshop.id.generator-id"), output());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "1024"})
    void failsWhenTheGeneratorIdIsOutsideTheLiveRange(String generatorId) throws Exception {
        assertNotEquals(0, launch(VALID, generatorId));
        assertTrue(output().contains("Live generator ID must be in 1..1023"), output());
    }

    @Test
    void failsWhenTheGeneratorIdIsMalformed() throws Exception {
        assertNotEquals(0, launch(VALID, "not-a-number"));
        assertTrue(output().contains("shardshop.id.generator-id"), output());
    }

    @ParameterizedTest
    @ValueSource(strings = {"7", "1023"})
    void startsWithAnEnvironmentGeneratorId(String generatorId) throws Exception {
        assertEquals(0, launch(VALID, null, generatorId), output());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "1024", "4294967297"})
    void failsWhenTheEnvironmentGeneratorIdIsOutsideTheLiveRange(String generatorId) throws Exception {
        assertNotEquals(0, launch(VALID, null, generatorId));
        assertTrue(output().contains("Live generator ID must be in 1..1023"), output());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1023.0", "not-a-number"})
    void failsWhenTheEnvironmentGeneratorIdIsEmptyOrMalformed(String generatorId) throws Exception {
        assertNotEquals(0, launch(VALID, null, generatorId));
        assertTrue(output().contains("shardshop.id.generator-id"), output());
    }

    @Test
    void rejectsAProfileEnvironmentGeneratorIdNextToTheRegistry() throws Exception {
        assertNotEquals(0, launch(VALID, null, null, Map.of("CONFIG_ORDINAL", "500",
                "_PROD_SHARDSHOP_ID_GENERATOR_ID", "2", "SHARDSHOP_ID_REGISTRY_UID", REGISTRY_UID)));
        assertTrue(output().contains("Set shardshop.id.registry.uid or shardshop.id.generator-id, not both"), output());
    }

    @Test
    void rejectsAnExternalFileGeneratorIdNextToTheRegistry() throws Exception {
        assertNotEquals(0, launch(VALID + "config_ordinal=500\nshardshop.id.generator-id=2\n", null, null,
                Map.of("SHARDSHOP_ID_REGISTRY_UID", REGISTRY_UID)));
        assertTrue(output().contains("Set shardshop.id.registry.uid or shardshop.id.generator-id, not both"), output());
    }

    @Test
    void rejectsAFixedGeneratorIdInAKubernetesPod() throws Exception {
        assertNotEquals(0, launch(VALID, "1", null, Map.of("KUBERNETES_SERVICE_HOST", "10.96.0.1")));
        assertTrue(output().contains("A Kubernetes pod must reserve its generator ID"), output());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-uid", "3B439D70-A189-4A6E-8045-AB5B1FA140AC"})
    void rejectsANonCanonicalRegistryUid(String uid) throws Exception {
        assertNotEquals(0, launch(VALID, null, null, Map.of("SHARDSHOP_ID_REGISTRY_UID", uid)));
        assertTrue(output().contains("A pinned generator registry UID is required"), output());
    }

    @Test
    void failsWithoutTheCaFileOfTheApiServerBeforeAnyRequest() throws Exception {
        assertNotEquals(0, launch(VALID, null, null, reservationWithoutCaFile()));
        assertTrue(output().contains("Kubernetes API access is not configured correctly"), output());
        assertFalse(output().contains("Reserved generator ID"), output());
    }

    @Test
    void checksTheRoutingSnapshotBeforeTheGeneratorReservation() throws Exception {
        assertNotEquals(0, launch("shardshop.routing.shards=shard-a,shard-a\nshardshop.routing.regions=US,EU\n", null,
                null, reservationWithoutCaFile()));
        assertTrue(output().contains("Shard topology must contain a nonempty list of distinct shards"), output());
        assertFalse(output().contains("Kubernetes API access is not configured correctly"), output());
    }

    /** A misconfigured pod restarts repeatedly; each start before these checks would burn one lifetime slot. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "shardshop.order.connections.shard-x.password=secret|Order connections must name only configured routing shards",
            "shardshop.order.max-pool-size=0|Order pool size must be between 1 and 32",
            "shardshop.order.max-id-candidates=0|Order ID candidate budget must be in 1..65536",
            "shardshop.order.catalog-concurrency=0|Catalog concurrency must be in 1..64",
            "shardshop.order.catalog-deadline=5s|Order catalog deadline must be between 2s and 3s",
            "shardshop.order.stock.poll-interval=1ms|Stock poll interval must be between 100ms and 30s",
            "shardshop.order.stock.lease=1s|Stock lease must be between 10s and 5m",
            "shardshop.order.stock.batch-size=0|Stock batch size must be between 1 and 100",
            "shardshop.order.profit.poll-interval=1ms|Profit poll interval must be between 100ms and 30s",
            "shardshop.order.profit.batch-size=0|Profit batch size must be between 1 and 100"
    })
    void checksTheOrderConfigurationBeforeTheGeneratorReservation(String property, String message) throws Exception {
        assertNotEquals(0, launch(VALID + property + "\n", null, null, reservationWithoutCaFile()));
        assertTrue(output().contains(message), output());
        assertFalse(output().contains("Kubernetes API access is not configured correctly"), output());
    }

    /** Enabled CDC without a credential fails before the reservation and prints no credential value. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "shardshop.order.cdc.connections.shard-a.password|CDC is enabled but shardshop.order.cdc.connections.shard-a.password is not set",
            "shardshop.order.cdc.broker.password|CDC is enabled but shardshop.order.cdc.broker.password is not set"
    })
    void checksTheCdcCredentialsBeforeTheGeneratorReservation(String missing, String message) throws Exception {
        String properties = VALID.lines().filter(line -> !line.startsWith(missing + "="))
                .collect(Collectors.joining("\n", "", "\n"));
        assertNotEquals(0, launch(properties, null, null, reservationWithoutCaFile()));
        assertTrue(output().contains(message), output());
        assertFalse(output().contains("cdc-secret") || output().contains("broker-secret"), output());
        assertFalse(output().contains("Kubernetes API access is not configured correctly"), output());
    }

    @Test
    void startsWithoutBrokerCredentialsWhenBothBrokerWorkersAreDisabled() throws Exception {
        assertEquals(0, launch(VALID_ROUTING + "shardshop.order.cdc.enabled=false\n"
                + "shardshop.order.results.enabled=false\n"), output());
    }

    @Test
    void resultsAloneRequireTheSharedBrokerCredentialBeforeGeneratorReservation() throws Exception {
        String properties = VALID_ROUTING + "shardshop.order.cdc.enabled=false\n";
        assertNotEquals(0, launch(properties, null, null, reservationWithoutCaFile()));
        assertTrue(output().contains("Result consumption is enabled but shardshop.order.cdc.broker.password is not set"),
                output());
        assertFalse(output().contains("Kubernetes API access is not configured correctly"), output());
        assertEquals(0, launch(properties + "shardshop.order.cdc.broker.password=broker-secret\n"), output());
    }

    /** A reservation stops at its missing CA file, before any request; an earlier failure never reaches it. */
    private Map<String, String> reservationWithoutCaFile() {
        return Map.of("SHARDSHOP_ID_REGISTRY_UID", REGISTRY_UID,
                "SHARDSHOP_ID_REGISTRY_CA_FILE", directory.resolve("missing-ca.crt").toString());
    }

    private int launch(String properties) throws Exception {
        return launch(properties, "1");
    }

    private int launch(String properties, String generatorId) throws Exception {
        return launch(properties, generatorId, null);
    }

    private int launch(String properties, String generatorId, String environmentGeneratorId) throws Exception {
        return launch(properties, generatorId, environmentGeneratorId, Map.of());
    }

    private int launch(String properties, String generatorId, String environmentGeneratorId,
                       Map<String, String> environment) throws Exception {
        Path config = directory.resolve("external-routing-snapshot.properties");
        if (properties != null) {
            assertEquals(config, Files.writeString(config, properties));
        }
        ProcessBuilder builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dshardshop.routing.config=" + config.toUri(),
                "-Dquarkus.profile=prod",
                "-Dquarkus.http.port=0",
                "-jar", Path.of("target", "quarkus-app", "quarkus-run.jar").toAbsolutePath().toString(), "--check-startup")
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(directory.resolve("startup.log").toFile());
        if (generatorId != null) {
            builder.command().add(1, "-Dshardshop.id.generator-id=" + generatorId);
        }
        // Local configuration and JVM environment options must not supply a missing setting.
        builder.environment().clear();
        builder.environment().putAll(environment);
        if (environmentGeneratorId != null) {
            assertNull(builder.environment().put("SHARDSHOP_ID_GENERATOR_ID", environmentGeneratorId));
        }
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Startup exceeded 30 seconds: " + output());
            return process.exitValue();
        } finally {
            assertTrue(process.destroyForcibly().waitFor(5, TimeUnit.SECONDS), "Process did not terminate");
        }
    }

    private String output() throws IOException {
        return Files.readString(directory.resolve("startup.log"));
    }
}
