package dev.nklip.javacraft.echo.blocking.server.common;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ServerThreadTest {

    @Test
    void testShouldHandleEmptyStatsEchoAndByeWithPerInstanceCounter() throws Exception {
        AtomicInteger counter = new AtomicInteger(0);
        Socket socket = Mockito.mock(Socket.class);
        ByteArrayInputStream input = new ByteArrayInputStream(("\r\nstats\r\nhello world\r\nbye\r\n")
                .getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        Mockito.when(socket.getInputStream()).thenReturn(input);
        Mockito.when(socket.getOutputStream()).thenReturn(output);
        Mockito.when(socket.getPort()).thenReturn(10101);

        new ServerThread(socket, counter).run();

        Assertions.assertEquals(0, counter.get(), "Counter should be decremented when connection is closed");
        String expected = """
                Please type something.
                Simultaneously connected clients: 1
                Did you say 'hello world'?
                Have a good day!
                """.replace("\n", "\r\n");
        Assertions.assertEquals(
                expected,
                output.toString(StandardCharsets.UTF_8)
        );
        Mockito.verify(socket).close();
    }

    @Test
    void testShouldKeepStatsIndependentForDifferentServerInstances() throws Exception {
        AtomicInteger firstCounter = new AtomicInteger(0);
        AtomicInteger secondCounter = new AtomicInteger(0);
        Socket firstSocket = Mockito.mock(Socket.class);
        Socket secondSocket = Mockito.mock(Socket.class);
        ByteArrayOutputStream firstOutput = new ByteArrayOutputStream();
        ByteArrayOutputStream secondOutput = new ByteArrayOutputStream();

        Mockito.when(firstSocket.getInputStream())
                .thenReturn(new ByteArrayInputStream("stats\r\nbye\r\n".getBytes(StandardCharsets.UTF_8)));
        Mockito.when(firstSocket.getOutputStream()).thenReturn(firstOutput);
        Mockito.when(firstSocket.getPort()).thenReturn(11111);

        Mockito.when(secondSocket.getInputStream())
                .thenReturn(new ByteArrayInputStream("stats\r\nbye\r\n".getBytes(StandardCharsets.UTF_8)));
        Mockito.when(secondSocket.getOutputStream()).thenReturn(secondOutput);
        Mockito.when(secondSocket.getPort()).thenReturn(22222);

        new ServerThread(firstSocket, firstCounter).run();
        new ServerThread(secondSocket, secondCounter).run();

        Assertions.assertEquals(0, firstCounter.get());
        Assertions.assertEquals(0, secondCounter.get());

        String expected = "Simultaneously connected clients: 1\r\nHave a good day!\r\n";
        Assertions.assertEquals(expected, firstOutput.toString(StandardCharsets.UTF_8));
        Assertions.assertEquals(expected, secondOutput.toString(StandardCharsets.UTF_8));
        Mockito.verify(firstSocket).close();
        Mockito.verify(secondSocket).close();
    }

    @Test
    void testConstructorShouldFailFastWhenSocketStreamInitFails() throws Exception {
        AtomicInteger counter = new AtomicInteger(0);
        try (Socket socket = Mockito.mock(Socket.class)) {
            Mockito.when(socket.getInputStream()).thenThrow(new IOException("forced input failure"));

            Assertions.assertThrows(IllegalStateException.class, () -> new ServerThread(socket, counter));
        }
        Assertions.assertEquals(0, counter.get(), "Counter should not change when constructor fails");
    }

    @Test
    void testConstructorShouldFailFastWhenSocketIsNull() {
        AtomicInteger counter = new AtomicInteger(0);
        Assertions.assertThrows(NullPointerException.class, () -> new ServerThread(null, counter));
        Assertions.assertEquals(0, counter.get());
    }

    @Test
    void testConstructorShouldFailFastWhenCounterIsNull() throws Exception {
        try (Socket socket = Mockito.mock(Socket.class)) {
            Assertions.assertThrows(NullPointerException.class, () -> new ServerThread(socket, null));
        }
    }

    @Test
    void testConstructorShouldNotIncrementCounterBeforeRun() throws Exception {
        AtomicInteger counter = new AtomicInteger(0);
        try (Socket socket = Mockito.mock(Socket.class)) {
            Mockito.when(socket.getInputStream())
                    .thenReturn(new ByteArrayInputStream("bye\r\n".getBytes(StandardCharsets.UTF_8)));
            Mockito.when(socket.getOutputStream()).thenReturn(new ByteArrayOutputStream());

            new ServerThread(socket, counter);

            Assertions.assertEquals(0, counter.get(), "Counter should be incremented in run(), not constructor");
        }
    }

    @Test
    void testConstructorShouldConfigureDefaultReadTimeout() throws Exception {
        AtomicInteger counter = new AtomicInteger(0);
        try (Socket socket = Mockito.mock(Socket.class)) {
            Mockito.when(socket.getInputStream())
                    .thenReturn(new ByteArrayInputStream("bye\r\n".getBytes(StandardCharsets.UTF_8)));
            Mockito.when(socket.getOutputStream()).thenReturn(new ByteArrayOutputStream());

            new ServerThread(socket, counter);

            Mockito.verify(socket).setSoTimeout(2_000);
        }
    }

    @Test
    void testRunShouldCatchIOExceptionAndStillDecrementCounter() throws Exception {
        AtomicInteger counter = new AtomicInteger(0);
        Socket socket = Mockito.mock(Socket.class);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        Mockito.when(socket.getInputStream()).thenReturn(new InputStreamThatAlwaysFails());
        Mockito.when(socket.getOutputStream()).thenReturn(output);
        Mockito.when(socket.getPort()).thenReturn(30303);

        new ServerThread(socket, counter).run();

        Assertions.assertEquals(0, counter.get(), "Counter must be decremented even on I/O failure");
        Assertions.assertEquals("", output.toString(StandardCharsets.UTF_8));
        Mockito.verify(socket).close();
    }

    @Test
    void testRunShouldRethrowRuntimeExceptionAndStillDecrementCounter() throws Exception {
        AtomicInteger counter = new AtomicInteger(0);
        Socket socket = Mockito.mock(Socket.class);

        Mockito.when(socket.getInputStream())
                .thenReturn(new ByteArrayInputStream("hello\r\n".getBytes(StandardCharsets.UTF_8)));
        Mockito.when(socket.getOutputStream()).thenReturn(new RuntimeFailingOutputStream());
        Mockito.when(socket.getPort()).thenReturn(40404);

        Assertions.assertThrows(RuntimeException.class, () -> new ServerThread(socket, counter).run());

        Assertions.assertEquals(0, counter.get(), "Counter must be decremented on runtime failures too");
        Mockito.verify(socket).close();
    }

    @Test
    void testRunShouldNotDecrementCounterTwiceWhenInvokedAgain() throws Exception {
        AtomicInteger counter = new AtomicInteger(0);
        Socket socket = Mockito.mock(Socket.class);

        Mockito.when(socket.getInputStream())
                .thenReturn(new ByteArrayInputStream("bye\r\n".getBytes(StandardCharsets.UTF_8)));
        Mockito.when(socket.getOutputStream()).thenReturn(new ByteArrayOutputStream());
        Mockito.when(socket.getPort()).thenReturn(50505);

        ServerThread serverThread = new ServerThread(socket, counter);
        serverThread.run();
        serverThread.run();

        Assertions.assertEquals(0, counter.get(), "Counter should never go negative");
    }

    @Test
    void testCloseShouldHandleSocketCloseFailure() throws Exception {
        AtomicInteger counter = new AtomicInteger(0);
        Socket socket = Mockito.mock(Socket.class);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        Mockito.when(socket.getInputStream())
                .thenReturn(new ByteArrayInputStream("bye\r\n".getBytes(StandardCharsets.UTF_8)));
        Mockito.when(socket.getOutputStream()).thenReturn(output);
        Mockito.when(socket.getPort()).thenReturn(60606);
        Mockito.doThrow(new IOException("forced close failure")).when(socket).close();

        Assertions.assertDoesNotThrow(() -> new ServerThread(socket, counter).run());
        Assertions.assertEquals(0, counter.get());
        Assertions.assertEquals("Have a good day!\r\n", output.toString(StandardCharsets.UTF_8));
    }

    private static final class InputStreamThatAlwaysFails extends java.io.InputStream {
        @Override
        public int read() throws IOException {
            throw new IOException("forced read failure");
        }
    }

    private static final class RuntimeFailingOutputStream extends java.io.OutputStream {
        @Override
        public void write(int b) {
            throw new RuntimeException("forced runtime write failure");
        }
    }
}
