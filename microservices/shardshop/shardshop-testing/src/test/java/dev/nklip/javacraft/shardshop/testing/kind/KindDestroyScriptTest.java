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

import static org.junit.jupiter.api.Assertions.*;

/** Exercise the real destructive entry point against isolated command and storage fixtures. */
class KindDestroyScriptTest {
    private static final String OWNED_CONFIG = """
            {"contexts":[{"name":"kind-shardshop","context":{"cluster":"kind-shardshop","user":"kind-shardshop"}},
              {"name":"other","context":{"cluster":"other","user":"other"}}],
             "clusters":[{"name":"kind-shardshop"},{"name":"other"}],
             "users":[{"name":"kind-shardshop"},{"name":"other"}],"current-context":"kind-shardshop"}
            """;
    private static final String OTHER_CONFIG = """
            {"contexts":[{"name":"other","context":{"cluster":"other","user":"other"}}],
             "clusters":[{"name":"other"}],"users":[{"name":"other"}],"current-context":""}
            """;
    @TempDir Path temporary;

    @BeforeEach
    void installControlledCommands() throws IOException {
        Files.createDirectories(temporary.resolve("bin"));
        Files.createDirectories(temporary.resolve("tmp"));
        Files.writeString(temporary.resolve("events"), "");
        Files.writeString(temporary.resolve("kubeconfig"), OWNED_CONFIG);
        Files.writeString(temporary.resolve("other-config"), OTHER_CONFIG);
        Files.writeString(temporary.resolve("nodes"), "node-a\nnode-b\n");
        Files.writeString(temporary.resolve("volumes"), "anonymous-owned\nnamed-owned\nunrelated-volume\n");
        Files.writeString(temporary.resolve("inspect.json"), """
                [{"Id":"node-a","Config":{"Labels":{"io.x-k8s.kind.cluster":"shardshop"}},
                  "Mounts":[{"Type":"volume","Name":"anonymous-owned"},
                    {"Type":"bind","Source":"/lib/modules","Destination":"/lib/modules","RW":false}]},
                 {"Id":"node-b","Config":{"Labels":{"io.x-k8s.kind.cluster":"shardshop"}},
                  "Mounts":[{"Type":"volume","Name":"named-owned"}]}]
                """);
        executable("docker", """
                #!/bin/bash
                set -eu
                printf 'docker %s\n' "$*" >> "$FIXTURE/events"
                case "$*" in
                  'info --format {{.ID}}') printf 'fixture-daemon\n';;
                  'ps -a --no-trunc --filter label=io.x-k8s.kind.cluster=shardshop --format {{.ID}}'|'ps -a --filter label=io.x-k8s.kind.cluster=shardshop --format {{.ID}}') cat "$FIXTURE/nodes";;
                  'inspect node-a node-b') cat "$FIXTURE/inspect.json";;
                  'ps -a --no-trunc --filter volume=anonymous-owned --format {{.ID}}')
                    grep -Fx node-a "$FIXTURE/nodes" || :
                    [ "$MODE" != shared ] || printf 'unrelated-container\n';;
                  'ps -a --no-trunc --filter volume=named-owned --format {{.ID}}')
                    grep -Fx node-b "$FIXTURE/nodes" || :
                    [ "$MODE" != reattached ] || printf 'unrelated-container\n';;
                  'volume ls --format {{.Name}}') cat "$FIXTURE/volumes";;
                  'volume rm named-owned')
                    [ "$MODE" != volume-failure ] || exit 29
                    if [ "$MODE" != remaining-volume ]; then
                      grep -Fxv named-owned "$FIXTURE/volumes" > "$FIXTURE/volumes.next"
                      mv "$FIXTURE/volumes.next" "$FIXTURE/volumes"
                    fi;;
                  *) printf 'Unexpected Docker operation: %s\n' "$*" >&2; exit 99;;
                esac
                """);
        executable("kubectl", """
                #!/bin/bash
                set -eu
                printf 'kubectl %s\n' "$*" >> "$FIXTURE/events"
                [ "$*" = 'config view -o json' ] || exit 99
                cat "$KUBECONFIG"
                """);
        executable("kind", """
                #!/bin/bash
                set -eu
                printf 'kind %s\n' "$*" >> "$FIXTURE/events"
                [ "$KIND_EXPERIMENTAL_PROVIDER" = docker ] || exit 98
                [ "$*" = 'delete cluster --name shardshop' ] || exit 99
                [ "$MODE" != kind-failure ] || exit 23
                [ "$MODE" = remaining-node ] || : > "$FIXTURE/nodes"
                grep -Fxv anonymous-owned "$FIXTURE/volumes" > "$FIXTURE/volumes.next"
                mv "$FIXTURE/volumes.next" "$FIXTURE/volumes"
                [ "$MODE" = remaining-config ] || cp "$FIXTURE/other-config" "$KUBECONFIG"
                """);
    }

    @Test
    void deletesOnlyOwnedContainersVolumesAndKubeconfigAndCanRunAgain() throws Exception {
        Result first = destroy("success");
        assertEquals(0, first.exit(), first.output());
        assertEquals("", Files.readString(temporary.resolve("nodes")));
        assertEquals(List.of("unrelated-volume"), Files.readAllLines(temporary.resolve("volumes")));
        assertEquals(OTHER_CONFIG, Files.readString(temporary.resolve("kubeconfig")));
        assertFalse(Files.exists(inventory()));
        assertTrue(first.events().contains("docker volume rm named-owned"));
        assertFalse(first.events().contains("docker volume rm anonymous-owned"));
        assertEquals(0, destroy("success").exit());
        assertFalse(Files.exists(lock()));
    }

    @Test
    void absentClusterSucceedsAndPreservesUnrelatedResources() throws Exception {
        Files.writeString(temporary.resolve("nodes"), "");
        Files.writeString(temporary.resolve("volumes"), "unrelated-volume\n");
        Files.writeString(temporary.resolve("kubeconfig"), OTHER_CONFIG);
        Result result = destroy("success");
        assertEquals(0, result.exit(), result.output());
        assertEquals("unrelated-volume\n", Files.readString(temporary.resolve("volumes")));
        assertEquals(OTHER_CONFIG, Files.readString(temporary.resolve("kubeconfig")));
    }

    @Test
    void sharedVolumeIsRejectedBeforeAnyDestruction() throws Exception {
        assertPreflightRejection(destroy("shared"), "shared volume");
    }

    @Test
    void hostDataBindIsRejectedBeforeAnyDestruction() throws Exception {
        Path inspection = temporary.resolve("inspect.json");
        Files.writeString(inspection, Files.readString(inspection).replace("/lib/modules", "/private/database"));
        assertPreflightRejection(destroy("success"), "host mounts");
    }

    @Test
    void writableKernelModuleBindIsRejectedBeforeAnyDestruction() throws Exception {
        Path inspection = temporary.resolve("inspect.json");
        Files.writeString(inspection, Files.readString(inspection).replace("\"RW\":false", "\"RW\":true"));
        assertPreflightRejection(destroy("success"), "host mounts");
    }

    @Test
    void unrelatedContextReferencingOwnedCredentialsBlocksDestruction() throws Exception {
        Files.writeString(temporary.resolve("kubeconfig"), OWNED_CONFIG.replace("\"user\":\"other\"", "\"user\":\"kind-shardshop\""));
        assertPreflightRejection(destroy("success"), "Another kubeconfig context");
    }

    @Test
    void kindFailureIsPropagatedAndReleasesLifecycleLock() throws Exception {
        Result result = destroy("kind-failure");
        assertEquals(23, result.exit(), result.output());
        assertEquals("node-a\nnode-b\n", Files.readString(temporary.resolve("nodes")));
        assertFalse(result.events().stream().anyMatch(event -> event.startsWith("docker volume rm")));
        assertFalse(Files.exists(lock()));
        assertEquals(0, destroy("success").exit());
    }

    @Test
    void remainingNodesFailBeforeManualVolumeRemoval() throws Exception {
        Result result = destroy("remaining-node");
        assertNotEquals(0, result.exit(), result.output());
        assertTrue(result.output().contains("node containers remain"), result.output());
        assertFalse(result.events().stream().anyMatch(event -> event.startsWith("docker volume rm")));
        assertFalse(Files.exists(lock()));
    }

    @Test
    void failedVolumeRemovalIsPropagated() throws Exception {
        Result result = destroy("volume-failure");
        assertEquals(29, result.exit(), result.output());
        assertTrue(Files.readAllLines(temporary.resolve("volumes")).contains("named-owned"));
        assertTrue(Files.exists(inventory()));
        assertTrue(Files.readString(inventory()).contains("named-owned"));
        assertFalse(Files.exists(lock()));
        Result retry = destroy("success");
        assertEquals(0, retry.exit(), retry.output());
        assertEquals(List.of("unrelated-volume"), Files.readAllLines(temporary.resolve("volumes")));
        assertFalse(Files.exists(inventory()));
    }

    @Test
    void retryRejectsInventoryFromAnotherDockerDaemonBeforeDestruction() throws Exception {
        Files.writeString(inventory(), "{\"daemonId\":\"another-daemon\",\"volumes\":[\"named-owned\"]}");
        Result result = destroy("success");
        assertPreflightRejection(result, "different Docker daemon");
        assertTrue(Files.exists(inventory()));
        assertEquals(List.of("docker info --format {{.ID}}"), result.events());
    }

    @Test
    void retryRefusesOrphanVolumeNowAttachedToUnrelatedContainer() throws Exception {
        assertEquals(29, destroy("volume-failure").exit());
        assertEquals("", Files.readString(temporary.resolve("nodes")));
        Result retry = destroy("reattached");
        assertNotEquals(0, retry.exit(), retry.output());
        assertTrue(retry.output().contains("shared volume named-owned"), retry.output());
        assertFalse(retry.events().stream().anyMatch(event -> event.startsWith("kind ") || event.startsWith("docker volume rm")));
        assertEquals(List.of("named-owned", "unrelated-volume"), Files.readAllLines(temporary.resolve("volumes")));
        assertTrue(Files.exists(inventory()));
        assertFalse(Files.exists(lock()));
    }

    @Test
    void volumeRemovalMustActuallyRemoveTheVolume() throws Exception {
        Result result = destroy("remaining-volume");
        assertNotEquals(0, result.exit(), result.output());
        assertTrue(result.output().contains("volume named-owned remains"), result.output());
        assertFalse(Files.exists(lock()));
    }

    @Test
    void successfulKindDeletionMustRemoveOwnedKubeconfigEntries() throws Exception {
        Result result = destroy("remaining-config");
        assertNotEquals(0, result.exit(), result.output());
        assertTrue(result.output().contains("kubeconfig entries remain"), result.output());
        assertEquals(OWNED_CONFIG, Files.readString(temporary.resolve("kubeconfig")));
        assertFalse(Files.exists(lock()));
    }

    @Test
    void conflictingLifecycleLockPreventsAllCommandsAndIsPreserved() throws Exception {
        Files.createDirectory(lock());
        Result result = destroy("success");
        assertNotEquals(0, result.exit(), result.output());
        assertTrue(result.output().contains("Another ShardShop lifecycle invocation"), result.output());
        assertTrue(result.events().isEmpty());
        assertTrue(Files.isDirectory(lock()));
        Files.delete(lock());
        assertEquals(0, destroy("success").exit());
    }

    private void assertPreflightRejection(Result result, String message) throws IOException {
        assertNotEquals(0, result.exit(), result.output());
        assertTrue(result.output().contains(message), result.output());
        assertFalse(result.events().stream().anyMatch(event -> event.startsWith("kind ") || event.startsWith("docker volume rm")));
        assertEquals("node-a\nnode-b\n", Files.readString(temporary.resolve("nodes")));
        assertEquals(List.of("anonymous-owned", "named-owned", "unrelated-volume"), Files.readAllLines(temporary.resolve("volumes")));
        assertFalse(Files.exists(lock()));
    }

    private Result destroy(String mode) throws Exception {
        Path source = Path.of(System.getProperty("shardshop.directory", "..")).toAbsolutePath().normalize();
        Path output = temporary.resolve("output");
        Files.writeString(temporary.resolve("events"), "");
        ProcessBuilder builder = new ProcessBuilder("/bin/bash", source.resolve("scripts/cluster-destroy.sh").toString())
                .directory(temporary.toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().putAll(Map.of(
                "PATH", temporary.resolve("bin") + ":" + jq().getParent() + ":/usr/bin:/bin",
                "FIXTURE", temporary.toString(), "MODE", mode,
                "KUBECONFIG", temporary.resolve("kubeconfig").toString(),
                "TMPDIR", temporary.resolve("tmp").toString(),
                "KIND_EXPERIMENTAL_PROVIDER", "podman"));
        Process process = builder.start();
        process.getOutputStream().close();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Cluster destruction exceeded its deadline");
            assertTrue(Files.size(output) <= 1_048_576, "Unexpectedly large destruction output");
            assertTrue(Files.size(temporary.resolve("events")) <= 16_384, "Unexpectedly large fixture event log");
            return new Result(process.exitValue(), Files.readString(output), Files.readAllLines(temporary.resolve("events")));
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    private Path lock() {
        return temporary.resolve("tmp/shardshop-up.lock");
    }

    private Path inventory() {
        return temporary.resolve("tmp/shardshop-destroy.json");
    }

    private static Path jq() {
        for (String directory : System.getenv("PATH").split(java.io.File.pathSeparator)) {
            Path executable = Path.of(directory).resolve("jq");
            if (Files.isExecutable(executable)) return executable.toAbsolutePath();
        }
        throw new AssertionError("jq must be installed on PATH, as required by the kind lab");
    }

    private void executable(String name, String script) throws IOException {
        Path path = temporary.resolve("bin").resolve(name);
        Files.writeString(path, script);
        assertTrue(path.toFile().setExecutable(true), "Cannot create executable fixture " + name);
    }

    private record Result(int exit, String output, List<String> events) { }
}
