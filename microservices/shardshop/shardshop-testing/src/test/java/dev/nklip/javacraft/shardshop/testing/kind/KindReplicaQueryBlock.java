package dev.nklip.javacraft.shardshop.testing.kind;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static dev.nklip.javacraft.shardshop.testing.kind.KindTopology.require;

/** A bounded, named PostgreSQL lock used to reproduce a standby query that never completes. */
public final class KindReplicaQueryBlock implements AutoCloseable {
    private static final Duration COMMAND = Duration.ofSeconds(170);
    private static final Duration STOP = Duration.ofSeconds(15);
    private final KindLab lab;
    private final KindTopology topology;
    private final KindTopology.Shard shard;
    private final KindDrillGuard guard;
    private final String application = "shardshop-replica-block-" + UUID.randomUUID().toString().replace("-", "");
    private final CountDownLatch finished = new CountDownLatch(1);
    private FutureTask<KindLab.CommandResult> task;
    private Thread runner;
    private boolean started;

    public KindReplicaQueryBlock(KindLab lab, KindTopology topology, KindTopology.Shard shard, KindDrillGuard guard) {
        this.lab = lab;
        this.topology = topology;
        this.shard = shard;
        this.guard = guard;
    }

    /** Register recovery before starting the transaction, including when kubectl loses its response. */
    public void start() throws IOException, InterruptedException {
        if (started) throw new IllegalStateException("Replica query block already started");
        for (String instance : List.of(shard.primary(), shard.standbys().get(0), shard.standbys().get(1)))
            require(topology.sql(instance, lockSql("", false)).strip().equals("0"),
                    "catalog.products already has an AccessExclusiveLock on " + instance);
        guard.onClose("terminate replica query block " + application, () -> { close(); return true; });
        task = new FutureTask<>(() -> {
            try {
                return lab.kubectlAttempt(COMMAND, "exec", shard.primary(), "-c", "postgres", "--",
                        "env", "PGAPPNAME=" + application,
                        "PGOPTIONS=-c statement_timeout=160000 -c lock_timeout=5000",
                        "psql", "-X", "-qAt", "-v", "ON_ERROR_STOP=1", "-U", "postgres", "-d", "shardshop",
                        "-c", "BEGIN; LOCK TABLE catalog.products IN ACCESS EXCLUSIVE MODE; SELECT pg_sleep(150); ROLLBACK;");
            } finally { finished.countDown(); }
        });
        runner = Thread.ofVirtual().unstarted(task);
        started = true;
        runner.start();
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        awaitLock(shard.primary(), true, deadline);
        awaitLock(shard.standbys().get(0), false, deadline);
        awaitLock(shard.standbys().get(1), false, deadline);
    }

    static String lockSql(String application, boolean primary) {
        return "SELECT count(*) FROM pg_locks l JOIN pg_class c ON c.oid=l.relation "
                + "WHERE c.oid='catalog.products'::regclass AND l.mode='AccessExclusiveLock' "
                + "AND l.granted AND l.database=(SELECT oid FROM pg_database WHERE datname=current_database())"
                + (primary ? " AND l.pid IN (SELECT pid FROM pg_stat_activity WHERE application_name='" + application + "')" : "");
    }

    private void awaitLock(String instance, boolean primary, long deadline) throws IOException, InterruptedException {
        for (;;) {
            if (task.isDone()) throw new IOException("Replica query block exited before its lock was observed");
            if (topology.sql(instance, lockSql(application, primary)).strip().equals("1")) return;
            if (System.nanoTime() >= deadline) throw new IOException("Timed out waiting for replica query lock on " + instance);
            Thread.sleep(250);
        }
    }

    @Override public void close() throws IOException, InterruptedException {
        if (!started) return;
        try {
            topology.sql(shard.primary(), "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name='" + application + "'");
        } finally {
            runner.interrupt();
            boolean interrupted = Thread.interrupted();
            try { require(finished.await(STOP.toSeconds(), TimeUnit.SECONDS), "Replica query block did not stop"); }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
        // The backend may not have appeared when the first terminate query ran; repeat once after join.
        topology.sql(shard.primary(), "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name='" + application + "'");
        for (String instance : List.of(shard.primary(), shard.standbys().get(0), shard.standbys().get(1)))
            topology.awaitSql(instance, lockSql(application, instance.equals(shard.primary())), "0");
        started = false;
    }
}
