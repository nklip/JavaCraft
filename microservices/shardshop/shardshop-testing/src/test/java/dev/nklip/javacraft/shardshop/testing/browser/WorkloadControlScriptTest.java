package dev.nklip.javacraft.shardshop.testing.browser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/** Executes the shipped page script in Node without requiring a browser installation. */
class WorkloadControlScriptTest {
    @TempDir
    Path directory;

    @Test
    void sharedScriptControlsEachWorkloadPageWithExactRatesPollingAndRuns() throws Exception {
        Path shardshop = Path.of(System.getProperty("basedir", System.getProperty("user.dir"))).resolve("..")
                .toAbsolutePath().normalize();
        List<Path> files = List.of(
                shardshop.resolve("shardshop-core/workload-control/src/main/resources/META-INF/resources/workload-control/control.js"),
                page(shardshop, "shardshop-order-producer", "order-producer"),
                page(shardshop, "shardshop-product-reader", "product-reader"),
                page(shardshop, "shardshop-product-seeder", "product-seeder"));
        List<String> command = new ArrayList<>(List.of(
                Path.of(Objects.requireNonNull(getClass().getResource("/browser/workload-control-page.test.cjs")).toURI()).toString()));
        for (Path file : files) {
            assertTrue(Files.isRegularFile(file), file.toString());
            command.add(file.toString());
        }
        NodeScriptChecks.run(directory, command);
    }

    private static Path page(Path shardshop, String module, String workload) {
        return shardshop.resolve("shardshop-workload/" + module + "/src/main/resources/META-INF/resources/workload-" + workload
                + "/index.html");
    }
}
