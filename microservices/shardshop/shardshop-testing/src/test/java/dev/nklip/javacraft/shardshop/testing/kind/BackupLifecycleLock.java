package dev.nklip.javacraft.shardshop.testing.kind;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;

/** Uses the lifecycle scripts' mkdir lock; shutdown waits for source guard recovery. */
final class BackupLifecycleLock implements AutoCloseable {
    private final Path path;
    private final Object identity;
    private final Thread shutdown;
    private volatile Thread active;
    private boolean closed;
    @FunctionalInterface interface Work<T> { T run() throws Exception; }

    BackupLifecycleLock(Path temporaryDirectory) throws IOException {
        path = temporaryDirectory.resolve("shardshop-up.lock");
        Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        identity = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
        shutdown = new Thread(() -> {
            Thread running = active;
            if (running != null) running.interrupt();
            synchronized (this) {
                try { close(); }
                catch (IOException failure) { System.err.println("Backup lifecycle lock requires manual recovery"); }
            }
        }, "shardshop-backup-lifecycle-recovery");
        Runtime.getRuntime().addShutdownHook(shutdown);
    }

    synchronized <T> T run(Work<T> work) throws Exception {
        if (closed || active != null) throw new IllegalStateException("Backup lifecycle lock is unavailable");
        active = Thread.currentThread();
        try { return work.run(); }
        finally { active = null; }
    }

    @Override public synchronized void close() throws IOException {
        if (closed) return;
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                || !Objects.equals(identity, Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey()))
            throw new IOException("Backup lifecycle lock identity changed");
        Files.delete(path); // Never recursively deletes a foreign or populated directory.
        closed = true;
        if (Thread.currentThread() != shutdown) {
            try { Runtime.getRuntime().removeShutdownHook(shutdown); }
            catch (IllegalStateException ignored) { /* The running hook owns shutdown. */ }
        }
    }
}
