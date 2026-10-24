package dev.nklip.javacraft.shardshop.ledger.metrics;

import dev.nklip.javacraft.shardshop.common.Failures;
import dev.nklip.javacraft.shardshop.ledger.persistence.JdbcLedgerStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Publishes cached database snapshots and direct metrics from the ledger-owned Agroal pool. */
public final class LedgerMetrics implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(LedgerMetrics.class);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(10);
    private static final long CLOSE_WAIT_MILLIS = 5_000;

    private final JdbcLedgerStore store;
    private final Clock clock;
    private final boolean enabled;
    private final CountDownLatch shutdown = new CountDownLatch(1);
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread worker;
    private volatile Sample sample = new Sample(null, null, false);
    private boolean failureReported;

    public LedgerMetrics(JdbcLedgerStore store, MeterRegistry registry, Clock clock, boolean enabled) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.enabled = enabled;
        this.worker = Thread.ofPlatform().daemon().name("ledger-metrics").unstarted(this::run);

        Gauge.builder("shardshop.ledger.pending.results", this, metrics -> metrics.value(0))
                .description("Pending ledger result outbox rows from the last successful snapshot")
                .register(registry);
        Gauge.builder("shardshop.ledger.oldest.pending.age.seconds", this, metrics -> metrics.value(1))
                .description("Age of the oldest pending ledger result at the last successful snapshot")
                .register(registry);
        Gauge.builder("shardshop.ledger.conflicting.commands", this, metrics -> metrics.value(2))
                .description("Historical conflicting command quarantine rows")
                .register(registry);
        Gauge.builder("shardshop.ledger.snapshot.success", this, metrics -> metrics.sample.success() ? 1 : 0)
                .description("Whether the latest ledger database snapshot succeeded")
                .register(registry);
        Gauge.builder("shardshop.ledger.snapshot.age.seconds", this, LedgerMetrics::snapshotAgeSeconds)
                .description("Seconds since the last successful ledger database snapshot")
                .register(registry);
        Gauge.builder("shardshop.ledger.pool.active", store, JdbcLedgerStore::activeConnections)
                .description("Active ledger Agroal pool connections")
                .register(registry);
        Gauge.builder("shardshop.ledger.pool.available", store, JdbcLedgerStore::availableConnections)
                .description("Available ledger Agroal pool connections")
                .register(registry);
        Gauge.builder("shardshop.ledger.pool.awaiting", store, JdbcLedgerStore::awaitingConnections)
                .description("Threads awaiting a ledger Agroal pool connection")
                .register(registry);
        Gauge.builder("shardshop.ledger.pool.max", store, JdbcLedgerStore::maxConnections)
                .description("Configured maximum ledger Agroal pool connections")
                .register(registry);
    }

    public void start() {
        if (closed.get()) {
            throw new IllegalStateException("Ledger metrics are closed");
        }
        if (enabled && started.compareAndSet(false, true)) {
            worker.start();
        }
    }

    void refresh() {
        try {
            JdbcLedgerStore.MetricsSnapshot snapshot = Objects.requireNonNull(store.metricsSnapshot());
            boolean recovering = failureReported;
            sample = new Sample(snapshot, clock.instant(), true);
            failureReported = false;
            if (recovering) {
                LOG.info("Ledger metrics snapshot recovered");
            }
        } catch (Exception failure) {
            boolean firstFailure = !failureReported;
            failureReported = true;
            Sample previous = sample;
            sample = new Sample(previous.snapshot(), previous.at(), false);
            if (firstFailure && !closed.get()) {
                LOG.warnf("Ledger metrics snapshot failed; retrying: %s", Failures.describe(failure));
            }
        }
    }

    private void run() {
        while (!closed.get()) {
            refresh();
            try {
                if (shutdown.await(POLL_INTERVAL.toMillis(), TimeUnit.MILLISECONDS)) {
                    break;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private double value(int field) {
        JdbcLedgerStore.MetricsSnapshot snapshot = sample.snapshot();
        if (snapshot == null) {
            return Double.NaN;
        }
        return switch (field) {
            case 0 -> snapshot.pendingResults();
            case 1 -> snapshot.oldestPendingAgeSeconds();
            case 2 -> snapshot.conflictingCommands();
            default -> throw new IllegalArgumentException("Unknown ledger metric");
        };
    }

    private double snapshotAgeSeconds() {
        Instant at = sample.at();
        return at == null ? Double.NaN : Math.max(0, Duration.between(at, clock.instant()).toMillis() / 1000.0);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            shutdown.countDown();
            if (started.get()) {
                worker.interrupt();
                try {
                    worker.join(CLOSE_WAIT_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private record Sample(JdbcLedgerStore.MetricsSnapshot snapshot, Instant at, boolean success) {}
}
