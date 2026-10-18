package dev.nklip.javacraft.shardshop.ledger.messaging;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import dev.nklip.javacraft.shardshop.common.Failures;
import dev.nklip.javacraft.shardshop.ledger.command.CommandDisposition;
import dev.nklip.javacraft.shardshop.ledger.command.CommandOutcome;
import dev.nklip.javacraft.shardshop.ledger.command.MalformedCommandException;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** One manually acknowledged delivery at a time; business work finishes before broker settlement. */
public final class LedgerCommandConsumer implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(LedgerCommandConsumer.class);
    private static final String QUEUE = "ledger.record-order";
    private final Settings settings;
    private final Function<byte[], CommandOutcome> handler;
    private final ConnectionFactory factory;
    private final CountDownLatch shutdown = new CountDownLatch(1);
    private final Thread supervisor;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    public LedgerCommandConsumer(Settings settings, Function<byte[], CommandOutcome> handler) {
        this(settings, handler, new ConnectionFactory());
    }

    LedgerCommandConsumer(Settings settings, Function<byte[], CommandOutcome> handler, ConnectionFactory factory) {
        this.settings = Objects.requireNonNull(settings);
        this.handler = Objects.requireNonNull(handler);
        this.factory = Objects.requireNonNull(factory);
        int timeout = Math.toIntExact(settings.timeout().toMillis());
        factory.setHost(settings.host());
        factory.setPort(settings.port());
        factory.setVirtualHost(settings.virtualHost());
        factory.setUsername(settings.username());
        factory.setPassword(settings.password());
        factory.setConnectionTimeout(timeout);
        factory.setHandshakeTimeout(timeout);
        factory.setChannelRpcTimeout(timeout);
        factory.setShutdownTimeout(timeout);
        factory.setRequestedHeartbeat(10);
        factory.setAutomaticRecoveryEnabled(false);
        factory.setTopologyRecoveryEnabled(false);
        // The client owns its callback executor and drains shutdown callbacks before terminating it.
        // Its per-channel dispatcher serializes deliveries, with one in flight because prefetch is one.
        factory.setThreadFactory(Thread.ofPlatform().daemon().name("ledger-amqp-", 0).factory());
        supervisor = Thread.ofPlatform().daemon().name("ledger-command-consumer").unstarted(this::run);
    }

    public void start() {
        if (closed.get()) {
            throw new IllegalStateException("Ledger consumer is closed");
        }
        if (settings.enabled() && started.compareAndSet(false, true)) {
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
                LOG.warnf("Ledger command consumer stopped; retrying in %d ms: %s", backoff, cause);
                try {
                    if (shutdown.await(backoff, TimeUnit.MILLISECONDS)) {
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
            channel.basicConsume(QUEUE, false, (tag, delivery) -> {
                try {
                    settle(channel, delivery.getEnvelope().getDeliveryTag(), delivery.getBody());
                } catch (IOException | RuntimeException failedSettlement) {
                    // A commit or acknowledgement may already have succeeded. Closing lets idempotent replay decide.
                    stop(cause, stopped, "delivery settlement failed: " + Failures.describe(failedSettlement));
                }
            }, tag -> stop(cause, stopped, "the broker cancelled the consumer"));
            LOG.info("Ledger command consumer connected");
            while (!closed.get() && opened.isOpen() && !stopped.await(1, TimeUnit.SECONDS)) {
                // Shutdown/cancellation wakes this wait; no polling of business state is needed.
            }
            // A closed connection can end the wait before its listener records the reason.
            return Objects.requireNonNullElseGet(cause.get(), () -> opened.getCloseReason() == null
                    ? "the connection closed" : Failures.describe(opened.getCloseReason()));
        } finally {
            if (closed.get()) {
                try {
                    opened.close(Math.toIntExact(settings.timeout().toMillis()));
                } catch (IOException | RuntimeException failure) {
                    abort(opened);
                }
            } else {
                abort(opened);
            }
        }
    }

    void settle(Channel channel, long deliveryTag, byte[] body) throws IOException {
        CommandOutcome result;
        try {
            result = Objects.requireNonNull(handler.apply(body));
        } catch (MalformedCommandException malformed) {
            channel.basicReject(deliveryTag, false);
            LOG.warnf("Malformed ledger command rejected to its dead-letter queue: %s", malformed.reason());
            return;
        } catch (RuntimeException unavailable) {
            // RabbitMQ 4.3 basic.reject increments the quorum delivery count; basic.nack does not.
            channel.basicReject(deliveryTag, true);
            LOG.warnf("Ledger command transaction failed; requested broker retry: %s", Failures.describe(unavailable));
            return;
        }
        if (result.disposition() == CommandDisposition.QUARANTINED) {
            LOG.warnf("Conflicting ledger command %d for order %d committed to quarantine: %s",
                    result.messageId(), result.orderId(), result.conflict());
        }
        channel.basicAck(deliveryTag, false);
    }

    private static void stop(AtomicReference<String> cause, CountDownLatch stopped, String reason) {
        cause.compareAndSet(null, reason);
        stopped.countDown();
    }

    private void abort(Connection opened) {
        try {
            opened.abort(Math.toIntExact(settings.timeout().toMillis()));
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
                    supervisor.join(settings.timeout().toMillis() + 1000);
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

    public record Settings(boolean enabled, String host, int port, String virtualHost, String username,
                           String password, Duration timeout) {
        public Settings {
            if (host == null || host.isBlank() || virtualHost == null || virtualHost.isBlank()
                    || username == null || username.isBlank() || password == null) {
                throw new IllegalArgumentException("Ledger broker connection settings are incomplete");
            }
            if (port < 1 || port > 65535 || timeout == null || timeout.toMillis() < 1
                    || timeout.toMillis() > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Ledger broker port or timeout is out of range");
            }
        }

        @Override
        public String toString() {
            return "Settings[enabled=" + enabled + ", host=" + host + ", port=" + port + ", virtualHost="
                    + virtualHost + ", username=" + username + ", password=<redacted>, timeout=" + timeout + "]";
        }
    }
}
