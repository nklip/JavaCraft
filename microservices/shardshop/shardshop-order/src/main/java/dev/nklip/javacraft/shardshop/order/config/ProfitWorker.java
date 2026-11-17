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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

/** Recovers confirmed margin intents after process restarts and unknown seller commits. */
public final class ProfitWorker implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(ProfitWorker.class.getName());
    private final ProfitStore store;
    private final ShardRouter router;
    private final ProfitCoordinator coordinator;
    private final ShardTopology topology;
    private final Settings settings;
    private final IntFunction<ScheduledExecutorService> schedulerFactory;
    /** Shards with a logged failure; a later completed intent on such a shard logs that posting resumed. */
    private final Set<String> unavailable = ConcurrentHashMap.newKeySet();
    private ScheduledExecutorService executor;
    /** Runs the credit groups of the lanes; absent in the serial mode and with one credit at a time. */
    private ExecutorService items;
    private volatile boolean closed;

    /**
     * In lane mode, {@code itemConcurrency} groups of one batch post at the same time. The credits of one seller stay
     * in one group, in their order, thus two groups never wait for the same balance row.
     */
    public record Settings(boolean enabled, Duration interval, int batchSize, int shardConcurrency, int itemConcurrency) {
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
            if (itemConcurrency < 1 || itemConcurrency > 16) {
                throw new IllegalArgumentException("Profit item concurrency must be between 1 and 16");
            }
        }

        /** One credit at a time in each lane. */
        public Settings(boolean enabled, Duration interval, int batchSize, int shardConcurrency) {
            this(enabled, interval, batchSize, shardConcurrency, 1);
        }
    }

    public ProfitWorker(ProfitStore store, ShardRouter router, ShardTopology topology, Settings settings) {
        this(store, router, topology, settings, lanes -> Executors.newScheduledThreadPool(lanes,
                Thread.ofPlatform().daemon().name("order-profit-worker-", 0).factory()));
    }

    ProfitWorker(ProfitStore store, ShardRouter router, ShardTopology topology, Settings settings,
                 IntFunction<ScheduledExecutorService> schedulerFactory) {
        this.store = store;
        this.router = router;
        this.coordinator = new ProfitCoordinator(store, router);
        this.topology = topology;
        this.settings = settings;
        this.schedulerFactory = schedulerFactory;
    }

    public synchronized void start() {
        if (!settings.enabled() || executor != null || closed) {
            return;
        }
        int lanes = Math.min(settings.shardConcurrency(), topology.shards().size());
        executor = schedulerFactory.apply(lanes);
        if (settings.shardConcurrency() > 1 && settings.itemConcurrency() > 1) {
            items = Executors.newFixedThreadPool(lanes * settings.itemConcurrency(),
                    Thread.ofPlatform().daemon().name("order-profit-item-", 0).factory());
        }
        for (var home : topology.shards()) {
            executor.schedule(new Lane(home), 0, TimeUnit.MILLISECONDS);
        }
    }

    /** One batch per turn: requeue productive lanes behind other ready shards instead of draining inline. */
    private final class Lane implements Runnable {
        private final Shard home;

        private Lane(Shard home) {
            this.home = home;
        }

        @Override
        public synchronized void run() {
            if (closed || Thread.currentThread().isInterrupted()) {
                return;
            }
            boolean full = false;
            try {
                full = pollShard(home);
            } finally {
                if (!closed && !Thread.currentThread().isInterrupted()) {
                    try {
                        executor.schedule(this, full ? 0 : settings.interval().toMillis(), TimeUnit.MILLISECONDS);
                    } catch (RejectedExecutionException stopped) {
                        if (!closed) {
                            throw stopped;
                        }
                    }
                }
            }
        }
    }

    boolean pollShard(Shard home) {
        if (Thread.currentThread().isInterrupted()) {
            return false;
        }
        List<PendingCredit> pending;
        try {
            pending = store.pendingProfit(home, settings.batchSize());
        } catch (RuntimeException failure) {
            unavailable(home, "polling", failure);
            return false;
        }
        ExecutorService parallel = items;
        List<List<PendingCredit>> groups = groups(pending, parallel == null ? 1 : settings.itemConcurrency());
        boolean applied;
        if (groups.size() < 2) {
            applied = post(home, pending);
        } else {
            // Step 7.9: one credit at a time capped a home lane at approximately 26 credits/s on EKS (35 ms each).
            List<Future<Boolean>> running = new ArrayList<>();
            try {
                for (var group : groups) {
                    running.add(parallel.submit(() -> post(home, group)));
                }
                applied = true;
                for (var group : running) {
                    // Join every group even when an earlier group could not advance.
                    applied &= group.get();
                }
            } catch (InterruptedException interrupted) {
                running.forEach(group -> group.cancel(true));
                Thread.currentThread().interrupt();
                return false;
            } catch (ExecutionException | RejectedExecutionException failure) {
                running.forEach(group -> group.cancel(true));
                unavailable(home, "posting", failure);
                return false;
            }
        }
        return applied && pending.size() == settings.batchSize() && !Thread.currentThread().isInterrupted();
    }

    private boolean post(Shard home, List<PendingCredit> credits) {
        boolean applied = true;
        for (var credit : credits) {
            if (Thread.currentThread().isInterrupted()) {
                return false;
            }
            try {
                Progress progress = coordinator.process(home, credit);
                applied &= progress.status() == Progress.Status.APPLIED;
                report(home, credit, progress);
            } catch (RuntimeException failure) {
                // The home shard could not save the retry time or the block, thus it failed as well.
                // An unavailable seller must not stop other sellers or home shards.
                applied = false;
                unavailable(home, "posting", failure);
            }
        }
        return applied;
    }

    /** The credits of one seller stay in one group, in their batch order; empty groups are left out. */
    static List<List<PendingCredit>> groups(List<PendingCredit> pending, int count) {
        List<List<PendingCredit>> groups = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            groups.add(new ArrayList<>());
        }
        for (var credit : pending) {
            groups.get(Math.floorMod(Long.hashCode(credit.sellerId()), count)).add(credit);
        }
        groups.removeIf(List::isEmpty);
        return groups;
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
        if (items != null) {
            // The lanes have cancelled their groups; this also interrupts a group that is still posting.
            items.shutdownNow();
            try {
                if (!items.awaitTermination(5, TimeUnit.SECONDS)) {
                    LOG.log(System.Logger.Level.WARNING, "Profit credit groups did not stop within five seconds");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
