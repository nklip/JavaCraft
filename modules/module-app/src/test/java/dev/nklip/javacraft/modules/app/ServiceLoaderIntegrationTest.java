package dev.nklip.javacraft.modules.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import dev.nklip.javacraft.modules.hello.HelloService;
import dev.nklip.javacraft.modules.util.Util;
import org.junit.jupiter.api.Test;

class ServiceLoaderIntegrationTest {

    @Test
    void testRunOnModulePathShouldResolveHelloServiceProvider() throws Exception {
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
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();

        assertEquals(0, exitCode, output);
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
