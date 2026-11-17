package dev.nklip.javacraft.shardshop.common.amqp;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.AlreadyClosedException;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.ExceptionHandler;
import com.rabbitmq.client.Return;
import com.rabbitmq.client.ReturnCallback;
import com.rabbitmq.client.ReturnListener;
import com.rabbitmq.client.ShutdownSignalException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.Mockito.*;

class ConfirmedPublisherTest {
    private static final ConfirmedPublisher.Destination COMMANDS = new ConfirmedPublisher.Destination(
            "ledger.commands", "ledger.record-order", "command", "test-command-publisher");
    private final ConnectionFactory factory = mock(ConnectionFactory.class);
    private final Connection connection = mock(Connection.class);
    private final Channel channel = mock(Channel.class);
    private final byte[] body = "{\"messageId\":\"42\"}".getBytes(StandardCharsets.UTF_8);
    private final Logger log = Logger.getLogger(ConfirmedPublisher.class.getName());
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

    private ConfirmedPublisher publisher(Duration timeout) throws Exception {
        when(factory.newConnection()).thenReturn(connection);
        when(connection.createChannel()).thenReturn(channel);
        when(channel.isOpen()).thenReturn(true);
        when(channel.waitForConfirms(anyLong())).thenReturn(true);
        return new ConfirmedPublisher(new BrokerSettings(
                "rabbit", 5672, "/", "order", "secret", timeout), COMMANDS, factory);
    }

    @Test
    void publishesExactBodiesAsMandatoryPersistentJsonAndWaitsForEachConfirm() throws Exception {
        try (var publisher = publisher(Duration.ofSeconds(1))) {
            publisher.publish("42", body);
            publisher.publish("43", body);
        }

        var properties = ArgumentCaptor.forClass(AMQP.BasicProperties.class);
        var order = inOrder(channel);
        order.verify(channel).confirmSelect();
        order.verify(channel).basicPublish(eq("ledger.commands"), eq("ledger.record-order"), eq(true),
                properties.capture(), aryEq(body));
        order.verify(channel).waitForConfirms(1000);
        order.verify(channel).basicPublish(eq("ledger.commands"), eq("ledger.record-order"), eq(true),
                properties.capture(), aryEq(body));
        order.verify(channel).waitForConfirms(1000);
        assertEquals(2, properties.getAllValues().getFirst().getDeliveryMode());
        assertEquals("application/json", properties.getAllValues().getFirst().getContentType());
        assertEquals("42", properties.getAllValues().getFirst().getMessageId());
        assertEquals("43", properties.getAllValues().get(1).getMessageId());
        verify(factory, times(1)).newConnection();
        verify(connection).close(1000);
        verify(connection, never()).abort(anyInt());
    }

    @Test
    void pipelinesTheEntireWindowBeforeWaitingForItsConfirms() throws Exception {
        try (var publisher = publisher(Duration.ofSeconds(1))) {
            publisher.publishBatch(List.of(new ConfirmedPublisher.Publication("42", body),
                    new ConfirmedPublisher.Publication("43", body), new ConfirmedPublisher.Publication("44", body)));
        }
        var sent = ArgumentCaptor.forClass(AMQP.BasicProperties.class);
        var order = inOrder(channel);
        order.verify(channel, times(3)).basicPublish(eq("ledger.commands"), eq("ledger.record-order"), eq(true),
                sent.capture(), aryEq(body));
        order.verify(channel).waitForConfirms(1000);
        assertEquals(List.of("42", "43", "44"), sent.getAllValues().stream()
                .map(AMQP.BasicProperties::getMessageId).toList());
        assertTrue(sent.getAllValues().stream().allMatch(p -> p.getDeliveryMode() == 2
                && "application/json".equals(p.getContentType())));
    }

    @Test
    void returnForOneMessageFailsTheWholePositivelyConfirmedWindow() throws Exception {
        AtomicReference<ReturnCallback> callback = returns();
        try (var publisher = publisher(Duration.ofSeconds(1))) {
            when(channel.waitForConfirms(anyLong())).thenAnswer(call -> {
                callback.get().handle(returned("43"));
                return true;
            });
            assertEquals("RabbitMQ returned unroutable command publication", assertThrows(IOException.class,
                    () -> publisher.publishBatch(List.of(new ConfirmedPublisher.Publication("42", body),
                            new ConfirmedPublisher.Publication("43", body)))).getMessage());
            assertThrows(IllegalStateException.class, () -> publisher.publish("42", body));
        }
        verify(channel, times(2)).basicPublish(anyString(), anyString(), eq(true), any(), any(byte[].class));
        verify(connection).abort(anyInt());
    }

    @Test
    void unknownOrMissingReturnIdentityFailsClosed() throws Exception {
        AtomicReference<ReturnCallback> callback = returns();
        try (var publisher = publisher(Duration.ofSeconds(1))) {
            when(channel.waitForConfirms(anyLong())).thenAnswer(call -> {
                callback.get().handle(returned(null));
                return true;
            });
            assertEquals("RabbitMQ returned an unknown command publication", assertThrows(IOException.class,
                    () -> publisher.publish("42", body)).getMessage());
        }
    }

    @Test
    void partialWriteFailureNeverReportsWindowSuccessAndInvalidatesStream() throws Exception {
        try (var publisher = publisher(Duration.ofSeconds(1))) {
            doNothing().doThrow(new IOException("socket closed")).when(channel)
                    .basicPublish(anyString(), anyString(), eq(true), any(), any(byte[].class));
            assertThrows(IOException.class, () -> publisher.publishBatch(List.of(
                    new ConfirmedPublisher.Publication("42", body), new ConfirmedPublisher.Publication("43", body),
                    new ConfirmedPublisher.Publication("44", body))));
            assertThrows(IllegalStateException.class, () -> publisher.publish("44", body));
        }
        verify(channel, times(2)).basicPublish(anyString(), anyString(), eq(true), any(), any(byte[].class));
        verify(channel, never()).waitForConfirms(anyLong());
        verify(connection).abort(anyInt());
    }

    @Test
    void negativeConfirmAppliesToTheWholeWindow() throws Exception {
        try (var publisher = publisher(Duration.ofSeconds(1))) {
            when(channel.waitForConfirms(anyLong())).thenReturn(false);
            assertThrows(IOException.class, () -> publisher.publishBatch(List.of(
                    new ConfirmedPublisher.Publication("42", body), new ConfirmedPublisher.Publication("43", body))));
            assertThrows(IllegalStateException.class, () -> publisher.publish("42", body));
        }
        verify(channel, times(2)).basicPublish(anyString(), anyString(), eq(true), any(), any(byte[].class));
        verify(channel).waitForConfirms(anyLong());
    }

    @Test
    void missingWindowConfirmHasOneBoundedDeadlineAndInvalidatesTheStream() throws Exception {
        var publisher = publisher(Duration.ofMillis(150));
        CountDownLatch release = new CountDownLatch(1);
        when(channel.waitForConfirms(anyLong())).thenAnswer(call -> {
            release.await();
            return true;
        });
        try {
            TimeoutException failure = assertThrows(TimeoutException.class, () -> publisher.publishBatch(List.of(
                    new ConfirmedPublisher.Publication("42", body), new ConfirmedPublisher.Publication("43", body))));
            assertEquals("RabbitMQ command publication confirm timed out", failure.getMessage());
            assertThrows(IllegalStateException.class, () -> publisher.publish("42", body));
            verify(channel, times(2)).basicPublish(anyString(), anyString(), eq(true), any(), any(byte[].class));
            verify(channel).waitForConfirms(150);
            verify(connection).abort(anyInt());
        } finally {
            release.countDown();
            publisher.close();
        }
    }

    @Test
    void disconnectDuringConfirmsFailsTheWholeWindow() throws Exception {
        try (var publisher = publisher(Duration.ofSeconds(1))) {
            when(channel.waitForConfirms(anyLong())).thenThrow(
                    new ShutdownSignalException(true, false, null, connection));
            assertThrows(ShutdownSignalException.class, () -> publisher.publishBatch(List.of(
                    new ConfirmedPublisher.Publication("42", body), new ConfirmedPublisher.Publication("43", body))));
            assertThrows(IllegalStateException.class, () -> publisher.publish("42", body));
        }
        verify(connection).abort(anyInt());
    }

    @Test
    void closeDuringWindowValidationRejectsPublicationAsClosed() throws Exception {
        var publisher = publisher(Duration.ofSeconds(5));
        var publications = spy(new ArrayList<>(List.of(new ConfirmedPublisher.Publication("42", body))));
        CountDownLatch validating = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        doAnswer(call -> {
            validating.countDown();
            assertTrue(resume.await(2, TimeUnit.SECONDS));
            return false;
        }).when(publications).isEmpty();
        try (var callers = Executors.newSingleThreadExecutor()) {
            var running = callers.submit(() -> { publisher.publishBatch(publications); return null; });
            try {
                assertTrue(validating.await(2, TimeUnit.SECONDS));
                assertTimeoutPreemptively(Duration.ofSeconds(1), publisher::close);
                resume.countDown();
                var failure = assertThrows(ExecutionException.class, () -> running.get(2, TimeUnit.SECONDS));
                assertInstanceOf(IllegalStateException.class, failure.getCause());
                assertEquals("Publisher is closed", failure.getCause().getMessage());
                verify(factory, never()).newConnection();
            } finally {
                resume.countDown();
                publisher.close();
            }
        }
    }

    @Test
    void closeInterruptsAWindowWaitingForConfirms() throws Exception {
        var publisher = publisher(Duration.ofSeconds(5));
        CountDownLatch confirming = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(channel.waitForConfirms(anyLong())).thenAnswer(call -> {
            confirming.countDown();
            release.await();
            return true;
        });
        try (var callers = Executors.newSingleThreadExecutor()) {
            var running = callers.submit(() -> { publisher.publish("42", body); return null; });
            try {
                assertTrue(confirming.await(2, TimeUnit.SECONDS));
                publisher.close();
                assertThrows(ExecutionException.class, () -> running.get(2, TimeUnit.SECONDS));
            } finally {
                release.countDown();
                publisher.close();
            }
        }
    }

    @Test
    void invalidWindowsAreRejectedBeforeBrokerIoAndPublicationBytesAreImmutable() throws Exception {
        try (var publisher = publisher(Duration.ofSeconds(1))) {
            var publication = new ConfirmedPublisher.Publication("42", body);
            assertThrows(IllegalArgumentException.class, () -> publisher.publishBatch(List.of()));
            assertThrows(IllegalArgumentException.class, () -> publisher.publishBatch(
                    java.util.Collections.nCopies(ConfirmedPublisher.MAX_BATCH_SIZE + 1, publication)));
            assertThrows(IllegalArgumentException.class, () -> publisher.publishBatch(List.of(publication, publication)));
            assertThrows(IllegalArgumentException.class, () -> new ConfirmedPublisher.Publication("43",
                    new byte[ConfirmedPublisher.MAX_BODY_BYTES + 1]));
            verify(factory, never()).newConnection();
            byte[] original = body.clone();
            body[0] = 0;
            publication.body()[0] = 0;
            publisher.publishBatch(List.of(publication));
            verify(channel).basicPublish(anyString(), anyString(), eq(true), any(), aryEq(original));
        }
    }

    private AtomicReference<ReturnCallback> returns() {
        AtomicReference<ReturnCallback> callback = new AtomicReference<>();
        when(channel.addReturnListener(any(ReturnCallback.class))).thenAnswer(call -> {
            callback.set(call.getArgument(0));
            return mock(ReturnListener.class);
        });
        return callback;
    }

    private Return returned(String messageId) {
        return new Return(312, "NO_ROUTE", "ledger.commands", "ledger.record-order",
                new AMQP.BasicProperties.Builder().messageId(messageId).build(), body);
    }

    @Test
    void mandatoryReturnFailsEvenAfterPositiveConfirmAndInvalidatesStream() throws Exception {
        AtomicReference<ReturnCallback> callback = new AtomicReference<>();
        when(channel.addReturnListener(any(ReturnCallback.class))).thenAnswer(call -> {
            callback.set(call.getArgument(0));
            return mock(ReturnListener.class);
        });
        var publisher = publisher(Duration.ofSeconds(1));
        when(channel.waitForConfirms(anyLong())).thenAnswer(call -> {
            callback.get().handle(new Return(312, "NO_ROUTE", "ledger.commands", "ledger.record-order",
                    new AMQP.BasicProperties.Builder().messageId("42").build(), body));
            return true;
        });

        assertEquals("RabbitMQ returned unroutable command publication",
                assertThrows(IOException.class, () -> publisher.publish("42", body)).getMessage());
        assertThrows(IllegalStateException.class, () -> publisher.publish("43", body));
        verify(connection).abort(anyInt());
        verify(channel, times(1)).basicPublish(anyString(), anyString(), eq(true), any(), any(byte[].class));
    }

    @Test
    void negativeConfirmInvalidatesStream() throws Exception {
        var publisher = publisher(Duration.ofSeconds(1));
        when(channel.waitForConfirms(anyLong())).thenReturn(false);

        assertEquals("RabbitMQ negatively confirmed command publication",
                assertThrows(IOException.class, () -> publisher.publish("42", body)).getMessage());
        assertThrows(IllegalStateException.class, () -> publisher.publish("43", body));
        verify(connection).abort(anyInt());
    }

    /** Regression for the step 4.6 validation: a retry log said only "TimeoutException". */
    @Test
    void missingConfirmNamesTheConfirmPhaseAndInvalidatesStream() throws Exception {
        var publisher = publisher(Duration.ofSeconds(1));
        TimeoutException clientTimeout = new TimeoutException();
        when(channel.waitForConfirms(anyLong())).thenThrow(clientTimeout);

        TimeoutException failure = assertThrows(TimeoutException.class, () -> publisher.publish("42", body));
        assertEquals("RabbitMQ command publication confirm timed out", failure.getMessage());
        assertSame(clientTimeout, failure.getCause());
        assertThrows(IllegalStateException.class, () -> publisher.publish("43", body));
        verify(connection).abort(anyInt());
    }

    @Test
    void connectionTimeoutNamesTheConnectionPhase() throws Exception {
        var publisher = publisher(Duration.ofSeconds(1));
        when(factory.newConnection()).thenThrow(new TimeoutException());

        assertEquals("RabbitMQ command publisher connection timed out",
                assertThrows(TimeoutException.class, () -> publisher.publish("42", body)).getMessage());
        assertThrows(IllegalStateException.class, () -> publisher.publish("43", body));
    }

    @Test
    void blockedSocketWriteHasBoundedPublicationDeadline() throws Exception {
        var publisher = publisher(Duration.ofMillis(150));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            release.await();
            return null;
        }).when(channel).basicPublish(anyString(), anyString(), eq(true), any(), any(byte[].class));
        try {
            assertEquals("RabbitMQ command publication timed out",
                    assertThrows(TimeoutException.class, () -> publisher.publish("42", body)).getMessage());
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () -> publisher.publish("43", body));
            verify(connection).abort(anyInt());
        } finally {
            release.countDown();
        }
    }

    /** A socket connect ignores interrupts; a connection that opens after the deadline must close, not leak. */
    @Test
    void connectionThatOpensAfterTheDeadlineIsAborted() throws Exception {
        var publisher = publisher(Duration.ofMillis(150));
        CountDownLatch release = new CountDownLatch(1);
        when(factory.newConnection()).thenAnswer(call -> {
            while (true) {
                try {
                    release.await();
                    return connection;
                } catch (InterruptedException ignored) {
                    // As a blocked socket connect, the open continues after the interrupt of the deadline.
                }
            }
        });

        assertThrows(TimeoutException.class, () -> publisher.publish("42", body));
        release.countDown();

        verify(connection, timeout(2_000)).abort(anyInt());
        verify(connection, never()).createChannel();
    }

    /** Regression for the step 4.6 validation: a closed idle stream failed the next publication one time. */
    @Test
    void closedIdleStreamReconnectsForTheNextPublicationAndLogsEachConnection() throws Exception {
        Connection second = mock(Connection.class);
        Channel fresh = mock(Channel.class);
        var publisher = publisher(Duration.ofSeconds(1));
        when(factory.newConnection()).thenReturn(connection, second);
        when(second.createChannel()).thenReturn(fresh);
        when(fresh.isOpen()).thenReturn(true);
        when(fresh.waitForConfirms(anyLong())).thenReturn(true);
        publisher.publish("42", body);
        when(channel.isOpen()).thenReturn(false);
        doThrow(new AlreadyClosedException(new ShutdownSignalException(true, false, null, connection)))
                .when(channel).basicPublish(anyString(), anyString(), anyBoolean(), any(), any(byte[].class));

        publisher.publish("43", body);

        verify(fresh).basicPublish(eq("ledger.commands"), eq("ledger.record-order"), eq(true), any(), aryEq(body));
        verify(fresh).waitForConfirms(1000);
        verify(connection).abort(anyInt());
        assertEquals(List.of("RabbitMQ command publisher connected to ledger.commands",
                        "RabbitMQ command publisher connected to ledger.commands"),
                records.stream().filter(record -> record.getLevel().intValue() == Level.INFO.intValue())
                        .map(LogRecord::getMessage).toList());
        publisher.close();
        verify(second).close(1000);
    }

    /** The publication reports the failure, so the client's driver error of the same socket is a debug line. */
    @Test
    void driverFailuresOfTheStreamAreOnlyDebugLines() throws Exception {
        publisher(Duration.ofSeconds(1));
        var handler = ArgumentCaptor.forClass(ExceptionHandler.class);
        verify(factory).setExceptionHandler(handler.capture());

        handler.getValue().handleUnexpectedConnectionDriverException(connection, new SocketException("Socket closed"));

        assertEquals(1, records.size());
        assertEquals(Level.FINE.intValue(), records.getFirst().getLevel().intValue());
        assertEquals("RabbitMQ command publisher connection stopped: java.net.SocketException: Socket closed",
                records.getFirst().getMessage());
    }

    @Test
    void closeBeforeStartNeedsNoConnectionAndInvalidPublicationsAreRejected() throws Exception {
        var publisher = publisher(Duration.ofSeconds(1));
        assertThrows(IllegalArgumentException.class, () -> publisher.publish(" ", body));
        assertThrows(IllegalArgumentException.class, () -> publisher.publish("42", new byte[0]));
        publisher.close();

        assertThrows(IllegalStateException.class, () -> publisher.publish("42", body));
        verify(factory, never()).newConnection();
    }

    @Test
    void settingsHideThePasswordAndRejectIncompleteValues() {
        var settings = new BrokerSettings("rabbit", 5672, "/", "order", "secret", Duration.ofSeconds(1));
        assertFalse(settings.toString().contains("secret"));
        assertThrows(IllegalArgumentException.class, () -> new BrokerSettings(
                "rabbit", 5672, "/", "order", "secret", Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new BrokerSettings(
                "rabbit", 0, "/", "order", "secret", Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new ConfirmedPublisher.Destination(
                "ledger.commands", " ", "command", "test-command-publisher"));
    }
}
