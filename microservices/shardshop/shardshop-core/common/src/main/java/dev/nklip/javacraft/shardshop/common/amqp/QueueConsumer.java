package dev.nklip.javacraft.shardshop.common.amqp;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import dev.nklip.javacraft.shardshop.common.Failures;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One quorum queue with manual acknowledgements and one prefetched delivery, for the ledger command consumer and the
 * order result consumer. The handler's local transaction commits before the broker settlement. A permanently invalid
 * message goes to the queue's dead-letter queue; a failed transaction uses {@code basic.reject} with requeue, thus the
 * broker's delayed retry and delivery limit apply. A supervisor reconnects with capped exponential backoff and jitter.
 * Log lines name causes and fixed rule texts, never a message body or a password.
 */
public final class QueueConsumer implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(QueueConsumer.class.getName());

    private final BrokerSettings broker;
    private final boolean enabled;
    private final Source source;
    private final Handler handler;
    private final ConnectionFactory factory;
    private final CountDownLatch shutdown = new CountDownLatch(1);
    private final Thread supervisor;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    public QueueConsumer(BrokerSettings broker, boolean enabled, Source source, Handler handler) {
        this(broker, enabled, source, handler, new ConnectionFactory());
    }

    QueueConsumer(BrokerSettings broker, boolean enabled, Source source, Handler handler, ConnectionFactory factory) {
        this.broker = Objects.requireNonNull(broker, "broker");
        this.enabled = enabled;
        this.source = Objects.requireNonNull(source, "source");
        this.handler = Objects.requireNonNull(handler, "handler");
        this.factory = Objects.requireNonNull(factory, "factory");
        // The client owns its callback executor and drains shutdown callbacks before terminating it.
        // Its per-channel dispatcher serializes deliveries, with one in flight because prefetch is one.
        broker.configure(factory, source.threadName() + "-amqp-");
        supervisor = Thread.ofPlatform().daemon().name(source.threadName()).unstarted(this::run);
    }

    public void start() {
        if (closed.get()) {
            throw new IllegalStateException(source.consumer() + " consumer is closed");
        }
        if (enabled && started.compareAndSet(false, true)) {
            supervisor.start();
        }
    }

    private void run() {
        long backoff = 500;
        while (!closed.get()) {
            String cause;
            try {
                cause = consume();
                backoff = 500;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception failure) {
                cause = Failures.describe(failure);
            }
            if (!closed.get()) {
                long delay = Math.min(30_000, Math.round(backoff * ThreadLocalRandom.current().nextDouble(0.75, 1.25)));
                String reason = cause;
                LOG.log(System.Logger.Level.WARNING, () -> source.consumer() + " consumer stopped; retrying in "
                        + delay + " ms: " + reason);
                try {
                    if (shutdown.await(delay, TimeUnit.MILLISECONDS)) {
                        break;
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
                backoff = Math.min(30_000, backoff * 2);
            }
        }
    }

    /** Consumes until the connection, the channel, a cancellation or a failed settlement stops it; returns why. */
    private String consume() throws Exception {
        Connection opened = factory.newConnection();
        AtomicReference<String> cause = new AtomicReference<>();
        CountDownLatch stopped = new CountDownLatch(1);
        try {
            if (closed.get()) {
                return "the consumer is closed";
            }
            opened.addShutdownListener(signal -> stop(cause, stopped, Failures.describe(signal)));
            Channel channel = opened.createChannel();
            channel.addShutdownListener(signal -> stop(cause, stopped, Failures.describe(signal)));
            channel.basicQos(1);
            channel.basicConsume(source.queue(), false, (tag, delivery) -> {
                try {
                    settle(channel, delivery.getEnvelope().getDeliveryTag(), delivery.getBody());
                } catch (IOException | RuntimeException failedSettlement) {
                    // A commit or acknowledgement may already have succeeded. Closing lets idempotent replay decide.
                    stop(cause, stopped, "delivery settlement failed: " + Failures.describe(failedSettlement));
                }
            }, tag -> stop(cause, stopped, "the broker cancelled the consumer"));
            LOG.log(System.Logger.Level.INFO, () -> source.consumer() + " consumer connected");
            while (!closed.get() && opened.isOpen() && !stopped.await(1, TimeUnit.SECONDS)) {
                // Shutdown/cancellation wakes this wait; no polling of business state is needed.
            }
            // A closed connection can end the wait before its listener records the reason.
            return Objects.requireNonNullElseGet(cause.get(), () -> opened.getCloseReason() == null
                    ? "the connection closed" : Failures.describe(opened.getCloseReason()));
        } finally {
            if (closed.get()) {
                try {
                    opened.close(broker.timeoutMillis());
                } catch (IOException | RuntimeException failure) {
                    abort(opened);
                }
            } else {
                abort(opened);
            }
        }
    }

    void settle(Channel channel, long deliveryTag, byte[] body) throws IOException {
        Settlement settlement;
        try {
            settlement = Objects.requireNonNull(handler.handle(body), "settlement");
        } catch (RuntimeException failed) {
            // RabbitMQ 4.3 basic.reject increments the quorum delivery count; basic.nack does not.
            channel.basicReject(deliveryTag, true);
            LOG.log(System.Logger.Level.WARNING, () -> source.consumer()
                    + " transaction failed; requested broker retry: " + Failures.describe(failed));
            return;
        }
        switch (settlement) {
            case Settlement.Reject reject -> {
                channel.basicReject(deliveryTag, false);
                LOG.log(System.Logger.Level.WARNING, () -> "Malformed " + source.message()
                        + " rejected to its dead-letter queue: " + reject.reason());
            }
            case Settlement.Acknowledge acknowledge -> {
                if (acknowledge.warning() != null) {
                    LOG.log(System.Logger.Level.WARNING, acknowledge::warning);
                }
                channel.basicAck(deliveryTag, false);
            }
        }
    }

    private static void stop(AtomicReference<String> cause, CountDownLatch stopped, String reason) {
        cause.compareAndSet(null, reason);
        stopped.countDown();
    }

    private void abort(Connection opened) {
        try {
            opened.abort(broker.timeoutMillis());
        } catch (RuntimeException ignored) {
            // A failed connection is already unusable; unacknowledged deliveries remain broker-owned.
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            shutdown.countDown();
            try {
                if (started.get()) {
                    // The consume loop observes close within one second, then owns the connection close.
                    supervisor.join(broker.timeoutMillis() + 1000L);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                if (supervisor.isAlive()) {
                    supervisor.interrupt();
                }
            }
        }
    }

    /**
     * The queue, the names in log lines and the supervisor thread name. For example, consumer "Ledger command" logs
     * "Ledger command consumer connected", and message "ledger command" logs "Malformed ledger command rejected ...".
     */
    public record Source(String queue, String consumer, String message, String threadName) {
        public Source {
            if (queue == null || queue.isBlank() || consumer == null || consumer.isBlank()
                    || message == null || message.isBlank() || threadName == null || threadName.isBlank()) {
                throw new IllegalArgumentException("RabbitMQ consumer source is incomplete");
            }
        }
    }

    /** Runs the local transaction of one delivery body. A RuntimeException requests the broker's delayed retry. */
    @FunctionalInterface
    public interface Handler {
        Settlement handle(byte[] body);
    }

    /** The broker settlement after the handler's transaction committed. */
    public sealed interface Settlement {
        static Settlement acknowledge() {
            return new Acknowledge(null);
        }

        /** Acknowledges after a warning line, for example about a committed quarantine. */
        static Settlement acknowledge(String warning) {
            return new Acknowledge(Objects.requireNonNull(warning, "warning"));
        }

        /** Rejects a permanently invalid message to the dead-letter queue; the reason is a fixed rule text. */
        static Settlement reject(String reason) {
            return new Reject(reason);
        }

        record Acknowledge(String warning) implements Settlement {
        }

        record Reject(String reason) implements Settlement {
            public Reject {
                if (reason == null || reason.isBlank()) {
                    throw new IllegalArgumentException("A rejected message needs a rule text");
                }
            }
        }
    }
}
