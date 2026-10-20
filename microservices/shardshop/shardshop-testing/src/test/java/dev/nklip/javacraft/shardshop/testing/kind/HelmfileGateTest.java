package dev.nklip.javacraft.shardshop.testing.kind;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Run real Helmfile orchestration with controlled local commands, without a cluster. */
class HelmfileGateTest {
    private static final String FINAL_GATE = "verify kind-release | kind-deployment";
    @TempDir Path temporary;

    @BeforeEach
    void snapshotHelmfileAndInstallControlledCommands() throws IOException {
        Path source = Path.of(System.getProperty("shardshop.directory", "..")).toAbsolutePath().normalize();
        Files.copy(source.resolve("infra/helmfile.yaml.gotmpl"), temporary.resolve("helmfile.yaml.gotmpl"));
        Files.createDirectories(temporary.resolve("chart"));
        Files.writeString(temporary.resolve("chart/Chart.yaml"), "apiVersion: v2\nname: fixture\nversion: 0.1.0\n");
        for (String name : List.of("environment.yaml", "inventory.yaml", "images.json", "operator-values.yaml")) {
            Files.writeString(temporary.resolve(name), "{}\n");
        }
        // Fake Helm handles chart contents; real Helmfile still reads each snapshot values file.
        Files.writeString(temporary.resolve("operator.tgz"), "fixture");
        Files.createDirectories(temporary.resolve("bin"));
        executable("helm", """
                #!/bin/sh
                if [ "$1" = --kube-context ]; then shift 2; fi
                case "$1" in
                  version) printf 'v4.3.0\n';;
                  list) printf '[]\n';;
                  show) printf 'name: fixture\nversion: 0.1.0\n';;
                  upgrade)
                    release="$3"
                    printf 'sync-start %s\n' "$release" >> "$FIXTURE_LOG"
                    if [ "$release" = "$FAIL_RELEASE" ]; then
                      printf 'Controlled Helm failure: readiness or post-upgrade seed hook\n' >&2
                      exit 17
                    fi
                    printf 'sync-complete %s\n' "$release" >> "$FIXTURE_LOG"
                    ;;
                  *) printf 'Unexpected Helm command: %s\n' "$*" >&2; exit 99;;
                esac
                """);
        executable("mvn", """
                #!/bin/sh
                for argument in "$@"; do
                  case "$argument" in
                    -Dshardshop.kind.tags=*)
                      tags="${argument#-Dshardshop.kind.tags=}"
                      printf 'verify %s\n' "$tags" >> "$FIXTURE_LOG"
                      case "$tags" in *kind-release*) exit "$FAIL_GATE";; esac
                      case "$tags" in *kind-drills-idle*) exit "$FAIL_DRILL_GATE";; esac
                      ;;
                  esac
                done
                """);
        executable("kubectl", """
                #!/bin/sh
                case "$*" in
                  '--context kind-shardshop wait --for=condition=Established crd --all --timeout=180s') exit 0;;
                  *) printf 'Unexpected kubectl command: %s\n' "$*" >&2; exit 99;;
                esac
                """);
    }

    @Test
    void successfulSyncRunsFinalVerificationOnceAfterApplicationCompletion() throws Exception {
        Sync result = sync("", 0);
        assertEquals(0, result.exit(), result.output());
        assertEquals(List.of(
                "sync-start cnpg", "sync-complete cnpg",
                "sync-start shardshop-infrastructure", "sync-complete shardshop-infrastructure",
                "verify kind-allocator | kind-infrastructure | kind-migration-reservation | kind-migrations-idle",
                "sync-start shardshop-migrations", "sync-complete shardshop-migrations",
                "verify kind-publications | kind-migration-reservation | kind-drills-idle",
                "sync-start shardshop-application", "sync-complete shardshop-application", FINAL_GATE), result.events());
    }

    @Test
    void retainedDrillLockStopsTheApplicationReleaseBeforeHelmChangesIt() throws Exception {
        Sync result = sync("", 0, 1);
        assertNotEquals(0, result.exit(), result.output());
        assertTrue(result.events().contains("sync-complete shardshop-migrations"));
        assertEquals("verify kind-publications | kind-migration-reservation | kind-drills-idle", result.events().getLast());
        assertFalse(result.events().contains("sync-start shardshop-application"), result.output());
        assertFalse(result.events().contains(FINAL_GATE), result.output());
    }

    @Test
    void failedFinalVerificationFailsAnOtherwiseSuccessfulSync() throws Exception {
        Sync result = sync("", 23);
        assertNotEquals(0, result.exit(), result.output());
        assertEquals(1, result.events().stream().filter(FINAL_GATE::equals).count());
        assertEquals("sync-complete shardshop-application", result.events().get(result.events().size() - 2));
        assertEquals(FINAL_GATE, result.events().getLast());
    }

    @Test
    void failedApplicationOrItsSeedHookFailsSyncWithoutVerifyingAnOlderRelease() throws Exception {
        Sync result = sync("shardshop-application", 0);
        assertNotEquals(0, result.exit(), result.output());
        assertTrue(result.events().contains("sync-complete shardshop-migrations"));
        assertEquals("sync-start shardshop-application", result.events().getLast());
        assertFalse(result.events().contains(FINAL_GATE), result.output());
    }

    @Test
    void failedMigrationBlocksApplicationAndFinalVerification() throws Exception {
        Sync result = sync("shardshop-migrations", 0);
        assertNotEquals(0, result.exit(), result.output());
        assertEquals("sync-start shardshop-migrations", result.events().getLast());
        assertFalse(result.events().contains("sync-start shardshop-application"), result.output());
        assertFalse(result.events().contains(FINAL_GATE), result.output());
    }

    private Sync sync(String failedRelease, int failedGate) throws Exception {
        return sync(failedRelease, failedGate, 0);
    }

    private Sync sync(String failedRelease, int failedGate, int failedDrillGate) throws Exception {
        Path output = temporary.resolve("output.log");
        Path events = temporary.resolve("events.log");
        ProcessBuilder builder = new ProcessBuilder(helmfile().toString(), "--file",
                temporary.resolve("helmfile.yaml.gotmpl").toString(), "sync", "--skip-deps")
                .directory(temporary.toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().putAll(java.util.Map.of(
                "PATH", temporary.resolve("bin") + ":/usr/bin:/bin",
                "SHARDSHOP_ROOT", temporary.toString(), "SHARDSHOP_SNAPSHOT", temporary.toString(),
                "SHARDSHOP_FRESH", "false", "FIXTURE_LOG", events.toString(),
                "FAIL_RELEASE", failedRelease, "FAIL_GATE", Integer.toString(failedGate),
                "FAIL_DRILL_GATE", Integer.toString(failedDrillGate)));
        Process process = builder.start();
        process.getOutputStream().close();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Helmfile sync exceeded its deadline");
            assertTrue(Files.size(output) <= 1_048_576, "Unexpectedly large Helmfile output");
            assertTrue(Files.size(events) <= 16_384, "Unexpectedly large fixture event log");
            return new Sync(process.exitValue(), Files.readString(output), Files.readAllLines(events));
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    private static Path helmfile() {
        for (String directory : System.getenv("PATH").split(java.io.File.pathSeparator)) {
            Path executable = Path.of(directory).resolve("helmfile");
            if (Files.isExecutable(executable)) return executable.toAbsolutePath();
        }
        throw new AssertionError("helmfile must be installed on PATH, as required by the kind lab");
    }

    private void executable(String name, String script) throws IOException {
        Path path = temporary.resolve("bin").resolve(name);
        Files.writeString(path, script);
        assertTrue(path.toFile().setExecutable(true), "Cannot create executable fixture " + name);
    }

    private record Sync(int exit, String output, List<String> events) { }
}
