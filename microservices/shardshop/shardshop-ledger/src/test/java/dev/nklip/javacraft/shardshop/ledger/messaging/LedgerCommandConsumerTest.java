package dev.nklip.javacraft.shardshop.ledger.messaging;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.ShutdownListener;
import com.rabbitmq.client.ShutdownSignalException;
import dev.nklip.javacraft.shardshop.ledger.command.CommandDisposition;
import dev.nklip.javacraft.shardshop.ledger.command.CommandOutcome;
import dev.nklip.javacraft.shardshop.ledger.command.MalformedCommandException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LedgerCommandConsumerTest {
    private static final LedgerCommandConsumer.Settings SETTINGS = new LedgerCommandConsumer.Settings(
            true, "localhost", 5672, "shardshop", "ledger", "private-value", Duration.ofSeconds(1));
    private static final CommandOutcome APPLIED = new CommandOutcome(CommandDisposition.APPLIED, 3, 4, null);

    @Test
    void acknowledgementFollowsSuccessfulTransactionForEveryDisposition() throws Exception {
        for (CommandDisposition disposition : CommandDisposition.values()) {
            Channel channel = mock(Channel.class);
            AtomicBoolean committed = new AtomicBoolean();
            doAnswer(invocation -> {
                assertTrue(committed.get(), "Business or quarantine commit precedes ACK");
                return null;
            }).when(channel).basicAck(17, false);
            try (var consumer = new LedgerCommandConsumer(SETTINGS, body -> {
                committed.set(true);
                return new CommandOutcome(disposition, 3, 4,
                        disposition == CommandDisposition.QUARANTINED ? "PAYLOAD_CONFLICT" : null);
            })) {
                consumer.settle(channel, 17, new byte[]{1});
            }
            verify(channel).basicAck(17, false);
            verifyNoMoreInteractions(channel);
        }
    }

    @Test
    void malformedCommandIsRejectedWithoutRequeue() throws Exception {
        Channel channel = mock(Channel.class);
        try (var consumer = new LedgerCommandConsumer(SETTINGS, body -> {
            throw new MalformedCommandException("Invalid JSON body");
        })) {
            consumer.settle(channel, 18, new byte[]{1});
        }
        verify(channel).basicReject(18, false);
        verifyNoMoreInteractions(channel);
    }

    @Test
    void failedTransactionUsesRejectToConsumeTheBrokersRetryBudget() throws Exception {
        Channel channel = mock(Channel.class);
        try (var consumer = new LedgerCommandConsumer(SETTINGS, body -> {
            throw new IllegalStateException("database unavailable");
        })) {
            consumer.settle(channel, 19, new byte[]{1});
        }
        verify(channel).basicReject(19, true);
        verifyNoMoreInteractions(channel);
    }

    @Test
    void acknowledgementFailureIsNotTurnedIntoASecondSettlement() throws Exception {
        Channel channel = mock(Channel.class);
        doThrow(new IOException("connection lost")).when(channel).basicAck(20, false);
        try (var consumer = new LedgerCommandConsumer(SETTINGS, body -> APPLIED)) {
            assertThrows(IOException.class, () -> consumer.settle(channel, 20, new byte[]{1}));
        }
        verify(channel).basicAck(20, false);
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
        }).when(channel).basicConsume(eq("ledger.record-order"), eq(false),
                any(com.rabbitmq.client.DeliverCallback.class), any(com.rabbitmq.client.CancelCallback.class));
        try (var consumer = new LedgerCommandConsumer(SETTINGS, body -> APPLIED, factory)) {
            consumer.start();
            assertTrue(consuming.await(5, TimeUnit.SECONDS), "Consumer reconnects after refused broker connection");
            consumer.start();
        }
        verify(factory, times(2)).newConnection();
        verify(channel).basicQos(1);
        verify(factory).setAutomaticRecoveryEnabled(false);
        verify(factory).setTopologyRecoveryEnabled(false);
        verify(connection).close(1000);
        verify(connection, never()).abort(anyInt());
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
        }).when(closedChannel).basicConsume(eq("ledger.record-order"), eq(false),
                any(com.rabbitmq.client.DeliverCallback.class), any(com.rabbitmq.client.CancelCallback.class));
        doAnswer(invocation -> {
            reconnected.countDown();
            return "replacement-consumer";
        }).when(replacementChannel).basicConsume(eq("ledger.record-order"), eq(false),
                any(com.rabbitmq.client.DeliverCallback.class), any(com.rabbitmq.client.CancelCallback.class));
        try (var consumer = new LedgerCommandConsumer(SETTINGS, body -> APPLIED, factory)) {
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
        var disabled = new LedgerCommandConsumer.Settings(false, "localhost", 5672, "shardshop", "ledger",
                "", Duration.ofSeconds(1));
        try (var consumer = new LedgerCommandConsumer(disabled, body -> APPLIED, factory)) {
            consumer.start();
        }
        verify(factory, never()).newConnection();
        assertFalse(SETTINGS.toString().contains("private-value"));
        assertThrows(IllegalArgumentException.class, () -> new LedgerCommandConsumer.Settings(true,
                "localhost", 5672, "shardshop", "ledger", "", Duration.ZERO));
    }
}
