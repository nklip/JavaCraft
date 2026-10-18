package dev.nklip.javacraft.shardshop.order.cdc;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** One ordered, confirmed command stream. A failed stream must be replaced by its supervisor. */
public final class RabbitCommandPublisher implements CommandPublisher {
    private static final String EXCHANGE = "ledger.commands";
    private static final String ROUTING_KEY = "ledger.record-order";

    private final ConnectionFactory factory;
    private final int timeoutMillis;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task ->
            Thread.ofPlatform().daemon().name("order-command-publisher").unstarted(task));
    private final AtomicBoolean returned = new AtomicBoolean();
    private volatile Connection connection;
    private volatile boolean closed;
    private Channel channel;

    public RabbitCommandPublisher(Settings settings) {
        this(settings, new ConnectionFactory());
    }

    RabbitCommandPublisher(Settings settings, ConnectionFactory factory) {
        Objects.requireNonNull(settings, "settings");
        this.factory = Objects.requireNonNull(factory, "factory");
        this.timeoutMillis = Math.toIntExact(settings.timeout().toMillis());
        factory.setHost(settings.host());
        factory.setPort(settings.port());
        factory.setVirtualHost(settings.virtualHost());
        factory.setUsername(settings.username());
        factory.setPassword(settings.password());
        factory.setConnectionTimeout(timeoutMillis);
        factory.setHandshakeTimeout(timeoutMillis);
        factory.setChannelRpcTimeout(timeoutMillis);
        factory.setShutdownTimeout(timeoutMillis);
        factory.setAutomaticRecoveryEnabled(false);
        factory.setTopologyRecoveryEnabled(false);
    }

    @Override
    public synchronized void publish(String messageId, byte[] envelope) throws Exception {
        if (closed) {
            throw new IllegalStateException("Publisher is closed");
        }
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("messageId is required");
        }
        Objects.requireNonNull(envelope, "envelope");
        if (envelope.length == 0) {
            throw new IllegalArgumentException("envelope is empty");
        }

        Future<?> attempt = worker.submit(() -> {
            publishOnWorker(messageId, envelope);
            return null;
        });
        try {
            attempt.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            attempt.cancel(true);
            invalidate();
            Thread.currentThread().interrupt();
            throw e;
        } catch (TimeoutException e) {
            attempt.cancel(true);
            invalidate();
            throw new TimeoutException("RabbitMQ command publication timed out");
        } catch (ExecutionException e) {
            invalidate();
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("RabbitMQ command publication failed", cause);
        }
    }

    private void publishOnWorker(String messageId, byte[] envelope) throws Exception {
        if (channel == null) {
            Connection opened = factory.newConnection();
            connection = opened;
            if (closed) {
                abort(opened);
                throw new IOException("Publisher closed while connecting");
            }
            Channel openedChannel = opened.createChannel();
            channel = openedChannel;
            if (closed) {
                throw new IOException("Publisher closed while opening a channel");
            }
            openedChannel.addReturnListener(message -> returned.set(true));
            openedChannel.confirmSelect();
        }

        returned.set(false);
        AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
                .deliveryMode(2)
                .contentType("application/json")
                .messageId(messageId)
                .build();
        channel.basicPublish(EXCHANGE, ROUTING_KEY, true, properties, envelope);
        boolean confirmed = channel.waitForConfirms(timeoutMillis);
        if (!confirmed) {
            throw new IOException("RabbitMQ negatively confirmed command publication");
        }
        if (returned.get()) {
            throw new IOException("RabbitMQ returned unroutable command publication");
        }
        if (closed) {
            throw new IOException("Publisher closed during publication");
        }
    }

    private void invalidate() {
        closed = true;
        worker.shutdownNow();
        Connection opened = connection;
        if (opened != null) {
            // Abort may encounter a stuck socket. Keep cleanup within the same finite budget.
            FutureTask<Void> cleanup = new FutureTask<>(() -> {
                abort(opened);
                return null;
            });
            Thread.ofPlatform().daemon().name("order-command-publisher-cleanup").start(cleanup);
            try {
                cleanup.get(timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | TimeoutException ignored) {
                // The channel is invalid regardless; the supervisor will create a new stream.
            }
        }
    }

    private void abort(Connection opened) {
        try {
            opened.abort(timeoutMillis);
        } catch (RuntimeException ignored) {
            // The connection is already unusable.
        }
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            invalidate();
        }
    }

    public record Settings(String host, int port, String virtualHost, String username,
                           String password, Duration timeout) {
        public Settings {
            if (host == null || host.isBlank() || virtualHost == null || virtualHost.isBlank()
                    || username == null || username.isBlank() || password == null) {
                throw new IllegalArgumentException("RabbitMQ connection settings are incomplete");
            }
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("RabbitMQ port is out of range");
            }
            if (timeout == null || timeout.toMillis() < 1 || timeout.toMillis() > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("RabbitMQ timeout is out of range");
            }
        }

        @Override
        public String toString() {
            return "Settings[host=" + host + ", port=" + port + ", virtualHost=" + virtualHost
                    + ", username=" + username + ", password=<redacted>, timeout=" + timeout + "]";
        }
    }
}
