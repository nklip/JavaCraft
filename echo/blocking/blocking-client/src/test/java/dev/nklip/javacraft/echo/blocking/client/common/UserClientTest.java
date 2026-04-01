package dev.nklip.javacraft.echo.blocking.client.common;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class UserClientTest {

    @Test
    void testReadUserMessagesShouldSendMessagesUntilBye() {
        UserClient client = Mockito.mock(
                UserClient.class,
                Mockito.withSettings()
                        .useConstructor("127.0.0.1", 8075)
                        .defaultAnswer(Mockito.CALLS_REAL_METHODS)
        );
        Mockito.doNothing().when(client).sendMessage(Mockito.anyString());
        Mockito.when(client.readMessage()).thenReturn("echo:hello", "Have a good day!");
        Logger logger = LoggerFactory.getLogger(UserClientTest.class);

        InputStream originalIn = System.in;
        try {
            System.setIn(new ByteArrayInputStream("hello\nbye\n".getBytes(StandardCharsets.UTF_8)));
            Assertions.assertDoesNotThrow(() -> client.readUserMessages(logger));
        } finally {
            System.setIn(originalIn);
        }

        InOrder inOrder = Mockito.inOrder(client);
        inOrder.verify(client).sendMessage("hello");
        inOrder.verify(client).readMessage();
        inOrder.verify(client).sendMessage("bye");
        inOrder.verify(client).readMessage();
        Mockito.verify(client, Mockito.times(1)).close();
    }

    @Test
    void testReadUserMessagesShouldHandleEofWithoutSendingMessage() {
        UserClient client = Mockito.mock(
                UserClient.class,
                Mockito.withSettings()
                        .useConstructor("127.0.0.1", 8075)
                        .defaultAnswer(Mockito.CALLS_REAL_METHODS)
        );
        Logger logger = LoggerFactory.getLogger(UserClientTest.class);

        InputStream originalIn = System.in;
        try {
            System.setIn(new ByteArrayInputStream(new byte[0]));
            Assertions.assertDoesNotThrow(() -> client.readUserMessages(logger));
        } finally {
            System.setIn(originalIn);
        }

        Mockito.verify(client, Mockito.never()).sendMessage(Mockito.anyString());
        Mockito.verify(client, Mockito.never()).readMessage();
        Mockito.verify(client, Mockito.times(1)).close();
    }

    @Test
    void testReadUserMessagesShouldHandleUnexpectedException() {
        UserClient client = Mockito.mock(
                UserClient.class,
                Mockito.withSettings()
                        .useConstructor("127.0.0.1", 8075)
                        .defaultAnswer(Mockito.CALLS_REAL_METHODS)
        );
        Mockito.doThrow(new RuntimeException("forced failure")).when(client).sendMessage("boom");
        Logger logger = LoggerFactory.getLogger(UserClientTest.class);

        InputStream originalIn = System.in;
        try {
            System.setIn(new ByteArrayInputStream("boom\n".getBytes(StandardCharsets.UTF_8)));
            Assertions.assertDoesNotThrow(() -> client.readUserMessages(logger));
        } finally {
            System.setIn(originalIn);
        }

        Mockito.verify(client, Mockito.never()).readMessage();
        Mockito.verify(client, Mockito.times(1)).close();
    }
}
