package dev.nklip.javacraft.shardshop.testing.browser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Optional Node execution keeps ordinary Java builds independent of a browser installation. */
class WorkloadControlScriptTest {
    @TempDir
    Path directory;

    @Test
    void sharedScriptControlsEachWorkloadPageWithExactRatesPollingAndRuns() throws Exception {
        Process version;
        try {
            version = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
        } catch (IOException unavailable) {
            assumeTrue(false, "Node is unavailable; execute browser/workload-control-page.test.cjs where Node is installed");
            return;
        }
        try {
            assumeTrue(version.waitFor(5, TimeUnit.SECONDS) && version.exitValue() == 0, "Node is unavailable");
        } finally {
            version.destroyForcibly();
        }
        Path shardshop = Path.of(System.getProperty("basedir", System.getProperty("user.dir"))).resolve("..")
                .toAbsolutePath().normalize();
        List<Path> files = List.of(
                shardshop.resolve("shardshop-core/workload-control/src/main/resources/META-INF/resources/workload-control/control.js"),
                page(shardshop, "shardshop-order-producer", "order-producer"),
                page(shardshop, "shardshop-product-reader", "product-reader"),
                page(shardshop, "shardshop-product-seeder", "product-seeder"));
        List<String> command = new ArrayList<>(List.of("node",
                Path.of(Objects.requireNonNull(getClass().getResource("/browser/workload-control-page.test.cjs")).toURI()).toString()));
        for (Path file : files) {
            assertTrue(Files.isRegularFile(file), file.toString());
            command.add(file.toString());
        }
        Path output = directory.resolve("node-output.txt");
        Process checks = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(checks.waitFor(20, TimeUnit.SECONDS), "Workload control page checks exceeded 20 seconds");
            assertEquals(0, checks.exitValue(), () -> readOutput(output));
        } finally {
            checks.destroyForcibly();
        }
    }

    private static Path page(Path shardshop, String module, String workload) {
        return shardshop.resolve("shardshop-workload/" + module + "/src/main/resources/META-INF/resources/workload-" + workload
                + "/index.html");
    }

    private static String readOutput(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException failure) {
            return failure.toString();
        }
    }
}
