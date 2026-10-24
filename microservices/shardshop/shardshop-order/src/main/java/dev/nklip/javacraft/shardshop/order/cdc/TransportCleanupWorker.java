package dev.nklip.javacraft.shardshop.order.cdc;

import dev.nklip.javacraft.shardshop.common.Failures;
import dev.nklip.javacraft.shardshop.common.jdbc.Endpoint;
import dev.nklip.javacraft.shardshop.order.persistence.JdbcOrderStore;
import dev.nklip.javacraft.shardshop.sharding.Shard;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Saga-only, per-shard bounded cleanup of transport rows. Pending fences are deliberately not persisted. */
public final class TransportCleanupWorker implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(TransportCleanupWorker.class.getName());

    public record Settings(boolean enabled, Duration retention, Duration pollInterval, int batchSize) {
        public Settings {
            if (retention == null || retention.compareTo(Duration.ofSeconds(1)) < 0
                    || retention.compareTo(Duration.ofDays(365)) > 0) {
                throw new IllegalArgumentException("Cleanup retention must be between 1s and 365d");
            }
            if (pollInterval == null || pollInterval.compareTo(Duration.ofMillis(100)) < 0
                    || pollInterval.compareTo(Duration.ofSeconds(60)) > 0) {
                throw new IllegalArgumentException("Cleanup poll interval must be between 100ms and 60s");
            }
            if (batchSize < 1 || batchSize > 1000) {
                throw new IllegalArgumentException("Cleanup batch size must be between 1 and 1000");
            }
        }

        long retentionMicros() {
            // PostgreSQL timestamps have microsecond precision; round up to avoid early deletion.
            return (retention.toNanos() + 999) / 1_000;
        }
    }

    public record ShardSettings(Shard shard, Endpoint endpoint) {
        public ShardSettings {
            CdcConfiguration.identity(shard.clusterName());
            Objects.requireNonNull(endpoint);
        }
    }

    interface Store {
        int deleteInbox(long retentionMicros, int limit) throws SQLException;
        TransportCleanupStore.Fence capture(long retentionMicros, int limit) throws SQLException;
        Long durableCheckpoint(String identity) throws SQLException;
        int deleteOutbox(TransportCleanupStore.Fence fence, long retentionMicros) throws SQLException;
    }

    private static final class JdbcStore implements Store {
        private final TransportCleanupStore delegate;
        JdbcStore(Endpoint endpoint, JdbcOrderStore orderStore, Shard shard) {
            delegate = new TransportCleanupStore(endpoint, orderStore, shard);
        }
        @Override public int deleteInbox(long age, int limit) throws SQLException { return delegate.deleteInbox(age, limit); }
        @Override public TransportCleanupStore.Fence capture(long age, int limit) throws SQLException {
            return delegate.capture(age, limit);
        }
        @Override public Long durableCheckpoint(String identity) throws SQLException {
            return delegate.durableCheckpoint(identity);
        }
        @Override public int deleteOutbox(TransportCleanupStore.Fence fence, long age) throws SQLException {
            return delegate.deleteOutbox(fence, age);
        }
    }

    private static final class ShardState {
        final String name;
        final String identity;
        final Store store;
        TransportCleanupStore.Fence pending;
        boolean unavailable;

        ShardState(String name, Store store) {
            this.name = name;
            this.identity = CdcConfiguration.identity(name);
            this.store = store;
        }
    }

    private final Settings settings;
    private final List<ShardState> shards;
    private ScheduledExecutorService executor;

    public TransportCleanupWorker(List<ShardSettings> configured, JdbcOrderStore orderStore, Settings settings) {
        this.settings = Objects.requireNonNull(settings);
        Objects.requireNonNull(configured);
        Objects.requireNonNull(orderStore);
        Set<String> names = new HashSet<>();
        List<ShardState> created = new ArrayList<>();
        for (ShardSettings shard : configured) {
            if (!names.add(shard.shard().clusterName())) {
                throw new IllegalArgumentException("Duplicate cleanup shard");
            }
            created.add(new ShardState(shard.shard().clusterName(),
                    new JdbcStore(shard.endpoint(), orderStore, shard.shard())));
        }
        this.shards = List.copyOf(created);
    }

    TransportCleanupWorker(Settings settings, String name, Store store) {
        this.settings = Objects.requireNonNull(settings);
        this.shards = List.of(new ShardState(name, Objects.requireNonNull(store)));
    }

    public synchronized void start() {
        if (!settings.enabled() || executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon()
                .name("order-transport-cleanup").factory());
        executor.scheduleWithFixedDelay(this::poll, 0, settings.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
    }

    void poll() {
        for (ShardState shard : shards) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            // Inbox cleanup is independent of a pending outbox checkpoint.
            try {
                shard.store.deleteInbox(settings.retentionMicros(), settings.batchSize());
            } catch (SQLException | RuntimeException failure) {
                unavailable(shard, failure);
                continue;
            }
            try {
                if (shard.pending == null) {
                    shard.pending = shard.store.capture(settings.retentionMicros(), settings.batchSize());
                } else {
                    Long checkpoint = shard.store.durableCheckpoint(shard.identity);
                    if (checkpoint != null && checkpoint > shard.pending.walPosition()) {
                        shard.store.deleteOutbox(shard.pending, settings.retentionMicros());
                        // One bounded recapture phase prepares the next poll; never catch up in a loop.
                        shard.pending = shard.store.capture(settings.retentionMicros(), settings.batchSize());
                    }
                }
                if (shard.unavailable) {
                    shard.unavailable = false;
                    LOG.log(System.Logger.Level.INFO, "Transport cleanup resumed for {0}", shard.name);
                }
            } catch (SQLException | RuntimeException failure) {
                unavailable(shard, failure);
            }
        }
    }

    private void unavailable(ShardState shard, Exception failure) {
        if (!shard.unavailable) {
            shard.unavailable = true;
            LOG.log(System.Logger.Level.WARNING, () -> "Transport cleanup unavailable for " + shard.name
                    + "; will retry: " + Failures.describe(failure));
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
