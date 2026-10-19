package dev.nklip.javacraft.shardshop.common.amqp;

import com.rabbitmq.client.CancelCallback;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;
import com.rabbitmq.client.ShutdownListener;
import com.rabbitmq.client.ShutdownSignalException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QueueConsumerTest {
    private static final BrokerSettings BROKER = new BrokerSettings(
            "localhost", 5672, "shardshop", "ledger", "private-value", Duration.ofSeconds(1));
    private static final QueueConsumer.Source SOURCE = new QueueConsumer.Source(
            "ledger.record-order", "Ledger command", "ledger command", "test-command-consumer");
    private final Logger log = Logger.getLogger(QueueConsumer.class.getName());
    private final List<LogRecord> records = new CopyOnWriteArrayList<>();
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    @BeforeEach
    void captureLog() {
        log.addHandler(capture);
        log.setUseParentHandlers(false);
        log.setLevel(Level.ALL);
    }

    @AfterEach
    void releaseLog() {
        log.removeHandler(capture);
        log.setUseParentHandlers(true);
        log.setLevel(null);
    }

    private QueueConsumer consumer(QueueConsumer.Handler handler) {
        return new QueueConsumer(BROKER, true, SOURCE, handler, mock(ConnectionFactory.class));
    }

    private List<String> messages(Level level) {
        return records.stream().filter(record -> record.getLevel().intValue() == level.intValue())
                .map(LogRecord::getMessage).toList();
    }

    @Test
    void acknowledgementFollowsTheCommittedTransaction() throws Exception {
        Channel channel = mock(Channel.class);
        AtomicBoolean committed = new AtomicBoolean();
        doAnswer(invocation -> {
            assertTrue(committed.get(), "The local commit precedes the acknowledgement");
            return null;
        }).when(channel).basicAck(17, false);
        try (var consumer = consumer(body -> {
            committed.set(true);
            return QueueConsumer.Settlement.acknowledge();
        })) {
            consumer.settle(channel, 17, new byte[]{1});
        }
        verify(channel).basicAck(17, false);
        verifyNoMoreInteractions(channel);
        assertEquals(List.of(), records);
    }

    @Test
    void committedQuarantineIsLoggedBeforeItsAcknowledgement() throws Exception {
        Channel channel = mock(Channel.class);
        String warning = "Conflicting ledger command 3 for order 4 committed to quarantine: PAYLOAD_CONFLICT";
        doAnswer(invocation -> {
            assertEquals(List.of(warning), messages(Level.WARNING));
            return null;
        }).when(channel).basicAck(16, false);
        try (var consumer = consumer(body -> QueueConsumer.Settlement.acknowledge(warning))) {
            consumer.settle(channel, 16, new byte[]{1});
        }
        verify(channel).basicAck(16, false);
        verifyNoMoreInteractions(channel);
    }

    @Test
    void malformedMessageGoesToTheDeadLetterQueueWithItsRule() throws Exception {
        Channel channel = mock(Channel.class);
        try (var consumer = consumer(body -> QueueConsumer.Settlement.reject("Invalid JSON body"))) {
            consumer.settle(channel, 18, new byte[]{1});
        }
        verify(channel).basicReject(18, false);
        verifyNoMoreInteractions(channel);
        assertEquals(List.of("Malformed ledger command rejected to its dead-letter queue: Invalid JSON body"),
                messages(Level.WARNING));
        assertThrows(IllegalArgumentException.class, () -> QueueConsumer.Settlement.reject(" "));
    }

    /** RabbitMQ 4.3 counts basic.reject toward the delivery limit; the log names the cause chain. */
    @Test
    void failedTransactionRequestsTheBrokerRetryAndLogsItsCause() throws Exception {
        Channel channel = mock(Channel.class);
        try (var consumer = consumer(body -> {
            throw new IllegalStateException("Ledger transaction unavailable", new SQLException("Connection refused"));
        })) {
            consumer.settle(channel, 19, new byte[]{1});
        }
        try (var consumer = consumer(body -> null)) {
            consumer.settle(channel, 20, new byte[]{1});
        }
        verify(channel).basicReject(19, true);
        verify(channel).basicReject(20, true);
        verifyNoMoreInteractions(channel);
        assertEquals(List.of(
                "Ledger command transaction failed; requested broker retry: IllegalStateException: Ledger "
                        + "transaction unavailable <- SQLException: Connection refused",
                "Ledger command transaction failed; requested broker retry: NullPointerException: settlement"),
                messages(Level.WARNING));
    }

    @Test
    void acknowledgementFailureIsNotTurnedIntoASecondSettlement() throws Exception {
        Channel channel = mock(Channel.class);
        doThrow(new IOException("connection lost")).when(channel).basicAck(21, false);
        try (var consumer = consumer(body -> QueueConsumer.Settlement.acknowledge())) {
            assertThrows(IOException.class, () -> consumer.settle(channel, 21, new byte[]{1}));
        }
        verify(channel).basicAck(21, false);
        verifyNoMoreInteractions(channel);
    }

    @Test
    void initialBrokerFailureReconnectsWithManualAcknowledgementAndOnePrefetchedDelivery() throws Exception {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        Connection connection = mock(Connection.class);
        Channel channel = mock(Channel.class);
        when(factory.newConnection()).thenThrow(new IOException("unavailable")).thenReturn(connection);
        when(connection.createChannel()).thenReturn(channel);
        when(connection.isOpen()).thenReturn(true);
        CountDownLatch consuming = new CountDownLatch(1);
        doAnswer(invocation -> {
            consuming.countDown();
            return "test-consumer";
        }).when(channel).basicConsume(eq("ledger.record-order"), eq(false), any(DeliverCallback.class),
                any(CancelCallback.class));
        try (var consumer = new QueueConsumer(BROKER, true, SOURCE, body -> QueueConsumer.Settlement.acknowledge(),
                factory)) {
            consumer.start();
            assertTrue(consuming.await(5, TimeUnit.SECONDS), "Consumer reconnects after refused broker connection");
            consumer.start();
        }
        verify(factory, times(2)).newConnection();
        verify(channel).basicQos(1);
        verify(factory).setAutomaticRecoveryEnabled(false);
        verify(factory).setTopologyRecoveryEnabled(false);
        verify(factory).setRequestedHeartbeat(10);
        verify(factory).setConnectionTimeout(1000);
        verify(connection).close(1000);
        verify(connection, never()).abort(anyInt());
        List<String> warnings = messages(Level.WARNING);
        assertEquals(1, warnings.size(), warnings::toString);
        Matcher restart = Pattern.compile("Ledger command consumer stopped; retrying in (\\d+) ms: IOException: unavailable")
                .matcher(warnings.getFirst());
        assertTrue(restart.matches(), warnings.getFirst());
        long delay = Long.parseLong(restart.group(1));
        assertTrue(delay >= 375 && delay <= 625, "The first retry waits 500 ms with 25% jitter, not " + delay);
        assertEquals(List.of("Ledger command consumer connected"), messages(Level.INFO));
    }

    @Test
    void channelShutdownReconnectsEvenWhenTheConnectionRemainsOpen() throws Exception {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        Connection original = mock(Connection.class);
        Connection replacement = mock(Connection.class);
        Channel closedChannel = mock(Channel.class);
        Channel replacementChannel = mock(Channel.class);
        when(factory.newConnection()).thenReturn(original, replacement);
        when(original.createChannel()).thenReturn(closedChannel);
        when(replacement.createChannel()).thenReturn(replacementChannel);
        when(original.isOpen()).thenReturn(true);
        when(replacement.isOpen()).thenReturn(true);
        AtomicReference<ShutdownListener> shutdown = new AtomicReference<>();
        doAnswer(invocation -> {
            shutdown.set(invocation.getArgument(0));
            return null;
        }).when(closedChannel).addShutdownListener(any());
        CountDownLatch consuming = new CountDownLatch(1);
        CountDownLatch reconnected = new CountDownLatch(1);
        doAnswer(invocation -> {
            consuming.countDown();
            return "original-consumer";
        }).when(closedChannel).basicConsume(eq("ledger.record-order"), eq(false), any(DeliverCallback.class),
                any(CancelCallback.class));
        doAnswer(invocation -> {
            reconnected.countDown();
            return "replacement-consumer";
        }).when(replacementChannel).basicConsume(eq("ledger.record-order"), eq(false), any(DeliverCallback.class),
                any(CancelCallback.class));
        try (var consumer = new QueueConsumer(BROKER, true, SOURCE, body -> QueueConsumer.Settlement.acknowledge(),
                factory)) {
            consumer.start();
            assertTrue(consuming.await(5, TimeUnit.SECONDS));
            ShutdownListener listener = shutdown.get();
            if (listener != null) {
                listener.shutdownCompleted(new ShutdownSignalException(false, false, null, closedChannel));
            }
            assertTrue(reconnected.await(5, TimeUnit.SECONDS),
                    "Channel-only shutdown must restart consumption without waiting for connection loss");
        }
        verify(original, atLeastOnce()).abort(1000);
        verify(replacementChannel).basicQos(1);
    }

    @Test
    void disabledOrUnstartedConsumerDoesNotOpenExternalConnections() throws Exception {
        ConnectionFactory factory = mock(ConnectionFactory.class);
        try (var consumer = new QueueConsumer(BROKER, true, SOURCE, body -> QueueConsumer.Settlement.acknowledge(),
                factory)) {
            verify(factory, never()).newConnection();
        }
        try (var consumer = new QueueConsumer(BROKER, false, SOURCE, body -> QueueConsumer.Settlement.acknowledge(),
                factory)) {
            consumer.start();
        }
        verify(factory, never()).newConnection();
        var closed = new QueueConsumer(BROKER, true, SOURCE, body -> QueueConsumer.Settlement.acknowledge(), factory);
        closed.close();
        assertThrows(IllegalStateException.class, closed::start);
        assertThrows(IllegalArgumentException.class, () -> new QueueConsumer.Source(
                "ledger.record-order", "Ledger command", " ", "test-command-consumer"));
    }
}
