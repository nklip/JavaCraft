package dev.nklip.javacraft.shardshop.ledger.cleanup;

import dev.nklip.javacraft.shardshop.common.Failures;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Periodically removes expired ledger transport rows without touching durable replay identities. */
public final class LedgerTransportCleanup implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(LedgerTransportCleanup.class);
    private static final long CLOSE_WAIT_MILLIS = 5_000;

    private final TransportCleanupStore store;
    private final Settings settings;
    private final Clock clock;
    private final CountDownLatch shutdown = new CountDownLatch(1);
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread worker;

    public LedgerTransportCleanup(TransportCleanupStore store, Settings settings, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.worker = Thread.ofPlatform().daemon().name("ledger-transport-cleanup").unstarted(this::run);
    }

    public void start() {
        if (closed.get()) {
            throw new IllegalStateException("Ledger transport cleanup is closed");
        }
        if (settings.enabled() && started.compareAndSet(false, true)) {
            worker.start();
        }
    }

    private void run() {
        boolean failureReported = false;
        while (!closed.get()) {
            boolean fullBatch = false;
            try {
                Instant cutoff = clock.instant().minus(settings.retention());
                TransportCleanupStore.Counts deleted = Objects.requireNonNull(
                        store.cleanup(cutoff, settings.batchSize()), "cleanup counts");
                if (failureReported) {
                    LOG.info("Ledger transport cleanup recovered");
                    failureReported = false;
                }
                fullBatch = deleted.inbox() == settings.batchSize() || deleted.outbox() == settings.batchSize();
            } catch (Exception failure) {
                if (!closed.get() && !failureReported) {
                    LOG.warnf("Ledger transport cleanup failed; retrying: %s", Failures.describe(failure));
                    failureReported = true;
                }
            }
            try {
                if (!fullBatch && shutdown.await(settings.pollInterval().toMillis(), TimeUnit.MILLISECONDS)) {
                    break;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
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

    public record Settings(boolean enabled, Duration retention, Duration pollInterval, int batchSize) {
        public Settings {
            if (retention == null || retention.compareTo(Duration.ofSeconds(1)) < 0
                    || retention.compareTo(Duration.ofDays(365)) > 0) {
                throw new IllegalArgumentException("Ledger cleanup retention must be 1s..365d");
            }
            if (pollInterval == null || pollInterval.compareTo(Duration.ofMillis(100)) < 0
                    || pollInterval.compareTo(Duration.ofSeconds(60)) > 0) {
                throw new IllegalArgumentException("Ledger cleanup poll interval must be 100ms..60s");
            }
            if (batchSize < 1 || batchSize > 1_000) {
                throw new IllegalArgumentException("Ledger cleanup batch size must be 1..1000");
            }
        }
    }
}
