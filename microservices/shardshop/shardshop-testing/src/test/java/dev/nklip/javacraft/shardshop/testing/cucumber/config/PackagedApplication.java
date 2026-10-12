package dev.nklip.javacraft.shardshop.testing.cucumber.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * One JVM of a packaged quarkus-app folder, as the application images start it. The output goes to a log file, and
 * the environment of the test JVM does not reach the application.
 */
public final class PackagedApplication {
    private static final Pattern PROBLEM = Pattern.compile("\\b(?:WARN|ERROR)\\b");

    private final Process process;
    private final Path log;

    private PackagedApplication(Process process, Path log) {
        this.process = process;
        this.log = log;
    }

    /**
     * Starts {@code module/target/quarkus-app/quarkus-run.jar} with the given JVM options in the prod profile.
     * The module path is relative to the ShardShop directory.
     */
    public static PackagedApplication start(String module, Path directory, List<String> options) throws IOException {
        return start(module, directory, options, List.of());
    }

    /** As {@link #start(String, Path, List)}, with program arguments after the JAR, for example --check-startup. */
    public static PackagedApplication start(String module, Path directory, List<String> options, List<String> arguments)
            throws IOException {
        Path jar = ShardShopFiles.resolve(module).resolve("target/quarkus-app/quarkus-run.jar");
        if (!Files.isRegularFile(jar)) {
            throw new IllegalStateException("Package " + module + " first; build shardshop-testing with -am");
        }
        String name = Path.of(module).getFileName().toString();
        Path log = Files.createTempFile(directory, name + "-", ".log");
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(options);
        command.add("-Dquarkus.profile=prod");
        command.add("-jar");
        command.add(jar.toString());
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
        builder.environment().clear();
        return new PackagedApplication(builder.start(), log);
    }

    public long pid() {
        return process.pid();
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    public Path log() {
        return log;
    }

    /** Waits for the exit of the application and returns its exit code. */
    public int awaitExit(Duration timeout) throws InterruptedException {
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("The application did not exit within " + timeout + ":\n" + tail());
        }
        return process.exitValue();
    }

    /** Sends SIGTERM, and returns the exit code. A JVM that does not stop within 15 seconds is killed. */
    public int stop() throws InterruptedException {
        process.destroy();
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            throw new AssertionError("The application did not stop within 15 seconds after SIGTERM:\n" + tail());
        }
        return process.exitValue();
    }

    public List<String> lines() {
        try {
            return Files.readAllLines(log, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** The log lines of the levels WARN and ERROR. */
    public List<String> problems() {
        return lines().stream().filter(line -> PROBLEM.matcher(line).find()).toList();
    }

    /** The last lines of the log, for assertion messages. */
    public String tail() {
        List<String> lines = lines();
        return String.join("\n", lines.subList(Math.max(0, lines.size() - 30), lines.size()));
    }
}
