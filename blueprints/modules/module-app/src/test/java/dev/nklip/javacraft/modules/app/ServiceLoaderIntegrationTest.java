package dev.nklip.javacraft.modules.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import dev.nklip.javacraft.modules.hello.HelloService;
import dev.nklip.javacraft.modules.util.Util;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServiceLoaderIntegrationTest {

    @Test
    void testRunOnModulePathShouldResolveHelloServiceProvider(@TempDir Path tempDir) throws Exception {
        String modulePath = Stream.of(
                        pathFor(App.class),
                        pathFor(HelloService.class),
                        pathFor(Util.class)
                )
                .distinct()
                .map(Path::toString)
                .collect(Collectors.joining(File.pathSeparator));

        ProcessBuilder processBuilder = new ProcessBuilder(
                javaBinary(),
                "--module-path",
                modulePath,
                "--module",
                "module.app/dev.nklip.javacraft.modules.app.App"
        );
        Path outputFile = tempDir.resolve("module-app.log");
        processBuilder.redirectErrorStream(true).redirectOutput(outputFile.toFile());

        Process process = processBuilder.start();
        boolean finished;
        try {
            finished = process.waitFor(10, TimeUnit.SECONDS);
        } finally {
            if (process.isAlive()) {
                assertTrue(process.destroyForcibly().waitFor(5, TimeUnit.SECONDS),
                        () -> "Child JVM " + process.pid() + " did not terminate after forced shutdown");
            }
        }

        String output = Files.readString(outputFile);
        assertTrue(finished, () -> "Module app timed out after 10 seconds.\n" + output);
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("Hello World from Modules!"), output);
    }

    private Path pathFor(Class<?> sourceType) throws Exception {
        return Path.of(sourceType.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private String javaBinary() {
        String executable = System.getProperty("os.name")
                .toLowerCase(Locale.ROOT)
                .contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }
}
