package dev.nklip.javacraft.shardshop.order.config;

import dev.nklip.javacraft.shardshop.order.stock.StockCoordinator;
import dev.nklip.javacraft.shardshop.sharding.Shard;
import dev.nklip.javacraft.shardshop.sharding.ShardTopology;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Bounded, non-overlapping shard polling outside the HTTP event loop. */
public final class StockWorker implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(StockWorker.class.getName());
    private final StockCoordinator coordinator;
    private final ShardTopology topology;
    private final Settings settings;
    private final Set<String> unavailable = ConcurrentHashMap.newKeySet();
    private ScheduledExecutorService executor;
    private boolean closed;

    public record Settings(boolean enabled, Duration interval, Duration lease, int batchSize, int shardConcurrency) {
        public Settings {
            if (interval == null || interval.compareTo(Duration.ofMillis(100)) < 0
                    || interval.compareTo(Duration.ofSeconds(30)) > 0) {
                throw new IllegalArgumentException("Stock poll interval must be between 100ms and 30s");
            }
            if (lease == null || lease.compareTo(Duration.ofSeconds(10)) < 0
                    || lease.compareTo(Duration.ofMinutes(5)) > 0) {
                throw new IllegalArgumentException("Stock lease must be between 10s and 5m");
            }
            if (batchSize < 1 || batchSize > 100) {
                throw new IllegalArgumentException("Stock batch size must be between 1 and 100");
            }
            if (shardConcurrency < 1 || shardConcurrency > 32) {
                throw new IllegalArgumentException("Stock shard concurrency must be between 1 and 32");
            }
        }
    }

    public StockWorker(StockCoordinator coordinator, ShardTopology topology, Settings settings) {
        this.coordinator = coordinator;
        this.topology = topology;
        this.settings = settings;
    }

    public synchronized void start() {
        if (!settings.enabled() || executor != null || closed) {
            return;
        }
        executor = Executors.newScheduledThreadPool(Math.min(settings.shardConcurrency(), topology.shards().size()),
                Thread.ofPlatform().daemon().name("order-stock-worker-", 0).factory());
        if (settings.shardConcurrency() == 1) {
            // Preserve the original global scan and delay as an operational rollback baseline.
            executor.scheduleWithFixedDelay(this::poll, 0, settings.interval().toMillis(), TimeUnit.MILLISECONDS);
        } else {
            for (var shard : topology.shards()) {
                // A periodic task never overlaps itself, even when other shard lanes are still busy.
                executor.scheduleWithFixedDelay(() -> pollShard(shard), 0,
                        settings.interval().toMillis(), TimeUnit.MILLISECONDS);
            }
        }
    }

    void poll() {
        for (var shard : topology.shards()) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            pollShard(shard);
        }
    }

    void pollShard(Shard shard) {
        try {
            for (int attempt = 0; attempt < settings.batchSize(); attempt++) {
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                var progress = coordinator.processNext(shard);
                if (progress != StockCoordinator.Progress.ADVANCED) {
                    break;
                }
            }
            if (unavailable.remove(shard.clusterName())) {
                LOG.log(System.Logger.Level.INFO, "Stock coordination resumed for {0}", shard.clusterName());
            }
        } catch (RuntimeException failure) {
            // Log the affected route once, without JDBC messages, credentials or buyer payloads.
            if (unavailable.add(shard.clusterName())) {
                LOG.log(System.Logger.Level.WARNING,
                        "Stock coordination unavailable for {0}; pending work will retry after recovery",
                        shard.clusterName());
            }
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (executor != null) {
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    LOG.log(System.Logger.Level.WARNING, "Stock worker did not stop within five seconds");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
