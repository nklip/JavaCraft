package dev.nklip.javacraft.shardshop.testing.kind;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class BackupProcessesTest {
    @TempDir Path temporary;

    @Test
    void streamsDumpLargerThanCommandCaptureLimitInBothDirections() throws Exception {
        BackupArtifacts bundle = bundle();
        Path input = bundle.newFile("input.dump");
        byte[] block = new byte[64 * 1024];
        for (int i = 0; i < block.length; i++) block[i] = (byte) i;
        try (var stream = Files.newOutputStream(input)) {
            for (int i = 0; i < 100; i++) stream.write(block);
        }
        Path output = bundle.newFile("output.dump");
        BackupProcesses.transfer(shell("cat"), input, output, Duration.ofSeconds(10));
        assertEquals(100L * block.length, Files.size(output));
        assertEquals(-1, Files.mismatch(input, output));
    }

    @Test
    void rejectsNonzeroWarningsAndExcessiveStderrWithoutLeakingDetails() throws Exception {
        for (String command : new String[]{"printf secret-diagnostic >&2", "printf secret-output; exit 9",
                "yes secret-diagnostic | head -c 100000 >&2"}) {
            IOException failure = assertThrows(IOException.class,
                    () -> BackupProcesses.transfer(shell(command), null, null, Duration.ofSeconds(5)));
            assertFalse(failure.toString().contains("secret"));
            assertNull(failure.getCause());
        }
    }

    @Test
    void timeoutKillsNativeProcessAndRetainsPartialArtifact() throws Exception {
        BackupArtifacts bundle = bundle();
        Path output = bundle.newFile("partial.dump");
        Path pid = temporary.resolve("pid");
        ProcessBuilder process = shell("printf partial; echo $$ > \"$PID_FILE\"; sleep 30 & wait");
        process.environment().put("PID_FILE", pid.toString());
        IOException failure = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertThrows(IOException.class,
                () -> BackupProcesses.transfer(process, null, output, Duration.ofMillis(300))));
        assertTrue(failure.getMessage().contains("timeout"));
        assertEquals("partial", Files.readString(output));
        long nativePid = Long.parseLong(Files.readString(pid).strip());
        assertTrue(ProcessHandle.of(nativePid).isEmpty() || !ProcessHandle.of(nativePid).orElseThrow().isAlive());
        assertFalse(Files.exists(bundle.directory().resolve("manifest.json")));
    }

    @Test
    void interruptionTerminatesTransferAndRestoresInterruptStatus() throws Exception {
        Path pid = temporary.resolve("pid");
        ProcessBuilder process = shell("echo $$ > \"$PID_FILE\"; sleep 30 & wait");
        process.environment().put("PID_FILE", pid.toString());
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        Thread worker = Thread.ofPlatform().start(() -> {
            try {
                BackupProcesses.transfer(process, null, null, Duration.ofSeconds(30));
            } catch (InterruptedException expected) {
                interrupted.set(Thread.currentThread().isInterrupted());
            } catch (Throwable failure) {
                unexpected.set(failure);
            }
        });
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                while (!Files.exists(pid)) Thread.onSpinWait();
                worker.interrupt();
                worker.join();
            });
            assertNull(unexpected.get());
            assertTrue(interrupted.get());
            long nativePid = Long.parseLong(Files.readString(pid).strip());
            assertTrue(ProcessHandle.of(nativePid).isEmpty() || !ProcessHandle.of(nativePid).orElseThrow().isAlive());
        } finally {
            worker.interrupt();
            worker.join(1000);
        }
    }

    private static ProcessBuilder shell(String script) {
        return new ProcessBuilder("/bin/sh", "-c", script);
    }

    private BackupArtifacts bundle() throws IOException {
        return BackupArtifacts.create(temporary.toRealPath().resolve("bundle"));
    }
}
