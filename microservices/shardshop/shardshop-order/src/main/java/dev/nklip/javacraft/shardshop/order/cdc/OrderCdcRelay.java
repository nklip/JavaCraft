package dev.nklip.javacraft.shardshop.order.cdc;

import dev.nklip.javacraft.shardshop.common.Failures;
import dev.nklip.javacraft.shardshop.common.amqp.BrokerSettings;
import dev.nklip.javacraft.shardshop.common.amqp.ConfirmedPublisher;
import dev.nklip.javacraft.shardshop.common.jdbc.Endpoint;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.format.Json;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** One independently supervised, source-ordered Debezium reader for each order shard. */
public final class OrderCdcRelay implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(OrderCdcRelay.class);
    private static final long MAX_BACKOFF_MILLIS = 30_000;
    /** Commands route to ledger's quorum queue ledger.record-order. */
    private static final ConfirmedPublisher.Destination COMMANDS = new ConfirmedPublisher.Destination(
            "ledger.commands", "ledger.record-order", "command", "order-command-publisher");

    public record ShardSettings(String shard, Endpoint application, Endpoint replication) {
        public ShardSettings {
            CdcConfiguration.identity(shard);
            Objects.requireNonNull(application, "application");
            Objects.requireNonNull(replication, "replication");
        }
    }

    private final List<Reader> readers;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    public OrderCdcRelay(List<ShardSettings> shards, BrokerSettings rabbit) {
        Objects.requireNonNull(shards, "shards");
        Objects.requireNonNull(rabbit, "rabbit");
        Set<String> seen = new HashSet<>();
        for (ShardSettings shard : shards) {
            if (!seen.add(shard.shard())) {
                throw new IllegalArgumentException("Duplicate CDC shard");
            }
        }
        readers = List.copyOf(shards).stream().map(shard -> new Reader(shard, rabbit)).toList();
    }

    /** Starts the readers without blocking application readiness on external services. */
    public void start() {
        if (closed.get()) {
            throw new IllegalStateException("CDC relay is closed");
        }
        if (started.compareAndSet(false, true)) {
            readers.forEach(Reader::start);
        }
    }

    public boolean recoveryRequired(String shard) {
        return readers.stream().filter(reader -> reader.settings.shard().equals(shard))
                .anyMatch(reader -> reader.recoveryRequired);
    }

    public boolean readerConfigured(String shard) {
        return readers.stream().anyMatch(reader -> reader.settings.shard().equals(shard));
    }

    public boolean retrying(String shard) {
        return readers.stream().filter(reader -> reader.settings.shard().equals(shard))
                .anyMatch(reader -> reader.retrying);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            readers.forEach(Reader::stop);
            for (Reader reader : readers) {
                reader.awaitStop();
            }
        }
    }

    /** Finishes engine shutdown before the caller releases the shard ownership connection. */
    static Exception stopEngine(DebeziumEngine<?> created, Thread running) {
        Exception closeFailure = null;
        // An interrupted supervisor must not pass its interruption into Debezium's blocking cleanup.
        boolean interrupted = Thread.interrupted();
        try {
            while (running.isAlive()) {
                try {
                    created.close();
                } catch (IllegalStateException lifecycleRace) {
                    // AsyncEmbeddedEngine rejects close during STARTING_TASKS and STOPPING. Retry after
                    // startup, or let its ongoing shutdown finish, under the same shard ownership lock.
                } catch (Exception failedToClose) {
                    closeFailure = failedToClose;
                }
                try {
                    // Debezium bounds task and record shutdown itself. Interrupting this thread would
                    // cancel connector cleanup and leave its separate replication stream alive.
                    running.join(1_000);
                } catch (InterruptedException stop) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        return closeFailure;
    }

    private final class Reader implements Runnable {
        private final ShardSettings settings;
        private final BrokerSettings rabbit;
        private final CdcStore store;
        private final Thread supervisor;
        private final CountDownLatch stopRequested = new CountDownLatch(1);
        private volatile boolean recoveryRequired;
        private volatile boolean retrying;

        Reader(ShardSettings settings, BrokerSettings rabbit) {
            this.settings = settings;
            this.rabbit = rabbit;
            this.store = new CdcStore(settings.application(), settings.replication());
            this.supervisor = Thread.ofPlatform().daemon().name("order-cdc-" + settings.shard()).unstarted(this);
        }

        void start() {
            supervisor.start();
        }

        void stop() {
            // Wake retry backoff without interrupting a supervisor that may already be closing the
            // engine. Its one-second monitoring join observes closed and enters the same cleanup path.
            stopRequested.countDown();
        }

        void awaitStop() {
            if (!started.get()) {
                return;
            }
            try {
                supervisor.join(15_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void run() {
            long backoff = 500;
            while (!closed.get() && !recoveryRequired) {
                retrying = false;
                try {
                    runOnce();
                    backoff = 500;
                } catch (CdcStore.RecoveryRequiredException terminal) {
                    recoveryRequired = true;
                    LOG.errorf("CDC recovery required for %s: %s", settings.shard(), terminal.getMessage());
                } catch (InterruptedException interrupted) {
                    if (closed.get()) {
                        break;
                    }
                    LOG.warnf("CDC reader for %s interrupted; retrying in %d ms", settings.shard(), backoff);
                    Thread.interrupted();
                } catch (Exception failed) {
                    if (!closed.get()) {
                        LOG.warnf("CDC reader for %s stopped; retrying in %d ms: %s", settings.shard(), backoff,
                                Failures.describe(failed));
                    }
                }
                if (!closed.get() && !recoveryRequired) {
                    retrying = true;
                    try {
                        stopRequested.await(backoff, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException interrupted) {
                        if (closed.get()) {
                            break;
                        }
                    }
                    backoff = Math.min(MAX_BACKOFF_MILLIS, backoff * 2);
                }
            }
        }

        private void runOnce() throws Exception {
            String identity = CdcConfiguration.identity(settings.shard());
            try (Connection owner = store.own()) {
                if (store.checkRecovery(owner, identity)) {
                    store.dropSlot(identity);
                    LOG.infof("CDC reader for %s dropped the slot of a first start without a checkpoint",
                            settings.shard());
                }
                try (ConfirmedPublisher publisher = new ConfirmedPublisher(rabbit, COMMANDS)) {
                    CdcBatchProcessor processor = new CdcBatchProcessor(identity, publisher, store::markPublished);
                    AtomicReference<Throwable> completionFailure = new AtomicReference<>();
                    DebeziumEngine<ChangeEvent<String, String>> created = DebeziumEngine.create(Json.class)
                            .using(CdcConfiguration.properties(settings.shard(), settings.application(),
                                    settings.replication()))
                            .notifying(processor)
                            .using((success, message, failure) -> {
                                if (!success) {
                                    completionFailure.set(failure == null
                                            ? new IllegalStateException("CDC engine stopped") : failure);
                                }
                            })
                            .build();
                    Thread running = Thread.ofPlatform().daemon().name("order-cdc-engine-" + settings.shard())
                            .unstarted(created);
                    Exception closeFailure = null;
                    try {
                        running.start();
                        while (running.isAlive() && !closed.get()) {
                            running.join(1_000);
                            if (processor.failure() != null) {
                                break;
                            }
                            if (!store.ownerAlive(owner)) {
                                throw new IllegalStateException("CDC ownership connection was lost");
                            }
                        }
                    } finally {
                        closeFailure = stopEngine(created, running);
                    }
                    if (processor.failure() != null) {
                        throw new IllegalStateException("CDC batch stopped before checkpoint", processor.failure());
                    }
                    // A lost slot, offset or WAL fails the next checkRecovery, which reads durable state.
                    Throwable failure = completionFailure.get();
                    if (failure != null) {
                        throw new IllegalStateException("CDC engine stopped", failure);
                    }
                    if (closeFailure != null) {
                        throw closeFailure;
                    }
                }
            }
        }
    }
}
