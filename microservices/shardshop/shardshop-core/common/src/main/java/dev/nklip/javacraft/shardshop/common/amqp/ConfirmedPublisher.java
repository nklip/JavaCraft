package dev.nklip.javacraft.shardshop.common.amqp;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.impl.DefaultExceptionHandler;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
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
    /** One pipelined window, bounded independently of the caller's fetch batch. */
    public static final int MAX_BATCH_SIZE = 16;
    public static final int MAX_BODY_BYTES = 1_048_576;

    private static final System.Logger LOG = System.getLogger(ConfirmedPublisher.class.getName());

    private final Destination destination;
    private final ConnectionFactory factory;
    private final int timeoutMillis;
    private final ExecutorService worker;
    private final AtomicBoolean closed = new AtomicBoolean();
    /** The open connection; whoever takes it from here closes it, thus each connection closes once. */
    private final AtomicReference<Connection> live = new AtomicReference<>();
    private Channel channel;
    private Returns returns;
    private volatile boolean awaitingConfirms;

    public ConfirmedPublisher(BrokerSettings settings, Destination destination) {
        this(settings, destination, new ConnectionFactory());
    }

    ConfirmedPublisher(BrokerSettings settings, Destination destination, ConnectionFactory factory) {
        Objects.requireNonNull(settings, "settings");
        this.destination = Objects.requireNonNull(destination, "destination");
        this.factory = Objects.requireNonNull(factory, "factory");
        this.timeoutMillis = settings.timeoutMillis();
        this.worker = Executors.newSingleThreadExecutor(task ->
                Thread.ofPlatform().daemon().name(destination.threadName()).unstarted(task));
        settings.configure(factory, destination.threadName() + "-amqp-");
        factory.setExceptionHandler(new DriverFailures());
    }

    /** Compatibility for a single-message window. */
    public void publish(String messageId, byte[] body) throws Exception {
        publishBatch(List.of(new Publication(messageId, body)));
    }

    /**
     * Sends a bounded window in order before waiting for all confirms. Success is all-or-nothing: a return,
     * negative/missing confirm or uncertain write invalidates the stream, and the caller may replay the whole
     * window with the original identities. No database work belongs inside this method.
     */
    public synchronized void publishBatch(List<Publication> publications) throws Exception {
        if (closed.get()) {
            throw new IllegalStateException("Publisher is closed");
        }
        Objects.requireNonNull(publications, "publications");
        if (publications.isEmpty() || publications.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("Publication window must contain 1.." + MAX_BATCH_SIZE + " messages");
        }
        List<Publication> batch = List.copyOf(publications);
        var ids = new HashSet<String>();
        for (Publication publication : batch) {
            if (!ids.add(publication.messageId())) {
                throw new IllegalArgumentException("Publication window contains duplicate message IDs");
            }
        }
        awaitingConfirms = false;
        Future<?> attempt;
        try {
            attempt = worker.submit(() -> {
                publishOnWorker(batch);
                return null;
            });
        } catch (RejectedExecutionException e) {
            // close() may shut down the worker after the initial closed check, without taking this monitor.
            throw new IllegalStateException("Publisher is closed", e);
        }
        try {
            attempt.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            attempt.cancel(true);
            invalidate();
            Thread.currentThread().interrupt();
            throw e;
        } catch (TimeoutException e) {
            String phase = awaitingConfirms ? "publication confirm" : "publication";
            attempt.cancel(true);
            invalidate();
            throw timeout(phase, e);
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

    private void publishOnWorker(List<Publication> batch) throws Exception {
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

        Returns current = returns;
        current.begin(batch);
        for (Publication publication : batch) {
            if (closed.get()) {
                throw new IOException("Publisher closed during publication");
            }
            AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
                    .deliveryMode(2)
                    .contentType("application/json")
                    .messageId(publication.messageId())
                    .build();
            channel.basicPublish(destination.exchange(), destination.routingKey(), true, properties, publication.body);
        }
        boolean confirmed;
        awaitingConfirms = true;
        try {
            // The RabbitMQ client tracks individual and cumulative ACK/NACK delivery tags for this channel.
            // It also dispatches a mandatory return before the corresponding confirm.
            confirmed = channel.waitForConfirms(timeoutMillis);
        } catch (TimeoutException missing) {
            throw timeout("publication confirm", missing);
        }
        if (!confirmed) {
            throw new IOException("RabbitMQ negatively confirmed " + destination.kind() + " publication");
        }
        current.finish();
        if (closed.get()) {
            throw new IOException("Publisher closed during publication");
        }
    }

    /** Owns immutable bytes so a caller cannot change an in-flight envelope. At most 16 MiB per window. */
    public record Publication(String messageId, byte[] body) {
        public Publication {
            if (messageId == null || messageId.isBlank()) {
                throw new IllegalArgumentException("messageId is required");
            }
            Objects.requireNonNull(body, "body");
            if (body.length == 0 || body.length > MAX_BODY_BYTES) {
                throw new IllegalArgumentException("Publication body must contain 1.." + MAX_BODY_BYTES + " bytes");
            }
            body = body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }

    /** Callback state belongs to one channel, so a late callback from an old connection cannot affect a new one. */
    private final class Returns {
        private Map<String, Boolean> publications = Map.of();
        private boolean unexpected;

        synchronized void begin(List<Publication> batch) throws IOException {
            if (unexpected) {
                throw new IOException("RabbitMQ returned an unknown " + destination.kind() + " publication");
            }
            publications = new HashMap<>();
            for (Publication publication : batch) {
                publications.put(publication.messageId(), false);
            }
        }

        synchronized void returned(String messageId) {
            if (messageId == null || !publications.containsKey(messageId)) {
                unexpected = true;
            } else {
                publications.put(messageId, true);
            }
        }

        synchronized void finish() throws IOException {
            if (unexpected) {
                throw new IOException("RabbitMQ returned an unknown " + destination.kind() + " publication");
            }
            if (publications.containsValue(true)) {
                throw new IOException("RabbitMQ returned unroutable " + destination.kind() + " publication");
            }
            publications = Map.of();
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
        if (closed.get()) {
            // A concurrent invalidation that found no connection leaves this one to the worker.
            if (live.compareAndSet(opened, null)) {
                abort(opened);
            }
            throw new IOException("Publisher closed while connecting");
        }
        Channel openedChannel = opened.createChannel();
        channel = openedChannel;
        if (closed.get()) {
            throw new IOException("Publisher closed while opening a channel");
        }
        Returns callbacks = new Returns();
        returns = callbacks;
        openedChannel.addReturnListener(message -> callbacks.returned(message.getProperties().getMessageId()));
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
        closed.set(true);
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
    public void close() {
        if (closed.compareAndSet(false, true)) {
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
}
