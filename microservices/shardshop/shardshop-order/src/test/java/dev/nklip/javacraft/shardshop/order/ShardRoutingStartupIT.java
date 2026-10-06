package dev.nklip.javacraft.shardshop.order;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShardRoutingStartupIT {

    @TempDir
    Path directory;

    @Test
    void startsWithAnExternalRoutingSnapshot() throws Exception {
        assertEquals(0, launch("shardshop.routing.shards=shard-b,shard-a\nshardshop.routing.regions=EU,US\n"));
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

    private int launch(String properties) throws Exception {
        Path config = directory.resolve("external-routing-snapshot.properties");
        if (properties != null) {
            assertEquals(config, Files.writeString(config, properties));
        }
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dshardshop.routing.config=" + config.toUri(),
                "-jar", Path.of("target", "quarkus-app", "quarkus-run.jar").toAbsolutePath().toString())
                .redirectErrorStream(true)
                .redirectOutput(directory.resolve("startup.log").toFile())
                .start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Startup exceeded 30 seconds: " + output());
            return process.exitValue();
        } finally {
            process.destroyForcibly();
        }
    }

    private String output() throws IOException {
        return Files.readString(directory.resolve("startup.log"));
    }
}
