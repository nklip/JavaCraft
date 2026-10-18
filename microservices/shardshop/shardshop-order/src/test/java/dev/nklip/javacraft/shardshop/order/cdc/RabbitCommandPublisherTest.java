package dev.nklip.javacraft.shardshop.order.cdc;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.ReturnCallback;
import com.rabbitmq.client.ReturnListener;
import com.rabbitmq.client.Return;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RabbitCommandPublisherTest {
    private final ConnectionFactory factory = mock(ConnectionFactory.class);
    private final Connection connection = mock(Connection.class);
    private final Channel channel = mock(Channel.class);
    private final byte[] envelope = "{\"messageId\":\"42\"}".getBytes(StandardCharsets.UTF_8);

    private RabbitCommandPublisher publisher(Duration timeout) throws Exception {
        when(factory.newConnection()).thenReturn(connection);
        when(connection.createChannel()).thenReturn(channel);
        when(channel.waitForConfirms(anyLong())).thenReturn(true);
        return new RabbitCommandPublisher(new RabbitCommandPublisher.Settings(
                "rabbit", 5672, "/", "order", "secret", timeout), factory);
    }

    @Test
    void publishesPersistentJsonWithMandatoryRoutingAndWaitsForConfirm() throws Exception {
        try (var publisher = publisher(Duration.ofSeconds(2))) {
            publisher.publish("42", envelope);
            publisher.publish("43", envelope);
        }

        var properties = org.mockito.ArgumentCaptor.forClass(AMQP.BasicProperties.class);
        var order = inOrder(channel);
        order.verify(channel).confirmSelect();
        order.verify(channel).basicPublish(eq("ledger.commands"), eq("ledger.record-order"), eq(true),
                properties.capture(), same(envelope));
        order.verify(channel).waitForConfirms(anyLong());
        order.verify(channel).basicPublish(eq("ledger.commands"), eq("ledger.record-order"), eq(true),
                properties.capture(), same(envelope));
        order.verify(channel).waitForConfirms(anyLong());
        assertEquals(2, properties.getAllValues().getFirst().getDeliveryMode());
        assertEquals("application/json", properties.getAllValues().getFirst().getContentType());
        assertEquals("42", properties.getAllValues().getFirst().getMessageId());
        assertEquals("43", properties.getAllValues().get(1).getMessageId());
        verify(factory, times(1)).newConnection();
        verify(connection).abort(anyInt());
    }

    @Test
    void mandatoryReturnFailsEvenWhenBrokerAcknowledges() throws Exception {
        AtomicReference<ReturnCallback> callback = new AtomicReference<>();
        when(channel.addReturnListener(any(ReturnCallback.class))).thenAnswer(call -> {
            callback.set(call.getArgument(0));
            return mock(ReturnListener.class);
        });
        var publisher = publisher(Duration.ofSeconds(2));
        when(channel.waitForConfirms(anyLong())).thenAnswer(call -> {
            callback.get().handle(new Return(312, "NO_ROUTE", "ledger.commands", "ledger.record-order",
                    new AMQP.BasicProperties(), envelope));
            return true;
        });

        assertThrows(IOException.class, () -> publisher.publish("42", envelope));
        assertThrows(IllegalStateException.class, () -> publisher.publish("43", envelope));
        verify(connection).abort(anyInt());
        verify(channel, times(1)).basicPublish(anyString(), anyString(), eq(true), any(), any(byte[].class));
    }

    @Test
    void negativeConfirmInvalidatesStream() throws Exception {
        var publisher = publisher(Duration.ofSeconds(2));
        when(channel.waitForConfirms(anyLong())).thenReturn(false);

        assertThrows(IOException.class, () -> publisher.publish("42", envelope));
        assertThrows(IllegalStateException.class, () -> publisher.publish("43", envelope));
        verify(connection).abort(anyInt());
    }

    @Test
    void missingConfirmInvalidatesStream() throws Exception {
        var publisher = publisher(Duration.ofSeconds(2));
        when(channel.waitForConfirms(anyLong())).thenThrow(new TimeoutException("confirm"));

        assertThrows(TimeoutException.class, () -> publisher.publish("42", envelope));
        assertThrows(IllegalStateException.class, () -> publisher.publish("43", envelope));
        verify(connection).abort(anyInt());
    }

    @Test
    void blockedWriteTimesOutAndCannotPublishAnotherCommand() throws Exception {
        var publisher = publisher(Duration.ofMillis(150));
        CountDownLatch writeStarted = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);
        doAnswer(call -> {
            writeStarted.countDown();
            releaseWrite.await();
            return null;
        }).when(channel).basicPublish(anyString(), anyString(), eq(true), any(), any(byte[].class));

        try {
            assertThrows(TimeoutException.class, () -> publisher.publish("42", envelope));
            assertTrue(writeStarted.await(1, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () -> publisher.publish("43", envelope));
            verify(connection).abort(anyInt());
        } finally {
            releaseWrite.countDown();
        }
    }

    @Test
    void explicitCloseRejectsFurtherPublicationWithoutConnecting() throws Exception {
        var publisher = publisher(Duration.ofSeconds(1));
        publisher.close();

        assertThrows(IllegalStateException.class, () -> publisher.publish("42", envelope));
        verify(factory, never()).newConnection();
    }

    @Test
    void settingsNeverExposePasswordInToString() {
        var settings = new RabbitCommandPublisher.Settings("rabbit", 5672, "/", "order", "secret",
                Duration.ofSeconds(1));
        assertFalse(settings.toString().contains("secret"));
        assertThrows(IllegalArgumentException.class, () -> new RabbitCommandPublisher.Settings(
                "rabbit", 5672, "/", "order", "secret", Duration.ZERO));
    }
}
