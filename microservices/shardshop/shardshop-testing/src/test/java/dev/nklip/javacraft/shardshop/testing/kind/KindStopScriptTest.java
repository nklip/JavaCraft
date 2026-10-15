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

/** Exercise the actual Bash entry points against isolated native-command fixtures. */
class KindStopScriptTest {
    private static final List<String> NODES = List.of("shardshop-control-plane", "shardshop-worker",
            "shardshop-worker2", "shardshop-worker3");
    @TempDir Path temporary;
    private int invocation;

    @BeforeEach
    void installFixtures() throws IOException {
        Path source = Path.of(System.getProperty("shardshop.directory", "..")).toAbsolutePath().normalize();
        for (String directory : List.of("scripts", "bin", "tmp", "state")) {
            Files.createDirectories(temporary.resolve(directory));
        }
        for (String script : List.of("up.sh", "down.sh", "cluster-create.sh")) {
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
        for (String node : NODES) Files.writeString(temporary.resolve("state").resolve(node), "true\n");
        Files.writeString(temporary.resolve("state/unrelated-container"), "true\n");
        executable("kind", """
                #!/bin/bash
                set -eu
                printf 'kind %s\n' "$*" >> "$FIXTURE/events"
                [[ "$*" == 'get nodes --name shardshop' ]] || exit 99
                case "$(cat "$FIXTURE/mode")" in
                  fail-kind) exit 17;;
                  hang-kind) echo $$ > "$FIXTURE/active-pid"; exec tail -f /dev/null;;
                esac
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
                  inspect)
                    [[ "$mode" != fail-inspect ]] || exit 18
                    [[ "$2" == --format && "$3" == '{{.State.Running}}' && $# == 4 ]] || exit 99
                    cat "$FIXTURE/state/$4";;
                  stop)
                    [[ "$2" == --time && "$3" == 10 ]] || exit 99
                    shift 3
                    [[ "$mode" != fail-stop ]] || exit 20
                    if [[ "$mode" == block-stop || "$mode" == hang-stop ]]; then
                      echo $$ > "$FIXTURE/active-pid"
                      if [[ "$mode" == hang-stop ]]; then trap '' TERM; fi
                      while [[ ! -f "$FIXTURE/release" ]]; do sleep 0.05; done
                    fi
                    for node in "$@"; do
                      [[ "$mode" == lying-stop ]] || echo false > "$FIXTURE/state/$node"
                    done;;
                  *) exit 99;;
                esac
                """);
        for (String command : List.of("mvn", "kubectl", "helm", "helmfile", "skaffold", "openssl", "jq")) {
            executable(command, "#!/bin/sh\nprintf 'Unexpected fixture command\\n' >&2\nexit 99\n");
        }
    }

    @Test
    void stopsAndVerifiesTheControlPlaneAndEveryWorkerWithoutRemovingContainers() throws Exception {
        Result result = run("down.sh", "60");
        assertEquals(0, result.exit(), result.output());
        assertEquals(List.of("docker stop --time 10 " + String.join(" ", NODES)), mutations());
        for (String node : NODES) {
            assertEquals("false\n", Files.readString(temporary.resolve("state").resolve(node)));
        }
        assertEquals("true\n", Files.readString(temporary.resolve("state/unrelated-container")));
        assertFalse(Files.exists(lock()));
    }

    @Test
    void repeatedShutdownIsSuccessfulWithoutAnotherMutation() throws Exception {
        assertEquals(0, run("down.sh", "60").exit());
        List<String> firstMutations = mutations();
        Result repeated = run("down.sh", "60");
        assertEquals(0, repeated.exit(), repeated.output());
        assertEquals(firstMutations, mutations());
        assertFalse(Files.exists(lock()));
    }

    @Test
    void onlyStopsRunningNodesInAPartiallyStoppedCluster() throws Exception {
        Files.writeString(temporary.resolve("state/shardshop-worker"), "false\n");
        Result result = run("down.sh", "60");
        assertEquals(0, result.exit(), result.output());
        assertEquals(List.of("docker stop --time 10 shardshop-control-plane shardshop-worker2 shardshop-worker3"), mutations());
    }

    @Test
    void missingClusterSucceedsWithoutDockerMutation() throws Exception {
        Files.writeString(temporary.resolve("nodes"), "");
        Result result = run("down.sh", "60");
        assertEquals(0, result.exit(), result.output());
        assertEquals(List.of("kind get nodes --name shardshop"), events());
        assertFalse(Files.exists(lock()));
    }

    @Test
    void discoveryAndInspectionErrorsFailBeforeAnyStopAndReleaseTheLock() throws Exception {
        for (String mode : List.of("fail-kind", "fail-inspect")) {
            mode(mode);
            Result result = run("down.sh", "60");
            assertNotEquals(0, result.exit(), result.output());
            assertTrue(mutations().isEmpty());
            assertFalse(Files.exists(lock()));
        }
    }

    @Test
    void failedDockerStopFailsShutdownAndReleasesTheLock() throws Exception {
        mode("fail-stop");
        Result result = run("down.sh", "60");
        assertEquals(20, result.exit(), result.output());
        assertFalse(result.output().contains("Stopped the shardshop"));
        assertFalse(Files.exists(lock()));
    }

    @Test
    void dockerSuccessDoesNotHideAStillRunningNode() throws Exception {
        mode("lying-stop");
        Result result = run("down.sh", "60");
        assertNotEquals(0, result.exit(), result.output());
        assertTrue(result.output().contains("still running"), result.output());
        assertFalse(Files.exists(lock()));
    }

    @Test
    void discoveryCannotHangPastTheShutdownDeadline() throws Exception {
        assertTimedOut("hang-kind");
        assertTrue(mutations().isEmpty());
    }

    @Test
    void unresponsiveStopIsKilledAndReapedBeforeReleasingTheLock() throws Exception {
        assertTimedOut("hang-stop");
        assertEquals(1, mutations().size());
    }

    @Test
    void terminationReapsAnActiveStopBeforeReleasingTheSharedLock() throws Exception {
        interruptStop("TERM", 143);
    }

    @Test
    void interruptReapsAnActiveStopBeforeReleasingTheSharedLock() throws Exception {
        interruptStop("INT", 130);
    }

    @Test
    void existingLifecycleLockRejectsCreateUpAndDownWithoutTouchingItsOwner() throws Exception {
        Files.createDirectory(lock());
        Files.writeString(lock().resolve("owner"), "other lifecycle");
        for (String script : List.of("down.sh", "up.sh", "cluster-create.sh")) {
            Result result = run(script, "60");
            assertNotEquals(0, result.exit(), result.output());
            assertTrue(events().isEmpty(), "A competing lifecycle call reached kind or Docker: " + events());
            assertTrue(result.output().contains("lifecycle call"), result.output());
            assertEquals("other lifecycle", Files.readString(lock().resolve("owner")));
        }
        assertTrue(events().isEmpty());
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
        Result result = run("up.sh", "60");
        assertNotEquals(0, result.exit(), result.output());
        assertTrue(result.output().contains("Buildx"), result.output());
        assertFalse(Files.exists(lock()));
    }

    @Test
    void invalidTimeoutFailsWithoutCallingNativeTools() throws Exception {
        for (String timeout : List.of("0", "-1", "text", "3601", "99999999999999999999")) {
            assertNotEquals(0, run("down.sh", timeout).exit());
            assertFalse(Files.exists(lock()));
        }
        assertTrue(events().isEmpty());
    }

    private void assertTimedOut(String mode) throws Exception {
        mode(mode);
        // The global deadline includes discovery and four bounded inspections before stop.
        // Leave time for that preflight so this test reaches the intended unresponsive CLI.
        Result result = run("down.sh", mode.equals("hang-stop") ? "5" : "2");
        assertEquals(124, result.exit(), result.output());
        assertTrue(result.output().contains("timeout"), result.output());
        assertTrue(Files.exists(temporary.resolve("active-pid")), "The intended blocking CLI was not reached: " + result.output());
        assertFalse(ProcessHandle.of(activePid()).map(ProcessHandle::isAlive).orElse(false));
        assertFalse(Files.exists(lock()));
    }

    private void interruptStop(String signal, int expectedExit) throws Exception {
        mode("block-stop");
        try (Running stopping = start("down.sh", "60")) {
            await(() -> Files.exists(temporary.resolve("active-pid")), "down did not reach Docker stop");
            long child = activePid();
            for (String script : List.of("up.sh", "down.sh", "cluster-create.sh")) {
                Result contender = run(script, "60");
                assertNotEquals(0, contender.exit(), contender.output());
                assertTrue(Files.exists(lock()));
            }
            signal(stopping.process(), signal);
            Result result = stopping.finish();
            assertEquals(expectedExit, result.exit(), result.output());
            assertFalse(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false));
            assertFalse(Files.exists(lock()));
        }
    }

    private void interruptUp(String signal, int expectedExit) throws Exception {
        mode("block-up");
        try (Running up = start("up.sh", "60")) {
            await(() -> Files.exists(temporary.resolve("active-pid")), "up did not reach its foreground command");
            long child = activePid();
            signal(up.process(), signal);
            Result contender = run("down.sh", "60");
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

    private Running start(String script, String timeout) throws IOException {
        Path output = temporary.resolve("output-" + invocation++);
        ProcessBuilder builder = new ProcessBuilder("/bin/bash", temporary.resolve("scripts").resolve(script).toString())
                .directory(temporary.toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().putAll(Map.of("PATH", temporary.resolve("bin") + ":/usr/bin:/bin",
                "TMPDIR", temporary.resolve("tmp").toString(), "FIXTURE", temporary.toString(),
                "SHARDSHOP_SHUTDOWN_TIMEOUT_SECONDS", timeout));
        Process process = builder.start();
        process.getOutputStream().close();
        return new Running(process, output);
    }

    private Result run(String script, String timeout) throws Exception {
        try (Running running = start(script, timeout)) {
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
    private List<String> mutations() throws IOException {
        return events().stream().filter(line -> line.startsWith("docker stop ")).toList();
    }

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
