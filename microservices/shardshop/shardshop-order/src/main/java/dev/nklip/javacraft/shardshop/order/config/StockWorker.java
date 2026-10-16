package dev.nklip.javacraft.shardshop.order.config;

import dev.nklip.javacraft.shardshop.order.stock.StockCoordinator;
import dev.nklip.javacraft.shardshop.sharding.ShardTopology;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Bounded background polling on a dedicated thread; no database work runs on the HTTP event loop. */
public final class StockWorker implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(StockWorker.class.getName());
    private final StockCoordinator coordinator;
    private final ShardTopology topology;
    private final Settings settings;
    private final Set<String> unavailable = new HashSet<>();
    private ScheduledExecutorService executor;

    public record Settings(boolean enabled, Duration interval, Duration lease, int batchSize) {
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
        }
    }

    public StockWorker(StockCoordinator coordinator, ShardTopology topology, Settings settings) {
        this.coordinator = coordinator;
        this.topology = topology;
        this.settings = settings;
    }

    public synchronized void start() {
        if (!settings.enabled() || executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon()
                .name("order-stock-worker").factory());
        executor.scheduleWithFixedDelay(this::poll, 0, settings.interval().toMillis(), TimeUnit.MILLISECONDS);
    }

    void poll() {
        for (var shard : topology.shards()) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            try {
                for (int attempt = 0; attempt < settings.batchSize(); attempt++) {
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
    }

    @Override
    public synchronized void close() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }
}
