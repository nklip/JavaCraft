package dev.nklip.javacraft.shardshop.order.config;

import dev.nklip.javacraft.shardshop.common.Failures;
import dev.nklip.javacraft.shardshop.order.profit.ProfitCoordinator;
import dev.nklip.javacraft.shardshop.order.profit.ProfitCoordinator.Progress;
import dev.nklip.javacraft.shardshop.order.profit.ProfitStore;
import dev.nklip.javacraft.shardshop.order.profit.ProfitStore.PendingCredit;
import dev.nklip.javacraft.shardshop.sharding.Shard;
import dev.nklip.javacraft.shardshop.sharding.ShardRouter;
import dev.nklip.javacraft.shardshop.sharding.ShardTopology;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Recovers confirmed margin intents after process restarts and unknown seller commits. */
public final class ProfitWorker implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(ProfitWorker.class.getName());
    private final ProfitStore store;
    private final ShardRouter router;
    private final ProfitCoordinator coordinator;
    private final ShardTopology topology;
    private final Settings settings;
    /** Shards with a logged failure; a later completed intent on such a shard logs that posting resumed. */
    private final Set<String> unavailable = ConcurrentHashMap.newKeySet();
    private ScheduledExecutorService executor;
    private volatile boolean closed;

    public record Settings(boolean enabled, Duration interval, int batchSize, int shardConcurrency) {
        public Settings {
            if (interval == null || interval.compareTo(Duration.ofMillis(100)) < 0
                    || interval.compareTo(Duration.ofSeconds(30)) > 0) {
                throw new IllegalArgumentException("Profit poll interval must be between 100ms and 30s");
            }
            if (batchSize < 1 || batchSize > 100) {
                throw new IllegalArgumentException("Profit batch size must be between 1 and 100");
            }
            if (shardConcurrency < 1 || shardConcurrency > 32) {
                throw new IllegalArgumentException("Profit shard concurrency must be between 1 and 32");
            }
        }
    }

    public ProfitWorker(ProfitStore store, ShardRouter router, ShardTopology topology, Settings settings) {
        this.store = store;
        this.router = router;
        this.coordinator = new ProfitCoordinator(store, router);
        this.topology = topology;
        this.settings = settings;
    }

    public synchronized void start() {
        if (!settings.enabled() || executor != null || closed) {
            return;
        }
        executor = Executors.newScheduledThreadPool(Math.min(settings.shardConcurrency(), topology.shards().size()),
                Thread.ofPlatform().daemon().name("order-profit-worker-", 0).factory());
        if (settings.shardConcurrency() == 1) {
            // Preserve the original global scan and delay as an operational rollback baseline.
            executor.scheduleWithFixedDelay(this::poll, 0, settings.interval().toMillis(), TimeUnit.MILLISECONDS);
        } else {
            for (var home : topology.shards()) {
                // A periodic task never overlaps itself, even when other home lanes are still busy.
                executor.scheduleWithFixedDelay(() -> pollShard(home), 0,
                        settings.interval().toMillis(), TimeUnit.MILLISECONDS);
            }
        }
    }

    void poll() {
        for (var home : topology.shards()) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            pollShard(home);
        }
    }

    void pollShard(Shard home) {
        if (Thread.currentThread().isInterrupted()) {
            return;
        }
        List<PendingCredit> pending;
        try {
            pending = store.pendingProfit(home, settings.batchSize());
        } catch (RuntimeException failure) {
            unavailable(home, "polling", failure);
            return;
        }
        for (var credit : pending) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            try {
                report(home, credit, coordinator.process(home, credit));
            } catch (RuntimeException failure) {
                // The home shard could not save the retry time or the block, thus it failed as well.
                // An unavailable seller must not stop other sellers or home shards.
                unavailable(home, "posting", failure);
            }
        }
    }

    private void report(Shard home, PendingCredit credit, Progress progress) {
        switch (progress.status()) {
            case APPLIED -> {
                resumed(home);
                resumed(router.route(credit.sellerId()));
            }
            case DEFERRED -> unavailable(progress.unavailable(), "posting", progress.failure());
            // Concatenation, not a format pattern: a pattern would print the IDs with digit grouping.
            case BLOCKED -> LOG.log(System.Logger.Level.ERROR, () -> "Profit intent blocked for order "
                    + credit.orderId() + ", item " + credit.itemPosition() + " on " + home.clusterName() + ": "
                    + progress.failure().reason() + "; investigate the durable intent");
        }
    }

    private void unavailable(Shard shard, String work, Throwable failure) {
        if (closed && Thread.currentThread().isInterrupted()) {
            // DatabasePhases preserves interruption and wraps its cause when shutdown cancels a phase.
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof InterruptedException) {
                    return;
                }
            }
        }
        if (unavailable.add(shard.clusterName())) {
            LOG.log(System.Logger.Level.WARNING, () -> "Profit " + work + " unavailable for " + shard.clusterName()
                    + "; durable intents will retry: " + Failures.describe(failure));
        }
    }

    private void resumed(Shard shard) {
        if (unavailable.remove(shard.clusterName())) {
            LOG.log(System.Logger.Level.INFO, () -> "Profit posting resumed for " + shard.clusterName());
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (executor != null) {
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    LOG.log(System.Logger.Level.WARNING, "Profit worker did not stop within five seconds");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
