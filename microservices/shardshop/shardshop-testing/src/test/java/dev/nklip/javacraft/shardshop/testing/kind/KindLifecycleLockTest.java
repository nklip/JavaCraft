package dev.nklip.javacraft.shardshop.testing.kind;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Exercise the shared lock of the actual Bash entry points against isolated native-command fixtures. */
class KindLifecycleLockTest {
    private static final List<String> SCRIPTS = List.of("cluster-create.sh", "up.sh", "down.sh", "cluster-destroy.sh");
    private static final List<String> NODES = List.of("shardshop-control-plane", "shardshop-worker",
            "shardshop-worker2", "shardshop-worker3");
    @TempDir Path temporary;
    private int invocation;

    @BeforeEach
    void installFixtures() throws IOException {
        Path source = Path.of(System.getProperty("shardshop.directory", "..")).toAbsolutePath().normalize();
        for (String directory : List.of("scripts", "bin", "tmp")) {
            Files.createDirectories(temporary.resolve(directory));
        }
        for (String script : SCRIPTS) {
            Files.copy(source.resolve("scripts").resolve(script), temporary.resolve("scripts").resolve(script));
        }
        Path library = source.resolve("scripts/lib");
        if (Files.isDirectory(library)) {
            try (var paths = Files.walk(library)) {
                for (Path path : paths.toList()) {
                    Path target = temporary.resolve("scripts/lib").resolve(library.relativize(path));
                    if (Files.isDirectory(path)) Files.createDirectories(target);
                    else Files.copy(path, target);
                }
            }
        }
        Files.write(temporary.resolve("nodes"), NODES);
        Files.writeString(temporary.resolve("mode"), "normal");
        Files.writeString(temporary.resolve("events"), "");
        executable("kind", """
                #!/bin/bash
                set -eu
                printf 'kind %s\n' "$*" >> "$FIXTURE/events"
                [[ "$*" == 'get nodes --name shardshop' ]] || exit 99
                cat "$FIXTURE/nodes"
                """);
        executable("docker", """
                #!/bin/bash
                set -eu
                printf 'docker %s\n' "$*" >> "$FIXTURE/events"
                mode=$(cat "$FIXTURE/mode")
                case "$1" in
                  buildx)
                    [[ "$mode" != fail-up ]] || exit 19
                    if [[ "$mode" == block-up ]]; then
                      echo $$ > "$FIXTURE/active-pid"
                      while [[ ! -f "$FIXTURE/release" ]]; do sleep 0.05; done
                      echo up-cli-complete >> "$FIXTURE/events"
                    fi
                    exit 0;;
                  scout) exit 0;;
                  update) [[ "$mode" != fail-update ]] || exit 21;;
                  stop) [[ "$mode" != fail-stop ]] || exit 20;;
                  *) exit 99;;
                esac
                """);
        for (String command : List.of("mvn", "kubectl", "helm", "helmfile", "skaffold", "openssl", "jq")) {
            executable(command, "#!/bin/sh\nprintf 'Unexpected fixture command\\n' >&2\nexit 99\n");
        }
    }

    @Test
    void existingLifecycleLockRejectsEveryEntryPointWithoutTouchingItsOwner() throws Exception {
        Files.createDirectory(lock());
        Files.writeString(lock().resolve("owner"), "other lifecycle");
        for (String script : SCRIPTS) {
            Result result = run(script);
            assertNotEquals(0, result.exit(), result.output());
            assertTrue(events().isEmpty(), "A competing " + script + " call reached kind or Docker: " + events());
            assertTrue(result.output().contains("lifecycle call"), result.output());
            assertEquals("other lifecycle", Files.readString(lock().resolve("owner")));
        }
    }

    /**
     * kind starts its nodes with restart policy on-failure:1, and a SIGKILL at the stop timeout exits with a failure
     * code. Thus the Docker daemon restarts stopped nodes at its next start, for example when Docker Desktop's Resource
     * Saver starts the VM again. The policy must be off before the stop.
     */
    @Test
    void downTurnsOffTheRestartPolicyBeforeItStopsTheNodes() throws Exception {
        Result result = run("down.sh");
        assertEquals(0, result.exit(), result.output());
        assertEquals(List.of("kind get nodes --name shardshop", "docker update --restart=no " + String.join(" ", NODES),
                "docker stop --timeout 10 " + String.join(" ", NODES)), events());
        assertTrue(result.output().contains("Stopped the shardshop kind cluster."), result.output());
        assertFalse(Files.exists(lock()));
    }

    @Test
    void failedPolicyUpdateStopsNoNodeAndReleasesTheLock() throws Exception {
        mode("fail-update");
        Result result = run("down.sh");
        assertEquals(21, result.exit(), result.output());
        assertEquals(List.of("kind get nodes --name shardshop", "docker update --restart=no " + String.join(" ", NODES)),
                events());
        assertFalse(Files.exists(lock()));
    }

    @Test
    void failedDockerStopFailsShutdownAndReleasesTheLock() throws Exception {
        mode("fail-stop");
        Result result = run("down.sh");
        assertEquals(20, result.exit(), result.output());
        assertEquals(List.of("kind get nodes --name shardshop", "docker update --restart=no " + String.join(" ", NODES),
                "docker stop --timeout 10 " + String.join(" ", NODES)), events());
        assertFalse(result.output().contains("Stopped the shardshop"));
        assertFalse(Files.exists(lock()));
    }

    @Test
    void interruptedUpRetainsItsLockUntilTheForegroundCliCompletes() throws Exception {
        interruptUp("TERM", 143);
    }

    @Test
    void controlCAlsoRetainsTheUpLockUntilTheForegroundCliCompletes() throws Exception {
        interruptUp("INT", 130);
    }

    @Test
    void upPreflightFailureAlsoReleasesTheLifecycleLock() throws Exception {
        mode("fail-up");
        Result result = run("up.sh");
        assertNotEquals(0, result.exit(), result.output());
        assertTrue(result.output().contains("Buildx"), result.output());
        assertFalse(Files.exists(lock()));
    }

    private void interruptUp(String signal, int expectedExit) throws Exception {
        mode("block-up");
        try (Running up = start("up.sh")) {
            await(() -> Files.exists(temporary.resolve("active-pid")), "up did not reach its foreground command");
            long child = activePid();
            signal(up.process(), signal);
            Result contender = run("down.sh");
            assertNotEquals(0, contender.exit(), contender.output());
            assertTrue(Files.exists(lock()));
            assertTrue(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false));
            Files.writeString(temporary.resolve("release"), "complete");
            Result result = up.finish();
            assertEquals(expectedExit, result.exit(), result.output());
            assertTrue(events().contains("up-cli-complete"));
            assertFalse(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false));
            assertFalse(Files.exists(lock()));
        }
    }

    private Running start(String script) throws IOException {
        Path output = temporary.resolve("output-" + invocation++);
        ProcessBuilder builder = new ProcessBuilder("/bin/bash", temporary.resolve("scripts").resolve(script).toString())
                .directory(temporary.toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().putAll(Map.of("PATH", temporary.resolve("bin") + ":/usr/bin:/bin",
                "TMPDIR", temporary.resolve("tmp").toString(), "FIXTURE", temporary.toString()));
        for (String name : List.of("SHARDSHOP_TEST_CLUSTER", "SHARDSHOP_TEST_KUBECONFIG", "SHARDSHOP_CLUSTER_NAME", "SHARDSHOP_CONTEXT")) {
            builder.environment().remove(name);
        }
        Process process = builder.start();
        process.getOutputStream().close();
        return new Running(process, output);
    }

    private Result run(String script) throws Exception {
        try (Running running = start(script)) {
            return running.finish();
        }
    }

    private static void signal(Process process, String signal) throws Exception {
        Process kill = new ProcessBuilder("/bin/kill", "-" + signal, Long.toString(process.pid())).start();
        try {
            assertTrue(kill.waitFor(5, TimeUnit.SECONDS), "signal command exceeded its deadline");
            assertEquals(0, kill.exitValue());
        } finally {
            kill.destroyForcibly();
        }
    }

    private static void await(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
        assertTrue(condition.getAsBoolean(), message);
    }

    private Path lock() { return temporary.resolve("tmp/shardshop-up.lock"); }
    private long activePid() throws IOException { return Long.parseLong(Files.readString(temporary.resolve("active-pid")).trim()); }
    private void mode(String mode) throws IOException { Files.writeString(temporary.resolve("mode"), mode); }
    private List<String> events() throws IOException { return Files.readAllLines(temporary.resolve("events")); }

    private void executable(String name, String script) throws IOException {
        Path path = temporary.resolve("bin").resolve(name);
        Files.writeString(path, script);
        assertTrue(path.toFile().setExecutable(true), "Cannot create fixture " + name);
    }

    private record Result(int exit, String output) { }
    private record Running(Process process, Path output) implements AutoCloseable {
        Result finish() throws Exception {
            assertTrue(process.waitFor(12, TimeUnit.SECONDS), "Lifecycle script exceeded its test deadline");
            assertTrue(Files.size(output) <= 65_536, "Unexpectedly large lifecycle output");
            return new Result(process.exitValue(), Files.readString(output));
        }

        @Override public void close() {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }
}
