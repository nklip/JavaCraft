package dev.nklip.javacraft.shardshop.testing.kind;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/** Serializes explicit drills and restores their temporary changes on failure or JVM termination. */
public final class KindDrillGuard implements AutoCloseable {
    static final String LOCK = "shardshop-kind-drill";
    private final KindLab lab;
    private final String owner = UUID.randomUUID().toString();
    private final Deque<Recovery> recovery = new ArrayDeque<>();
    private final Thread shutdown;
    private boolean attempted;
    private boolean acquired;
    private boolean closed;
    private volatile boolean stopping;
    private volatile Thread running;

    public KindDrillGuard(KindLab lab) {
        this.lab = lab;
        shutdown = new Thread(() -> {
            try {
                recoverOnShutdown();
            } catch (IOException failure) {
                System.err.println(failure.getMessage());
            }
        }, "shardshop-drill-recovery");
    }

    public synchronized KindDrillGuard acquire() throws IOException, InterruptedException {
        if (attempted || closed) throw new IllegalStateException("Drill guard cannot be reused");
        Path manifest = Files.createTempFile(lab.snapshot(), "drill-lock-", ".json");
        Files.writeString(manifest, """
                {"apiVersion":"v1","kind":"ConfigMap","metadata":{"name":"%s",
                "namespace":"shardshop","labels":{"shardshop.javacraft/drill-owner":"%s"}},
                "data":{"owner":"%s","recovery":"Inspect this drill's report and recover temporary changes before removing this lock."}}
                """.formatted(LOCK, owner, owner));
        Runtime.getRuntime().addShutdownHook(shutdown);
        attempted = true;
        try {
            lab.kubectl("create", "-f", manifest.toString());
            acquired = true;
            return this;
        } catch (IOException | InterruptedException | RuntimeException failure) {
            try { close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        } finally {
            Files.deleteIfExists(manifest);
        }
    }

    /** Register recovery before the mutation, in reverse order of the required restoration. */
    public synchronized void onClose(String description, KindLab.Probe action) {
        if (!acquired || closed) throw new IllegalStateException("Acquire the drill lock before registering recovery");
        recovery.push(new Recovery(description, action));
    }

    @FunctionalInterface
    public interface Drill {
        void run() throws IOException, InterruptedException;
    }

    /** Keep the complete drill on this thread so recovery cannot overtake an in-flight mutation. */
    public synchronized void run(Drill action) throws IOException, InterruptedException {
        if (!acquired || closed || running != null) throw new IllegalStateException("Drill guard is not available");
        running = Thread.currentThread();
        try {
            if (stopping || running.isInterrupted()) throw new InterruptedException("Drill is stopping");
            action.run();
        } finally {
            running = null;
        }
    }

    void recoverOnShutdown() throws IOException {
        stopping = true;
        Thread active = running;
        if (active != null) active.interrupt();
        // The monitor is held by run until its bounded commands have unwound.
        close();
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        boolean interrupted = Thread.interrupted();
        IOException failure = null;
        try {
            while (!recovery.isEmpty()) {
                Recovery next = recovery.pop();
                try {
                    if (!next.action().check()) throw new IOException("Recovery did not complete");
                } catch (Exception | AssertionError problem) {
                    if (problem instanceof InterruptedException) interrupted = true;
                    IOException diagnostic = new IOException("Drill recovery failed: " + next.description(), problem);
                    if (failure == null) failure = diagnostic;
                    else failure.addSuppressed(diagnostic);
                } finally {
                    interrupted |= Thread.interrupted();
                }
            }
            // A timed-out create may have succeeded. The owner label makes this safe even
            // when another process owns the named lock. Keep our lock if restoration failed.
            if (attempted && failure == null) {
                try {
                    lab.kubectl(Duration.ofSeconds(35), "delete", "configmap",
                            "--field-selector=metadata.name=" + LOCK,
                            "-l", "shardshop.javacraft/drill-owner=" + owner,
                            "--ignore-not-found", "--wait=true", "--timeout=20s");
                } catch (IOException | InterruptedException problem) {
                    if (problem instanceof InterruptedException) interrupted = true;
                    failure = new IOException("Cannot release drill lock " + LOCK, problem);
                }
            }
        } finally {
            if (Thread.currentThread() != shutdown && attempted) {
                try { Runtime.getRuntime().removeShutdownHook(shutdown); }
                catch (IllegalStateException ignored) { /* JVM shutdown already owns this hook. */ }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
        if (failure != null) {
            throw new IOException("Drill recovery needs attention; retain " + LOCK
                    + " until the reported temporary changes are recovered: " + failure.getMessage(), failure);
        }
    }

    private record Recovery(String description, KindLab.Probe action) { }
}
