package dev.nklip.javacraft.shardshop.testing.browser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Mandatory JavaScript checks shared by the page harnesses. */
final class NodeScriptChecks {
    private NodeScriptChecks() {
    }

    static void run(Path directory, List<String> arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("node"));
        command.addAll(arguments);
        Path output = directory.resolve("node-output.txt");
        Process checks;
        try {
            checks = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        } catch (IOException unavailable) {
            throw new AssertionError("Cannot start Node.js for mandatory JavaScript checks. "
                    + "Install Node.js and put the node executable on PATH, then rerun Maven.", unavailable);
        }
        try {
            assertTrue(checks.waitFor(20, TimeUnit.SECONDS), "JavaScript behavior checks exceeded 20 seconds");
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
