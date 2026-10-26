package dev.nklip.javacraft.shardshop.testing.kind;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Bounded native backup transfers; database contents and diagnostics never enter exception messages. */
final class BackupProcesses {
    private static final int STDERR_LIMIT = 64 * 1024;

    private BackupProcesses() { }

    static void transfer(ProcessBuilder builder, Path input, Path output, Duration timeout)
            throws IOException, InterruptedException {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IOException("Backup process timeout must be positive");
        }
        if (input != null) BackupArtifacts.requirePrivateFile(input);
        if (output != null) BackupArtifacts.requirePrivateFile(output);
        if (input != null && output != null && Files.isSameFile(input, output)) {
            throw new IOException("Backup input and output must be distinct files");
        }
        // Keep all three streams under this method's control, regardless of caller defaults.
        builder.redirectErrorStream(false).redirectError(ProcessBuilder.Redirect.PIPE)
                .redirectOutput(ProcessBuilder.Redirect.PIPE).redirectInput(ProcessBuilder.Redirect.PIPE);
        Process process;
        try {
            process = builder.start();
        } catch (IOException failure) {
            throw new IOException("Backup process could not start");
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        var workers = Executors.newVirtualThreadPerTaskExecutor();
        Future<?> stdin = workers.submit(() -> {
            try (var destination = process.getOutputStream()) {
                if (input != null) {
                    try (var source = Files.newInputStream(input, LinkOption.NOFOLLOW_LINKS)) {
                        source.transferTo(destination);
                    }
                }
            }
            return null;
        });
        Future<?> stdout = workers.submit(() -> {
            try (var source = process.getInputStream();
                 var destination = output == null ? OutputStream.nullOutputStream()
                         : Files.newOutputStream(output, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING,
                                 LinkOption.NOFOLLOW_LINKS)) {
                source.transferTo(destination);
            }
            return null;
        });
        Future<Boolean> stderr = workers.submit(() -> hasDiagnostics(process.getErrorStream()));
        try {
            if (!process.waitFor(remaining(deadline), TimeUnit.NANOSECONDS)) {
                throw new TimeoutException();
            }
            stdin.get(remaining(deadline), TimeUnit.NANOSECONDS);
            stdout.get(remaining(deadline), TimeUnit.NANOSECONDS);
            boolean diagnostics = stderr.get(remaining(deadline), TimeUnit.NANOSECONDS);
            if (process.exitValue() != 0 || diagnostics) throw new IOException("Backup process failed or emitted diagnostics");
        } catch (TimeoutException failure) {
            terminate(process);
            throw new IOException("Backup process exceeded its timeout");
        } catch (ExecutionException failure) {
            terminate(process);
            throw new IOException("Backup process stream transfer failed");
        } catch (InterruptedException failure) {
            terminate(process);
            Thread.currentThread().interrupt();
            throw failure;
        } finally {
            if (process.isAlive()) terminate(process);
            stdin.cancel(true);
            stdout.cancel(true);
            stderr.cancel(true);
            workers.shutdownNow();
            close(process.getOutputStream());
            close(process.getInputStream());
            close(process.getErrorStream());
        }
    }

    private static boolean hasDiagnostics(InputStream source) throws IOException {
        try (source; var captured = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            boolean exceeded = false;
            for (int count; (count = source.read(buffer)) != -1;) {
                int available = STDERR_LIMIT - captured.size();
                captured.write(buffer, 0, Math.min(count, available));
                if (count > available) exceeded = true;
            }
            return exceeded || !captured.toString(StandardCharsets.UTF_8).isBlank();
        }
    }

    private static long remaining(long deadline) throws TimeoutException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new TimeoutException();
        return remaining;
    }

    private static void terminate(Process process) {
        List<ProcessHandle> descendants = process.descendants().toList();
        descendants.reversed().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void close(AutoCloseable stream) {
        try {
            stream.close();
        } catch (Exception ignored) {
            // Failure cleanup must preserve the original safe exception.
        }
    }
}
