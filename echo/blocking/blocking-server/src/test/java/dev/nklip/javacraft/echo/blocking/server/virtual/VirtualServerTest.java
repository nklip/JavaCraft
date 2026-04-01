package dev.nklip.javacraft.echo.blocking.server.virtual;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class VirtualServerTest {

    @Test
    void testStartUpClientShouldHandleClientConversation() throws Exception {
        VirtualServer server = new VirtualServer(0);
        try (Socket client = Mockito.mock(Socket.class)) {
            ByteArrayInputStream input = new ByteArrayInputStream("stats\r\nbye\r\n".getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream output = new ByteArrayOutputStream();

            Mockito.when(client.getInputStream()).thenReturn(input);
            Mockito.when(client.getOutputStream()).thenReturn(output);
            Mockito.when(client.getPort()).thenReturn(22222);

            server.startUpClient(client);

            Mockito.verify(client, Mockito.timeout(2_000)).close();
            Assertions.assertEquals(
                    "Simultaneously connected clients: 1\r\nHave a good day!\r\n",
                    output.toString(StandardCharsets.UTF_8)
            );
        }
    }

    @Test
    void testStartUpClientShouldPropagateInitializationFailure() throws Exception {
        VirtualServer server = new VirtualServer(0);
        try (Socket client = Mockito.mock(Socket.class)) {
            Mockito.when(client.getInputStream()).thenThrow(new IOException("forced input failure"));

            Assertions.assertThrows(IllegalStateException.class, () -> server.startUpClient(client));
            Mockito.verify(client, Mockito.never()).close();
        }
    }
}
