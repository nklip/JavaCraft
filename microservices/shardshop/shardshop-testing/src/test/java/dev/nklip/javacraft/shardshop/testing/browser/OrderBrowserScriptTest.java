package dev.nklip.javacraft.shardshop.testing.browser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Optional Node execution keeps ordinary Java builds independent of a browser installation. */
class OrderBrowserScriptTest {
    @TempDir
    Path directory;

    @Test
    void shippedScriptPreservesNavigationFiltersPrecisionAndOneLoadingDeadline() throws Exception {
        Process version;
        try {
            version = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
        } catch (IOException unavailable) {
            assumeTrue(false, "Node is unavailable; execute browser/order-page.test.cjs where Node is installed");
            return;
        }
        try {
            assumeTrue(version.waitFor(5, TimeUnit.SECONDS) && version.exitValue() == 0, "Node is unavailable");
        } finally {
            version.destroyForcibly();
        }
        Path module = Path.of(System.getProperty("basedir", System.getProperty("user.dir")));
        Path script = module.resolve("../shardshop-order/src/main/resources/META-INF/resources/orders/orders.js")
                .toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(script), script.toString());
        Path harness = Path.of(Objects.requireNonNull(getClass().getResource("/browser/order-page.test.cjs")).toURI());
        Path output = directory.resolve("node-output.txt");
        Process checks = new ProcessBuilder("node", harness.toString(), script.toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(checks.waitFor(20, TimeUnit.SECONDS), "Order browser behavior checks exceeded 20 seconds");
            assertEquals(0, checks.exitValue(), () -> readOutput(output));
        } finally {
            checks.destroyForcibly();
        }
    }

    private static String readOutput(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException failure) {
            return failure.toString();
        }
    }
}
