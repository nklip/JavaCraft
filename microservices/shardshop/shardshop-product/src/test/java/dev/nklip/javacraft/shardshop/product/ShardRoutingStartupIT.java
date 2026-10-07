package dev.nklip.javacraft.shardshop.product;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShardRoutingStartupIT {

    private static final String VALID_ROUTING = "shardshop.routing.shards=shard-b,shard-a\n"
            + "shardshop.routing.regions=EU,US\n";

    @TempDir
    Path directory;

    @Test
    void startsWithAnExternalRoutingSnapshot() throws Exception {
        assertEquals(0, launch(VALID_ROUTING), output());
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
        assertEquals(0, launch(VALID_ROUTING, "1023"), output());
    }

    @Test
    void failsWhenTheGeneratorIdIsMissing() throws Exception {
        assertNotEquals(0, launch(VALID_ROUTING, null));
        assertTrue(output().contains("shardshop.id.generator-id"), output());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "1024"})
    void failsWhenTheGeneratorIdIsOutsideTheLiveRange(String generatorId) throws Exception {
        assertNotEquals(0, launch(VALID_ROUTING, generatorId));
        assertTrue(output().contains("Live generator ID must be in 1..1023"), output());
    }

    @Test
    void failsWhenTheGeneratorIdIsMalformed() throws Exception {
        assertNotEquals(0, launch(VALID_ROUTING, "not-a-number"));
        assertTrue(output().contains("shardshop.id.generator-id"), output());
    }

    @ParameterizedTest
    @ValueSource(strings = {"7", "1023"})
    void startsWithAnEnvironmentGeneratorId(String generatorId) throws Exception {
        assertEquals(0, launch(VALID_ROUTING, null, generatorId), output());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "1024", "4294967297"})
    void failsWhenTheEnvironmentGeneratorIdIsOutsideTheLiveRange(String generatorId) throws Exception {
        assertNotEquals(0, launch(VALID_ROUTING, null, generatorId));
        assertTrue(output().contains("Live generator ID must be in 1..1023"), output());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1023.0", "not-a-number"})
    void failsWhenTheEnvironmentGeneratorIdIsEmptyOrMalformed(String generatorId) throws Exception {
        assertNotEquals(0, launch(VALID_ROUTING, null, generatorId));
        assertTrue(output().contains("shardshop.id.generator-id"), output());
    }

    @Test
    void rejectsAProfileEnvironmentOverrideOfTheReservedGeneratorId() throws Exception {
        assertNotEquals(0, launch(VALID_ROUTING, "1", null, "1", Map.of(
                "CONFIG_ORDINAL", "500", "_PROD_SHARDSHOP_ID_GENERATOR_ID", "2",
                "SHARDSHOP_LAUNCHER_RESERVED_GENERATOR_ID", "2")));
        assertTrue(output().contains("Live generator ID does not match its reserved identity"), output());
    }

    @Test
    void rejectsAnExternalFileOverrideOfTheReservedGeneratorId() throws Exception {
        assertNotEquals(0, launch(VALID_ROUTING + "config_ordinal=500\nshardshop.id.generator-id=2\n",
                "1", null, "1", Map.of()));
        assertTrue(output().contains("Live generator ID does not match its reserved identity"), output());
    }

    @Test
    void matchingProfileEnvironmentConfigurationKeepsTheRawReservation() throws Exception {
        assertEquals(0, launch(VALID_ROUTING, "1", null, "1", Map.of(
                "CONFIG_ORDINAL", "500", "_PROD_SHARDSHOP_ID_GENERATOR_ID", "1",
                "SHARDSHOP_LAUNCHER_RESERVED_GENERATOR_ID", "2")), output());
    }

    @Test
    void matchingExternalFileConfigurationKeepsTheRawReservation() throws Exception {
        assertEquals(0, launch(VALID_ROUTING + "config_ordinal=500\nshardshop.id.generator-id=1\n",
                "1", null, "1", Map.of()), output());
    }

    private int launch(String properties) throws Exception {
        return launch(properties, "1");
    }

    private int launch(String properties, String generatorId) throws Exception {
        return launch(properties, generatorId, null);
    }

    private int launch(String properties, String generatorId, String environmentGeneratorId) throws Exception {
        return launch(properties, generatorId, environmentGeneratorId, null, Map.of());
    }

    private int launch(String properties, String generatorId, String environmentGeneratorId,
                       String reservedGeneratorId, Map<String, String> environment) throws Exception {
        Path config = directory.resolve("external-routing-snapshot.properties");
        if (properties != null) {
            assertEquals(config, Files.writeString(config, properties));
        }
        ProcessBuilder builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dshardshop.routing.config=" + config.toUri(),
                "-Dquarkus.profile=prod",
                "-jar", Path.of("target", "quarkus-app", "quarkus-run.jar").toAbsolutePath().toString())
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(directory.resolve("startup.log").toFile());
        if (generatorId != null) {
            builder.command().add(1, "-Dshardshop.id.generator-id=" + generatorId);
        }
        if (reservedGeneratorId != null) {
            builder.command().add(1, "-Dshardshop.launcher.reserved-generator-id=" + reservedGeneratorId);
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
