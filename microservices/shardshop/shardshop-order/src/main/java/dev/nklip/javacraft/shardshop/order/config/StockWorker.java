package dev.nklip.javacraft.shardshop.order.config;

import dev.nklip.javacraft.shardshop.order.stock.StockCoordinator;
import dev.nklip.javacraft.shardshop.sharding.Shard;
import dev.nklip.javacraft.shardshop.sharding.ShardTopology;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

/** Bounded buyer-shard batches and concurrent durable saga owners outside the HTTP event loop. */
public final class StockWorker implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(StockWorker.class.getName());
    private final StockCoordinator coordinator;
    private final ShardTopology topology;
    private final Settings settings;
    private final IntFunction<ScheduledExecutorService> schedulerFactory;
    private final Set<String> unavailable = ConcurrentHashMap.newKeySet();
    private ScheduledExecutorService executor;
    private ExecutorService workers;
    private volatile boolean closed;

    public record Settings(boolean enabled, Duration interval, Duration lease, int batchSize, int shardConcurrency,
                           int workersPerShard) {
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
            if (workersPerShard < 1 || workersPerShard > 8) {
                throw new IllegalArgumentException("Stock workers per shard must be between 1 and 8");
            }
            if (shardConcurrency * workersPerShard > 64) {
                throw new IllegalArgumentException("Stock shard concurrency times workers per shard must not exceed 64");
            }
        }

        /** One durable saga owner at a time in each buyer shard. */
        public Settings(boolean enabled, Duration interval, Duration lease, int batchSize, int shardConcurrency) {
            this(enabled, interval, lease, batchSize, shardConcurrency, 1);
        }
    }

    public StockWorker(StockCoordinator coordinator, ShardTopology topology, Settings settings) {
        this(coordinator, topology, settings, lanes -> Executors.newScheduledThreadPool(lanes,
                Thread.ofPlatform().daemon().name("order-stock-worker-", 0).factory()));
    }

    StockWorker(StockCoordinator coordinator, ShardTopology topology, Settings settings,
                IntFunction<ScheduledExecutorService> schedulerFactory) {
        this.coordinator = coordinator;
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
        if (settings.workersPerShard() > 1) {
            int capacity = lanes * Math.min(settings.workersPerShard(), settings.batchSize());
            workers = new ThreadPoolExecutor(capacity, capacity, 0, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(capacity),
                    Thread.ofPlatform().daemon().name("order-stock-saga-", 0).factory());
        }
        if (settings.shardConcurrency() == 1) {
            // Preserve the original global scan and delay; workers-per-shard=1 restores serial execution.
            executor.scheduleWithFixedDelay(this::poll, 0, settings.interval().toMillis(), TimeUnit.MILLISECONDS);
        } else {
            for (var shard : topology.shards()) {
                scheduleShard(shard, 0);
            }
        }
    }

    void poll() {
        for (var shard : topology.shards()) {
            if (stopping()) {
                return;
            }
            try {
                pollShard(shard);
            } catch (RuntimeException | Error failure) {
                unexpectedFailure(shard, failure);
            }
        }
    }

    /** Yield the scheduler after every batch so a permanently busy shard cannot starve queued shards. */
    private void runShard(Shard shard) {
        boolean full = false;
        try {
            full = pollShard(shard);
        } catch (RuntimeException | Error failure) {
            unexpectedFailure(shard, failure);
        } finally {
            if (!stopping()) {
                scheduleShard(shard, full ? 0 : settings.interval().toMillis());
            }
        }
    }

    private void scheduleShard(Shard shard, long delayMillis) {
        if (!closed) {
            try {
                executor.schedule(() -> runShard(shard), delayMillis, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException stopping) {
                if (!closed) {
                    throw stopping;
                }
            }
        }
    }

    /** One bounded batch; true when every attempt advanced a saga. */
    boolean pollShard(Shard shard) {
        if (stopping()) {
            return false;
        }
        Batch batch = new Batch(shard);
        if (workers == null) {
            batch.consume();
        } else {
            List<Future<?>> running = new ArrayList<>();
            try {
                for (int index = 0; index < Math.min(settings.workersPerShard(), settings.batchSize()); index++) {
                    running.add(workers.submit(batch::consume));
                }
                for (var worker : running) {
                    try {
                        worker.get();
                    } catch (ExecutionException failure) {
                        batch.failed.set(true);
                        batch.stop.set(true);
                        unavailable(shard);
                        // Await the siblings before releasing this shard's batch slot.
                    }
                }
            } catch (InterruptedException interrupted) {
                running.forEach(worker -> worker.cancel(true));
                Thread.currentThread().interrupt();
                return false;
            } catch (RejectedExecutionException failure) {
                running.forEach(worker -> worker.cancel(true));
                unavailable(shard);
                return false;
            }
        }
        if (!batch.failed.get() && unavailable.remove(shard.clusterName())) {
            LOG.log(System.Logger.Level.INFO, "Stock coordination resumed for {0}", shard.clusterName());
        }
        return batch.advanced.get() == settings.batchSize();
    }

    private final class Batch {
        private final Shard shard;
        private final AtomicInteger attempts = new AtomicInteger();
        private final AtomicInteger advanced = new AtomicInteger();
        private final AtomicBoolean stop = new AtomicBoolean();
        private final AtomicBoolean failed = new AtomicBoolean();

        private Batch(Shard shard) {
            this.shard = shard;
        }

        private void consume() {
            while (!stopping() && !stop.get() && attempts.getAndIncrement() < settings.batchSize()) {
                try {
                    // Claim inside the executing worker: queued tasks never hold expiring saga leases.
                    if (coordinator.processNext(shard) != StockCoordinator.Progress.ADVANCED) {
                        stop.set(true);
                        return;
                    }
                    advanced.incrementAndGet();
                } catch (RuntimeException failure) {
                    failed.set(true);
                    stop.set(true);
                    unavailable(shard);
                    return;
                }
            }
        }
    }

    private boolean stopping() {
        return closed || Thread.currentThread().isInterrupted();
    }

    private void unexpectedFailure(Shard shard, Throwable failure) {
        // Arbitrary failure messages can contain credentials or buyer payloads; only log the failure type.
        String type = failure.getClass().getSimpleName();
        LOG.log(System.Logger.Level.ERROR,
                "Unexpected stock coordination failure for {0} ({1}); pending work will retry after recovery",
                shard.clusterName(), type.substring(0, Math.min(type.length(), 120)));
    }

    private void unavailable(Shard shard) {
        if (!closed && unavailable.add(shard.clusterName())) {
            // No JDBC messages, credentials or buyer payloads.
            LOG.log(System.Logger.Level.WARNING,
                    "Stock coordination unavailable for {0}; pending work will retry after recovery",
                    shard.clusterName());
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (executor != null) {
            executor.shutdownNow();
        }
        if (workers != null) {
            workers.shutdownNow();
        }
        // One shared five-second shutdown budget for scheduler and saga workers.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try {
            for (var pool : new ExecutorService[] { executor, workers }) {
                if (pool != null && !pool.awaitTermination(Math.max(0, deadline - System.nanoTime()),
                        TimeUnit.NANOSECONDS)) {
                    LOG.log(System.Logger.Level.WARNING, "Stock worker did not stop within five seconds");
                    break;
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
