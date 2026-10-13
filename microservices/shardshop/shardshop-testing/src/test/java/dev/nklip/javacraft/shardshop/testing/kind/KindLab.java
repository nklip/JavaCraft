package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.Json;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/** The immutable invocation snapshot and bounded commands against its explicitly selected kind lab. */
public final class KindLab {
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);
    private static final int OUTPUT_LIMIT = 4 * 1024 * 1024;
    private final Path snapshot;
    private final String clusterName;
    private final boolean fresh;

    public KindLab() {
        this(snapshotProperty(), System.getProperty("shardshop.kind.cluster", "shardshop"),
                Boolean.parseBoolean(System.getProperty("shardshop.kind.fresh", "false")));
    }

    KindLab(Path snapshot, String clusterName, boolean fresh) {
        this.snapshot = snapshot.toAbsolutePath().normalize();
        if (!clusterName.matches("[a-z0-9][a-z0-9-]*")) {
            throw new IllegalArgumentException("Invalid kind cluster name");
        }
        this.clusterName = clusterName;
        this.fresh = fresh;
        for (String file : List.of("inventory.json", "kind.json", "infrastructure.yaml")) {
            if (!Files.isRegularFile(this.snapshot.resolve(file))) {
                throw new IllegalArgumentException("The kind invocation snapshot is missing " + file);
            }
        }
    }

    private static Path snapshotProperty() {
        String value = System.getProperty("shardshop.kind.snapshot");
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Set -Dshardshop.kind.snapshot to the invocation snapshot directory");
        }
        return Path.of(value);
    }

    public Path snapshot() {
        return snapshot;
    }

    public String clusterName() {
        return clusterName;
    }

    public boolean fresh() {
        return fresh;
    }

    public JsonNode json(String filename) throws IOException {
        Path file = snapshot.resolve(filename).normalize();
        if (!file.startsWith(snapshot)) {
            throw new IllegalArgumentException("Snapshot filename must remain inside the snapshot");
        }
        return parse(Files.readString(file));
    }

    public JsonNode inventory() throws IOException {
        return json("inventory.json").path("data");
    }

    public List<String> shards() throws IOException {
        return inventory().path("shards").asText().lines().map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    public List<String> clusters() throws IOException {
        List<String> clusters = new ArrayList<>(shards());
        clusters.add("ledger-db");
        return List.copyOf(clusters);
    }

    public static JsonNode parse(String text) {
        return text.isBlank() ? Json.newObject() : Json.parse(text.getBytes(StandardCharsets.UTF_8));
    }

    /** kubectl returns either a resource list or one resource. An empty response has no resources. */
    public static List<JsonNode> items(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || node.isEmpty()) {
            return List.of();
        }
        JsonNode entries = node.isArray() ? node : node.get("items");
        if (entries == null) {
            return List.of(node);
        }
        List<JsonNode> result = new ArrayList<>();
        entries.forEach(result::add);
        return List.copyOf(result);
    }

    public record CommandResult(int exitCode, String stdout, String stderr) {
    }

    public String command(String... arguments) throws IOException, InterruptedException {
        return command(COMMAND_TIMEOUT, arguments);
    }

    public String command(Duration timeout, String... arguments) throws IOException, InterruptedException {
        CommandResult result = attempt(timeout, arguments);
        if (result.exitCode() != 0) {
            // Arguments and output may contain credentials. Return them only through the explicit result API.
            throw new IOException("Kind command failed with exit code " + result.exitCode());
        }
        return result.stdout();
    }

    public CommandResult attempt(String... arguments) throws IOException, InterruptedException {
        return attempt(COMMAND_TIMEOUT, arguments);
    }

    public CommandResult attempt(Duration timeout, String... arguments) throws IOException, InterruptedException {
        requirePositive(timeout);
        long deadline = System.nanoTime() + timeout.toNanos();
        Process process = new ProcessBuilder(arguments).start();
        process.getOutputStream().close();
        var readers = Executors.newVirtualThreadPerTaskExecutor();
        AtomicReference<IOException> outputFailure = new AtomicReference<>();
        Future<String> stdout = readers.submit(() -> capture(process.getInputStream(), process, outputFailure));
        Future<String> stderr = readers.submit(() -> capture(process.getErrorStream(), process, outputFailure));
        try {
            if (!process.waitFor(remaining(deadline), TimeUnit.NANOSECONDS)) {
                throw new IOException("Kind command exceeded " + timeout);
            }
            if (outputFailure.get() != null) {
                throw outputFailure.get();
            }
            return new CommandResult(process.exitValue(), collect(stdout, deadline), collect(stderr, deadline));
        } finally {
            terminate(process);
            stdout.cancel(true);
            stderr.cancel(true);
            readers.shutdownNow();
            process.getInputStream().close();
            process.getErrorStream().close();
        }
    }

    private static String capture(InputStream stream, Process process, AtomicReference<IOException> failure)
            throws IOException {
        try (stream; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                if (bytes.size() + count > OUTPUT_LIMIT) {
                    throw new IOException("Kind command output exceeded " + OUTPUT_LIMIT + " bytes");
                }
                bytes.write(buffer, 0, count);
            }
            return bytes.toString(StandardCharsets.UTF_8);
        } catch (IOException problem) {
            failure.compareAndSet(null, problem);
            terminate(process);
            throw problem;
        }
    }

    private static String collect(Future<String> output, long deadline) throws IOException, InterruptedException {
        try {
            return output.get(remaining(deadline), TimeUnit.NANOSECONDS);
        } catch (ExecutionException failure) {
            throw new IOException("Cannot capture kind command output", failure.getCause());
        } catch (TimeoutException failure) {
            throw new IOException("Kind command output did not close before its timeout", failure);
        }
    }

    private static long remaining(long deadline) {
        return Math.max(0, deadline - System.nanoTime());
    }

    private static void terminate(Process process) {
        List<ProcessHandle> children = process.descendants().toList();
        children.reversed().forEach(ProcessHandle::destroyForcibly);
        if (process.isAlive()) {
            process.destroyForcibly();
        }
    }

    public String kubectl(String... arguments) throws IOException, InterruptedException {
        return kubectl(COMMAND_TIMEOUT, arguments);
    }

    public String kubectl(Duration timeout, String... arguments) throws IOException, InterruptedException {
        return command(timeout, kubectlArguments(arguments));
    }

    public CommandResult kubectlAttempt(String... arguments) throws IOException, InterruptedException {
        return kubectlAttempt(COMMAND_TIMEOUT, arguments);
    }

    public CommandResult kubectlAttempt(Duration timeout, String... arguments) throws IOException, InterruptedException {
        return attempt(timeout, kubectlArguments(arguments));
    }

    private String[] kubectlArguments(String[] arguments) {
        List<String> command = new ArrayList<>(List.of("kubectl", "--context", "kind-" + clusterName,
                "--request-timeout=10s", "-n", "shardshop"));
        command.addAll(List.of(arguments));
        return command.toArray(String[]::new);
    }

    @FunctionalInterface
    public interface Probe {
        boolean check() throws IOException, InterruptedException;
    }

    public void await(String description, Duration timeout, Probe probe) throws IOException, InterruptedException {
        requirePositive(timeout);
        long deadline = System.nanoTime() + timeout.toNanos();
        IOException lastFailure = null;
        var probes = Executors.newVirtualThreadPerTaskExecutor();
        try {
            do {
                Future<Boolean> pending = probes.submit(probe::check);
                try {
                    if (pending.get(remaining(deadline), TimeUnit.NANOSECONDS)) {
                        return;
                    }
                } catch (ExecutionException failure) {
                    if (failure.getCause() instanceof IOException problem) {
                        lastFailure = problem;
                    } else if (failure.getCause() instanceof InterruptedException problem) {
                        throw problem;
                    } else if (failure.getCause() instanceof RuntimeException problem) {
                        throw problem;
                    } else if (failure.getCause() instanceof Error problem) {
                        throw problem;
                    } else {
                        throw new IOException("Kind readiness probe failed", failure.getCause());
                    }
                } catch (TimeoutException failure) {
                    throw new IOException("Timed out waiting for " + description + " after " + timeout, lastFailure);
                } finally {
                    pending.cancel(true);
                }
                long remaining = remaining(deadline);
                if (remaining == 0) {
                    break;
                }
                TimeUnit.NANOSECONDS.sleep(Math.min(remaining, Duration.ofMillis(500).toNanos()));
            } while (remaining(deadline) > 0);
            throw new IOException("Timed out waiting for " + description + " after " + timeout, lastFailure);
        } finally {
            probes.shutdownNow();
        }
    }

    private static void requirePositive(Duration timeout) {
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Timeout must be positive");
        }
    }
}
