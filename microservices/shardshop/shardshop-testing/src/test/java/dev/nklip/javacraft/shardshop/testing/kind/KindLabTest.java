package dev.nklip.javacraft.shardshop.testing.kind;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class KindLabTest {
    @TempDir
    Path snapshot;
    private KindLab lab;

    @BeforeEach
    void prepareSnapshot() throws IOException {
        Files.writeString(snapshot.resolve("inventory.json"), """
                {"data":{"shards":"shard-a\\nshard-b\\n", "instances":"3"}}
                """);
        Files.writeString(snapshot.resolve("kind.json"), "{\"kind\":\"Cluster\"}");
        Files.writeString(snapshot.resolve("infrastructure.yaml"), "kind: Namespace\n");
        lab = new KindLab(snapshot, "shardshop", false);
    }

    @Test
    void readsShardsFromTheInvocationSnapshot() throws IOException {
        assertEquals(List.of("shard-a", "shard-b"), lab.shards());
        assertEquals(List.of("shard-a", "shard-b", "ledger-db"), lab.clusters());
        assertEquals("3", lab.inventory().path("instances").asText());
        assertThrows(IllegalArgumentException.class, () -> lab.json("../outside.json"));
    }

    @Test
    void requiresAllSnapshotInputs() throws IOException {
        Files.delete(snapshot.resolve("kind.json"));
        assertThrows(IllegalArgumentException.class, () -> new KindLab(snapshot, "shardshop", false));
    }

    @Test
    void ownedClusterRequiresAPrivateSingleKubeconfigAndPinsEveryChild() throws Exception {
        Path directory = Files.createDirectory(snapshot.resolve("private"),
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                        java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
        Path config = directory.toRealPath().resolve("config");
        KindLab isolated = new KindLab(snapshot, "shardshop-test-unit", false, config.toString());
        assertEquals(config.toString(), isolated.command("/bin/sh", "-c", "printf %s \"$KUBECONFIG\""));
        assertEquals(config.toString(), isolated.processBuilder("kubectl", "proxy").environment().get("KUBECONFIG"));
        assertThrows(IllegalArgumentException.class, () -> new KindLab(snapshot, "shardshop-test-unit", false));
        for (String name : List.of("other-lab", "shardshop-test-", "shardshop-test-x;echo", "shardshop-test-" + "x".repeat(60))) {
            assertThrows(IllegalArgumentException.class, () -> new KindLab(snapshot, name, false, config.toString()));
        }
        for (String path : List.of("relative", config + ":/tmp/other", config + "\nother")) {
            assertThrows(IllegalArgumentException.class, () -> new KindLab(snapshot, "shardshop-test-unit", false, path));
        }
        Files.writeString(config, "private");
        Files.setPosixFilePermissions(config, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
        assertThrows(IllegalArgumentException.class, () -> new KindLab(snapshot, "shardshop-test-unit", false, config.toString()));
        Files.delete(config);
        Files.createSymbolicLink(config, snapshot.resolve("inventory.json"));
        assertThrows(IllegalArgumentException.class, () -> new KindLab(snapshot, "shardshop-test-unit", false, config.toString()));
    }

    @Test
    void defaultKubeconfigDescendantsAreNeverPrivateTestStorage() throws Exception {
        Path home = Files.createDirectory(snapshot.resolve("home"));
        Path directory = Files.createDirectories(home.resolve(".kube/private"));
        Files.setPosixFilePermissions(directory, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        String previous = System.getProperty("user.home");
        try {
            System.setProperty("user.home", home.toString());
            assertThrows(IllegalArgumentException.class,
                    () -> new KindLab(snapshot, "shardshop-test-unit", false, directory.resolve("config").toString()));
        } finally {
            System.setProperty("user.home", previous);
        }
    }

    @Test
    void understandsEmptySingleAndListKubectlResponses() {
        assertTrue(KindLab.items(KindLab.parse("  ")).isEmpty());
        assertEquals(1, KindLab.items(KindLab.parse("{\"kind\":\"Pod\"}")).size());
        assertEquals(List.of("one", "two"), KindLab.items(KindLab.parse("""
                {"kind":"List","items":[{"name":"one"},{"name":"two"}]}
                """)).stream().map(node -> node.path("name").asText()).toList());
    }

    @Test
    void preservesExitStatusAndSeparateOutputWithoutIncludingSecretsInFailureMessages() throws Exception {
        KindLab.CommandResult result = lab.attempt(fixture("failure", "credential-value"));
        assertEquals(7, result.exitCode());
        assertEquals("standard output", result.stdout());
        assertEquals("credential-value", result.stderr());
        IOException failure = assertThrows(IOException.class,
                () -> lab.command(fixture("failure", "credential-value")));
        assertTrue(failure.getMessage().contains("7"));
        assertFalse(failure.getMessage().contains("credential-value"));
    }

    @Test
    void drainsBothStreamsWithoutDeadlock() throws Exception {
        KindLab.CommandResult result = lab.attempt(fixture("streams"));
        assertEquals(0, result.exitCode());
        assertEquals(128 * 1024, result.stdout().length());
        assertEquals(128 * 1024, result.stderr().length());
    }

    @Test
    void rejectsUnboundedCommandOutput() {
        IOException failure = assertThrows(IOException.class, () -> lab.command(fixture("overflow")));
        assertTrue(failure.getMessage().contains("output"));
    }

    @Test
    void timesOutAndTerminatesChildProcesses() throws Exception {
        Path pid = snapshot.resolve("child.pid");
        long started = System.nanoTime();
        IOException failure = assertThrows(IOException.class,
                () -> lab.command(Duration.ofSeconds(2), fixture("parent", pid.toString())));
        assertTrue(failure.getMessage().contains("exceeded"));
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(6)) < 0);
        long childPid = Long.parseLong(Files.readString(pid));
        lab.await("subprocess termination", Duration.ofSeconds(2),
                () -> ProcessHandle.of(childPid).map(handle -> !handle.isAlive()).orElse(true));
    }

    @Test
    void interruptionTerminatesTheRunningCommand() throws Exception {
        Path pid = snapshot.resolve("process.pid");
        CountDownLatch finished = new CountDownLatch(1);
        FutureTask<Boolean> task = new FutureTask<>(() -> {
            try {
                lab.command(fixture("sleep", pid.toString()));
                return false;
            } catch (InterruptedException expected) {
                return true;
            } finally {
                finished.countDown();
            }
        });
        Thread runner = Thread.ofPlatform().start(task);
        try {
            lab.await("subprocess startup", Duration.ofSeconds(5), () -> Files.isRegularFile(pid));
            runner.interrupt();
            assertTrue(finished.await(3, TimeUnit.SECONDS));
            assertTrue(task.get(1, TimeUnit.SECONDS));
            long processPid = Long.parseLong(Files.readString(pid));
            lab.await("interrupted subprocess termination", Duration.ofSeconds(2),
                    () -> ProcessHandle.of(processPid).map(handle -> !handle.isAlive()).orElse(true));
        } finally {
            runner.interrupt();
        }
    }

    @Test
    void pollingRetriesTransientFailuresAndStopsAtItsDeadline() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        lab.await("eventual readiness", Duration.ofSeconds(2), () -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IOException("temporary failure");
            }
            return true;
        });
        assertEquals(2, attempts.get());
        IOException timeout = assertThrows(IOException.class,
                () -> lab.await("never ready", Duration.ofMillis(20), () -> false));
        assertTrue(timeout.getMessage().contains("never ready"));
    }

    @Test
    void pollingDeadlineInterruptsABlockedProbe() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        assertThrows(IOException.class, () -> lab.await("blocked probe", Duration.ofMillis(100), () -> {
            try {
                Thread.sleep(Duration.ofMinutes(1));
                return false;
            } catch (InterruptedException expected) {
                interrupted.countDown();
                throw expected;
            }
        }));
        assertTrue(interrupted.await(2, TimeUnit.SECONDS));
    }

    private static String[] fixture(String... arguments) {
        return CommandFixture.command(arguments);
    }

    /** A controlled subprocess; no Docker, kubectl, or network services are used by these tests. */
    public static final class CommandFixture {
        private static String[] command(String... arguments) {
            List<String> command = new java.util.ArrayList<>(List.of(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                    Path.of(URI.create(CommandFixture.class.getProtectionDomain().getCodeSource().getLocation()
                            .toString())).toString(), CommandFixture.class.getName()));
            command.addAll(List.of(arguments));
            return command.toArray(String[]::new);
        }

        public static void main(String[] args) throws Exception {
            switch (args[0]) {
                case "failure" -> {
                    System.out.print("standard output");
                    System.err.print(args[1]);
                    System.exit(7);
                }
                case "streams" -> {
                    System.out.print("o".repeat(128 * 1024));
                    System.err.print("e".repeat(128 * 1024));
                }
                case "overflow" -> System.out.print("x".repeat(4 * 1024 * 1024 + 1));
                case "parent" -> {
                    Process child = new ProcessBuilder(command("sleep", args[1])).start();
                    child.waitFor();
                }
                case "sleep" -> {
                    Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
                    Thread.sleep(Duration.ofMinutes(1));
                }
                default -> throw new IllegalArgumentException("Unknown subprocess fixture");
            }
        }
    }
}
