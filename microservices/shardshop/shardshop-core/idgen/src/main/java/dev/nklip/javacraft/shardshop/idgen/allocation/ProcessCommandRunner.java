package dev.nklip.javacraft.shardshop.idgen.allocation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/** Runs one bounded kubectl process without a shell or unbounded output capture. */
final class ProcessCommandRunner implements CommandRunner {
    private static final int MAX_OUTPUT_BYTES = 4096;

    private final ProcessStarter starter;
    private final OutputReader reader;
    private final LongSupplier nanoClock;

    ProcessCommandRunner() {
        this(command -> new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start(),
                task -> {
                    FutureTask<byte[]> future = new FutureTask<>(task);
                    Thread.startVirtualThread(future);
                    return future;
                }, System::nanoTime);
    }

    ProcessCommandRunner(ProcessStarter starter, OutputReader reader, LongSupplier nanoClock) {
        this.starter = Objects.requireNonNull(starter, "starter");
        this.reader = Objects.requireNonNull(reader, "reader");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    @Override
    public CommandResult run(List<String> command, Duration timeout) throws IOException, InterruptedException {
        long budget = timeoutNanos(timeout);
        long started = nanoClock.getAsLong();
        Process process;
        try {
            process = starter.start(List.copyOf(command));
        } catch (IOException | SecurityException failure) {
            throw new AllocationFailure(AllocationFailure.Reason.KUBECTL_START);
        }
        Future<byte[]> output = reader.start(() -> {
            try (InputStream stream = process.getInputStream()) {
                return stream.readNBytes(MAX_OUTPUT_BYTES + 1);
            }
        });
        try {
            if (!process.waitFor(remaining(started, budget), TimeUnit.NANOSECONDS)) {
                throw new IOException("kubectl process timed out");
            }
            byte[] bytes = output.get(remaining(started, budget), TimeUnit.NANOSECONDS);
            if (bytes.length > MAX_OUTPUT_BYTES) {
                throw new IOException("kubectl output exceeds the limit");
            }
            return new CommandResult(process.exitValue(), new String(bytes, StandardCharsets.UTF_8));
        } catch (ExecutionException | TimeoutException failure) {
            throw new IOException("kubectl output could not be read within the deadline");
        } finally {
            output.cancel(true);
            process.destroyForcibly();
        }
    }

    private long remaining(long started, long budget) throws IOException {
        long remaining = budget - (nanoClock.getAsLong() - started);
        if (remaining <= 0) {
            throw new IOException("kubectl process deadline expired");
        }
        return remaining;
    }

    static long timeoutNanos(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        try {
            long nanos = timeout.toNanos();
            if (nanos <= 0) {
                throw new IllegalArgumentException("kubectl timeout must be positive");
            }
            return nanos;
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("kubectl timeout is too large");
        }
    }

    @FunctionalInterface
    interface ProcessStarter {
        Process start(List<String> command) throws IOException;
    }

    @FunctionalInterface
    interface OutputReader {
        Future<byte[]> start(Callable<byte[]> task);
    }
}
