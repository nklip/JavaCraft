package dev.nklip.javacraft.shardshop.order.config;

import dev.nklip.javacraft.shardshop.common.Failures;
import dev.nklip.javacraft.shardshop.order.reconciliation.ReconciliationCoordinator;
import dev.nklip.javacraft.shardshop.sharding.ShardTopology;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Polls eligible ledger sagas on the saga process and reports an aggregate exhausted budget. */
public final class ReconciliationWorker implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(ReconciliationWorker.class.getName());
    private static final Duration ALERT_INTERVAL = Duration.ofMinutes(5);

    private final ReconciliationCoordinator coordinator;
    private final ShardTopology topology;
    private final Settings settings;
    private final Clock clock;
    private final AlertSink alerts;
    private final Map<String, Alert> exhausted = new HashMap<>();
    private final Set<String> unavailable = new HashSet<>();
    private ScheduledExecutorService executor;

    public record Settings(boolean enabled, Duration interval, int batchSize) {
        public Settings {
            if (interval == null || interval.compareTo(Duration.ofMillis(100)) < 0
                    || interval.compareTo(Duration.ofSeconds(30)) > 0) {
                throw new IllegalArgumentException("Reconciliation poll interval must be between 100ms and 30s");
            }
            if (batchSize < 1 || batchSize > 1000) {
                throw new IllegalArgumentException("Reconciliation batch size must be between 1 and 1000");
            }
        }
    }

    interface AlertSink {
        void exhausted(String shard, long count);
        void recovered(String shard);
    }

    private record Alert(long count, Instant loggedAt) { }

    public ReconciliationWorker(ReconciliationCoordinator coordinator, ShardTopology topology,
                                Settings settings, Clock clock) {
        this(coordinator, topology, settings, clock, new AlertSink() {
            @Override public void exhausted(String shard, long count) {
                LOG.log(System.Logger.Level.WARNING, () -> "Reconciliation budget exhausted on " + shard
                        + ": " + count + " pending ledger sagas require investigation");
            }
            @Override public void recovered(String shard) {
                LOG.log(System.Logger.Level.INFO, () -> "Reconciliation budget recovered on " + shard);
            }
        });
    }

    ReconciliationWorker(ReconciliationCoordinator coordinator, ShardTopology topology,
                         Settings settings, Clock clock, AlertSink alerts) {
        this.coordinator = Objects.requireNonNull(coordinator);
        this.topology = Objects.requireNonNull(topology);
        this.settings = Objects.requireNonNull(settings);
        this.clock = Objects.requireNonNull(clock);
        this.alerts = Objects.requireNonNull(alerts);
    }

    public synchronized void start() {
        if (!settings.enabled() || executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon()
                .name("order-reconciliation-worker").factory());
        executor.scheduleWithFixedDelay(this::poll, 0, settings.interval().toMillis(), TimeUnit.MILLISECONDS);
    }

    void poll() {
        for (var shard : topology.shards()) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            String name = shard.clusterName();
            try {
                var result = coordinator.scan(shard, settings.batchSize());
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                long count = coordinator.exhausted(shard);
                alert(name, count);
                if (result.firstFailure() != null) {
                    unavailable(name, result.firstFailure().cause(), result.firstFailure().sagaId());
                } else if (result.passComplete() && !result.passFailed() && unavailable.remove(name)) {
                    LOG.log(System.Logger.Level.INFO, "Saga reconciliation resumed for {0}", name);
                }
            } catch (RuntimeException failure) {
                unavailable(name, failure, null);
            }
        }
    }

    private void unavailable(String name, RuntimeException failure, Long sagaId) {
        if (unavailable.add(name)) {
            String candidate = sagaId != null && sagaId > 0 ? " at saga " + sagaId : "";
            LOG.log(System.Logger.Level.WARNING, () -> "Saga reconciliation unavailable for " + name
                    + candidate + "; pending work will retry: " + Failures.describe(failure));
        }
    }

    boolean isUnavailable(String shard) {
        return unavailable.contains(shard);
    }

    private void alert(String shard, long count) {
        Instant now = clock.instant();
        Alert previous = exhausted.get(shard);
        if (count == 0) {
            if (previous != null) {
                exhausted.remove(shard);
                alerts.recovered(shard);
            }
            return;
        }
        if (previous == null || previous.count() != count
                || !now.isBefore(previous.loggedAt().plus(ALERT_INTERVAL))) {
            exhausted.put(shard, new Alert(count, now));
            alerts.exhausted(shard, count);
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
