package dev.nklip.javacraft.shardshop.order.cdc;

import dev.nklip.javacraft.shardshop.common.jdbc.Endpoint;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.format.Json;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** One independently supervised, source-ordered Debezium reader for each order shard. */
public final class OrderCdcRelay implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(OrderCdcRelay.class);
    private static final long MAX_BACKOFF_MILLIS = 30_000;

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

    public OrderCdcRelay(List<ShardSettings> shards, RabbitCommandPublisher.Settings rabbit) {
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

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            readers.forEach(Reader::stop);
            for (Reader reader : readers) {
                reader.awaitStop();
            }
        }
    }

    private final class Reader implements Runnable {
        private final ShardSettings settings;
        private final RabbitCommandPublisher.Settings rabbit;
        private final CdcStore store;
        private final Thread supervisor;
        private volatile DebeziumEngine<ChangeEvent<String, String>> engine;
        private volatile Thread engineThread;
        private volatile boolean recoveryRequired;

        Reader(ShardSettings settings, RabbitCommandPublisher.Settings rabbit) {
            this.settings = settings;
            this.rabbit = rabbit;
            this.store = new CdcStore(settings.application(), settings.replication());
            this.supervisor = Thread.ofPlatform().daemon().name("order-cdc-" + settings.shard()).unstarted(this);
        }

        void start() {
            supervisor.start();
        }

        void stop() {
            supervisor.interrupt();
            Thread running = engineThread;
            if (running != null) {
                running.interrupt();
            }
            DebeziumEngine<ChangeEvent<String, String>> current = engine;
            if (current != null) {
                try {
                    current.close();
                } catch (Exception ignored) {
                    // The supervisor also closes the engine and retains its source offset.
                }
            }
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
                                describe(failed));
                    }
                }
                if (!closed.get() && !recoveryRequired) {
                    try {
                        Thread.sleep(backoff);
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
                try (RabbitCommandPublisher publisher = new RabbitCommandPublisher(rabbit)) {
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
                    engine = created;
                    Thread running = Thread.ofPlatform().daemon().name("order-cdc-engine-" + settings.shard())
                            .unstarted(created);
                    engineThread = running;
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
                        try {
                            if (running.isAlive()) {
                                created.close();
                            }
                        } catch (Exception failedToClose) {
                            closeFailure = failedToClose;
                        } finally {
                            // Never release the session lock while the previous reader can still write offsets.
                            boolean interrupted = false;
                            while (running.isAlive()) {
                                running.interrupt();
                                try {
                                    running.join(1_000);
                                } catch (InterruptedException stop) {
                                    interrupted = true;
                                }
                            }
                            if (interrupted) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        engineThread = null;
                        engine = null;
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

    /** A single-line cause chain for the retry log, bounded in depth and length. */
    static String describe(Throwable failure) {
        StringBuilder text = new StringBuilder();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        int depth = 0;
        for (Throwable cause = failure; cause != null && depth < 6 && seen.add(cause); cause = cause.getCause()) {
            if (depth++ > 0) {
                text.append(" <- ");
            }
            text.append(cause.getClass().getSimpleName());
            String message = cause.getMessage();
            if (message != null && !message.isBlank()) {
                String line = message.replaceAll("\\s+", " ").strip();
                text.append(": ").append(line.length() > 300 ? line.substring(0, 300) + "..." : line);
            }
        }
        return text.toString();
    }
}
