package dev.nklip.javacraft.shardshop.common.amqp;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.impl.DefaultExceptionHandler;

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
import java.util.concurrent.atomic.AtomicReference;

/**
 * One ordered, confirmed stream of persistent JSON messages to one exchange and routing key, for the order CDC relay
 * and the ledger result relay. Only a positive confirm without a mandatory return completes a publication. A failed or
 * uncertain publication closes the stream, and its supervisor must create a new one. When the broker or the network
 * closed the stream while it was idle, the next publication opens a new connection: no publication of the old
 * connection is uncertain. The application owns the settings and credentials; each stream owns its one connection.
 */
public final class ConfirmedPublisher implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(ConfirmedPublisher.class.getName());

    private final Destination destination;
    private final ConnectionFactory factory;
    private final int timeoutMillis;
    private final ExecutorService worker;
    private final AtomicBoolean returned = new AtomicBoolean();
    /** The open connection; whoever takes it from here closes it, thus each connection closes once. */
    private final AtomicReference<Connection> live = new AtomicReference<>();
    private volatile boolean closed;
    private Channel channel;

    public ConfirmedPublisher(Settings settings, Destination destination) {
        this(settings, destination, new ConnectionFactory());
    }

    ConfirmedPublisher(Settings settings, Destination destination, ConnectionFactory factory) {
        Objects.requireNonNull(settings, "settings");
        this.destination = Objects.requireNonNull(destination, "destination");
        this.factory = Objects.requireNonNull(factory, "factory");
        this.timeoutMillis = Math.toIntExact(settings.timeout().toMillis());
        this.worker = Executors.newSingleThreadExecutor(task ->
                Thread.ofPlatform().daemon().name(destination.threadName()).unstarted(task));
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
        factory.setRequestedHeartbeat(10);
        factory.setThreadFactory(Thread.ofPlatform().daemon().name(destination.threadName() + "-amqp-", 0).factory());
        factory.setExceptionHandler(new DriverFailures());
    }

    /** Publishes one persistent JSON message with mandatory routing and waits for its broker confirm. */
    public synchronized void publish(String messageId, byte[] body) throws Exception {
        if (closed) {
            throw new IllegalStateException("Publisher is closed");
        }
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("messageId is required");
        }
        Objects.requireNonNull(body, "body");
        if (body.length == 0) {
            throw new IllegalArgumentException("body is empty");
        }
        Future<?> attempt = worker.submit(() -> {
            publishOnWorker(messageId, body);
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
            throw new TimeoutException("RabbitMQ " + destination.kind() + " publication timed out");
        } catch (ExecutionException e) {
            invalidate();
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("RabbitMQ " + destination.kind() + " publication failed", cause);
        }
    }

    private void publishOnWorker(String messageId, byte[] body) throws Exception {
        if (channel != null && !channel.isOpen()) {
            // All publications of the closed idle stream have their confirms, thus a new connection is safe.
            channel = null;
            Connection stale = live.getAndSet(null);
            if (stale != null) {
                abort(stale);
            }
        }
        if (channel == null) {
            connect();
        }

        returned.set(false);
        AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
                .deliveryMode(2)
                .contentType("application/json")
                .messageId(messageId)
                .build();
        channel.basicPublish(destination.exchange(), destination.routingKey(), true, properties, body);
        boolean confirmed;
        try {
            confirmed = channel.waitForConfirms(timeoutMillis);
        } catch (TimeoutException missing) {
            throw timeout("publication confirm", missing);
        }
        if (!confirmed) {
            throw new IOException("RabbitMQ negatively confirmed " + destination.kind() + " publication");
        }
        if (returned.get()) {
            throw new IOException("RabbitMQ returned unroutable " + destination.kind() + " publication");
        }
        if (closed) {
            throw new IOException("Publisher closed during publication");
        }
    }

    private void connect() throws Exception {
        Connection opened;
        try {
            opened = factory.newConnection();
        } catch (TimeoutException missing) {
            throw timeout("publisher connection", missing);
        }
        live.set(opened);
        if (closed) {
            // A concurrent invalidation that found no connection leaves this one to the worker.
            if (live.compareAndSet(opened, null)) {
                abort(opened);
            }
            throw new IOException("Publisher closed while connecting");
        }
        Channel openedChannel = opened.createChannel();
        channel = openedChannel;
        if (closed) {
            throw new IOException("Publisher closed while opening a channel");
        }
        openedChannel.addReturnListener(message -> returned.set(true));
        openedChannel.confirmSelect();
        LOG.log(System.Logger.Level.INFO, "RabbitMQ " + destination.kind() + " publisher connected to "
                + destination.exchange());
    }

    /** The client throws its timeouts without text; the message names the phase that reached its limit. */
    private TimeoutException timeout(String phase, TimeoutException cause) {
        TimeoutException named = new TimeoutException("RabbitMQ " + destination.kind() + " " + phase + " timed out");
        named.initCause(cause);
        return named;
    }

    private void invalidate() {
        closed = true;
        worker.shutdownNow();
        cleanup(false);
    }

    private void cleanup(boolean graceful) {
        Connection opened = live.getAndSet(null);
        if (opened == null) {
            return;
        }
        // A socket close may block; keep the caller within one finite budget.
        FutureTask<Void> cleanup = new FutureTask<>(() -> {
            if (graceful) {
                try {
                    opened.close(timeoutMillis);
                } catch (IOException | RuntimeException failure) {
                    abort(opened);
                }
            } else {
                abort(opened);
            }
            return null;
        });
        Thread.ofPlatform().daemon().name(destination.threadName() + "-cleanup").start(cleanup);
        try {
            cleanup.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException ignored) {
            // The connection is invalid regardless; the supervisor can create a fresh stream.
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
            closed = true;
            worker.shutdownNow();
            cleanup(true);
        }
    }

    /**
     * The client reports the socket close of a failed or aborted stream as an unexpected driver error. A publication
     * reports its own failure, and the next publication replaces a stream that closed while idle, thus this event is
     * only a debug line.
     */
    private final class DriverFailures extends DefaultExceptionHandler {
        @Override
        public void handleUnexpectedConnectionDriverException(Connection failed, Throwable failure) {
            LOG.log(System.Logger.Level.DEBUG, () -> "RabbitMQ " + destination.kind()
                    + " publisher connection stopped: " + failure);
        }
    }

    /**
     * The exchange and routing key of a stream, the kind in its messages (for example "result" in "RabbitMQ returned
     * unroutable result publication") and the name of its threads.
     */
    public record Destination(String exchange, String routingKey, String kind, String threadName) {
        public Destination {
            if (exchange == null || exchange.isBlank() || routingKey == null || routingKey.isBlank()
                    || kind == null || kind.isBlank() || threadName == null || threadName.isBlank()) {
                throw new IllegalArgumentException("RabbitMQ publisher destination is incomplete");
            }
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
