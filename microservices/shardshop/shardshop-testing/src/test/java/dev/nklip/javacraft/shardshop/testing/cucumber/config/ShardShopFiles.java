package dev.nklip.javacraft.shardshop.testing.cucumber.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Files of the ShardShop source tree and of the scenario runs. Failsafe sets the two directory properties. */
public final class ShardShopFiles {

    private ShardShopFiles() {
    }

    public static Path resolve(String relativePath) {
        return Path.of(property("shardshop.directory")).resolve(relativePath).toAbsolutePath().normalize();
    }

    /** A new directory under target/scenario-runs for the logs and files of one test object; mvn clean removes it. */
    public static Path newWorkDirectory(String prefix) throws IOException {
        Path runs = Files.createDirectories(Path.of(property("shardshop.testing.work")));
        return Files.createTempDirectory(runs, prefix);
    }

    private static String property(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Run the scenarios with Maven Failsafe, which sets " + name);
        }
        return value;
    }
}
