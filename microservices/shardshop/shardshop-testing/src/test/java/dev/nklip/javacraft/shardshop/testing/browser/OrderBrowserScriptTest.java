package dev.nklip.javacraft.shardshop.testing.browser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/** Executes the shipped page script in Node without requiring a browser installation. */
class OrderBrowserScriptTest {
    @TempDir
    Path directory;

    @Test
    void shippedScriptPreservesNavigationFiltersPrecisionAndOneLoadingDeadline() throws Exception {
        Path module = Path.of(System.getProperty("basedir", System.getProperty("user.dir")));
        Path script = module.resolve("../shardshop-order/src/main/resources/META-INF/resources/orders/orders.js")
                .toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(script), script.toString());
        Path harness = Path.of(Objects.requireNonNull(getClass().getResource("/browser/order-page.test.cjs")).toURI());
        NodeScriptChecks.run(directory, List.of(harness.toString(), script.toString()));
    }
}
