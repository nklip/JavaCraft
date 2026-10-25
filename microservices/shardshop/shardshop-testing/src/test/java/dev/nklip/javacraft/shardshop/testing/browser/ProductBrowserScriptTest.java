package dev.nklip.javacraft.shardshop.testing.browser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Makes the product page harness part of the mandatory Maven test suite. */
class ProductBrowserScriptTest {
    @TempDir
    Path directory;

    @Test
    void shippedScriptPreservesNavigationPrecisionCurrencyPolicyAndOneLoadingDeadline() throws Exception {
        Path module = Path.of(System.getProperty("basedir", System.getProperty("user.dir")));
        Path harness = module.resolve("../shardshop-product/src/test/js/products.test.cjs")
                .toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(harness), harness.toString());
        NodeScriptChecks.run(directory, List.of("--test", harness.toString()));
    }
}
