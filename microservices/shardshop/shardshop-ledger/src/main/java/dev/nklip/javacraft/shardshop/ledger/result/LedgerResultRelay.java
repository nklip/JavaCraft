package dev.nklip.javacraft.shardshop.ledger.result;

import dev.nklip.javacraft.shardshop.common.Failures;
import dev.nklip.javacraft.shardshop.common.amqp.ConfirmedPublisher;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Serially publishes committed results, then conditionally settles their outbox attempts. */
public final class LedgerResultRelay implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(LedgerResultRelay.class);
    private static final long INITIAL_BACKOFF_MILLIS = 500;
    private static final long MAX_BACKOFF_MILLIS = 30_000;
    private static final long CLOSE_WAIT_MILLIS = 5_000;

    private final ResultOutbox outbox;
    private final Supplier<ConfirmedPublisher> publishers;
    private final Settings settings;
    private final CountDownLatch shutdown = new CountDownLatch(1);
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread supervisor;
    private volatile ConfirmedPublisher publisher;

    public LedgerResultRelay(ResultOutbox outbox, Supplier<ConfirmedPublisher> publishers, Settings settings) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.publishers = Objects.requireNonNull(publishers, "publishers");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.supervisor = Thread.ofPlatform().daemon().name("ledger-result-relay").unstarted(this::run);
    }

    public void start() {
        if (closed.get()) {
            throw new IllegalStateException("Ledger result relay is closed");
        }
        if (settings.enabled() && started.compareAndSet(false, true)) {
            supervisor.start();
        }
    }

    private void run() {
        long backoff = INITIAL_BACKOFF_MILLIS;
        try {
            while (!closed.get()) {
                try {
                    List<PendingResult> batch = Objects.requireNonNull(outbox.pending(settings.batchSize()),
                            "pending results");
                    for (PendingResult result : batch) {
                        if (closed.get()) {
                            break;
                        }
                        ConfirmedPublisher active = publisher;
                        if (active == null) {
                            active = Objects.requireNonNull(publishers.get(), "result publisher");
                            publisher = active;
                        }
                        // The reserved result ID and the exact saved envelope.
                        active.publish(Long.toString(result.messageId()),
                                result.envelope().getBytes(StandardCharsets.UTF_8));
                        if (closed.get()) {
                            break;
                        }
                        outbox.markPublished(result.messageId(), result.publicationAttempt());
                    }
                    backoff = INITIAL_BACKOFF_MILLIS;
                    if (batch.size() < settings.batchSize() && !shutdown.await(
                            settings.pollInterval().toMillis(), TimeUnit.MILLISECONDS)) {
                        continue;
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception failure) {
                    discardPublisher();
                    if (!closed.get()) {
                        LOG.warnf("Ledger result publisher retry in %d ms: %s", backoff,
                                Failures.describe(failure));
                        try {
                            if (shutdown.await(backoff, TimeUnit.MILLISECONDS)) {
                                break;
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        backoff = Math.min(MAX_BACKOFF_MILLIS, backoff * 2);
                    }
                }
            }
        } finally {
            discardPublisher();
        }
    }

    private void discardPublisher() {
        ConfirmedPublisher current = publisher;
        publisher = null;
        if (current != null) {
            current.close();
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            shutdown.countDown();
            if (started.get()) {
                supervisor.interrupt();
                try {
                    supervisor.join(CLOSE_WAIT_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    public record Settings(boolean enabled, int batchSize, Duration pollInterval) {
        public Settings {
            if (batchSize < 1 || batchSize > 1_000) {
                throw new IllegalArgumentException("Result relay batch size must be 1..1000");
            }
            if (pollInterval == null || pollInterval.toMillis() < 1 || pollInterval.toMillis() > 60_000) {
                throw new IllegalArgumentException("Result relay poll interval must be 1..60000 ms");
            }
        }
    }
}
