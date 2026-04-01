package dev.nklip.javacraft.echo.blocking.client.virtual;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;

class VirtualThreadClientTest {

    @Test
    void testSendMessageShouldWriteToServer() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            AtomicReference<String> receivedMessage = new AtomicReference<>();
            AtomicReference<Throwable> acceptThreadFailure = new AtomicReference<>();

            Thread acceptThread = Thread.ofVirtual().start(() -> {
                try (Socket accepted = serverSocket.accept();
                     BufferedReader serverReader = new BufferedReader(new InputStreamReader(accepted.getInputStream()))) {
                    receivedMessage.set(serverReader.readLine());
                } catch (Exception e) {
                    acceptThreadFailure.set(e);
                }
            });

            try (VirtualThreadClient client = new VirtualThreadClient(
                    "sync-client",
                    "127.0.0.1",
                    serverSocket.getLocalPort())) {
                client.sendMessage("hello");
            }

            acceptThread.join(Duration.ofSeconds(2));
            Assertions.assertFalse(acceptThread.isAlive());
            Assertions.assertEquals("hello", receivedMessage.get());
            Assertions.assertNull(acceptThreadFailure.get());
        }
    }

    @Test
    void testReadMessageShouldReturnServerResponse() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            AtomicReference<Throwable> acceptThreadFailure = new AtomicReference<>();
            Thread acceptThread = Thread.ofVirtual().start(() -> {
                try (Socket accepted = serverSocket.accept();
                     PrintWriter serverWriter = new PrintWriter(accepted.getOutputStream(), true)) {
                    serverWriter.println("pong");
                } catch (Exception e) {
                    acceptThreadFailure.set(e);
                }
            });

            try (VirtualThreadClient client = new VirtualThreadClient(
                    "sync-client",
                    "127.0.0.1",
                    serverSocket.getLocalPort())) {
                Assertions.assertEquals("pong", client.readMessage());
            }

            acceptThread.join(Duration.ofSeconds(2));
            Assertions.assertFalse(acceptThread.isAlive());
            Assertions.assertNull(acceptThreadFailure.get());
        }
    }

    @Test
    void testReadMessageShouldReturnNullAndKeepInterruptStatus() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            CountDownLatch releaseServerSocket = new CountDownLatch(1);
            AtomicReference<Throwable> acceptThreadFailure = new AtomicReference<>();

            Thread acceptThread = Thread.ofVirtual().start(() -> {
                try (Socket ignored = serverSocket.accept()) {
                    Assertions.assertTrue(releaseServerSocket.await(2, TimeUnit.SECONDS));
                } catch (Exception e) {
                    acceptThreadFailure.set(e);
                }
            });

            try (VirtualThreadClient client = new VirtualThreadClient(
                    "sync-client",
                    "127.0.0.1",
                    serverSocket.getLocalPort())) {
                Thread.currentThread().interrupt();
                Assertions.assertNull(client.readMessage());
                Assertions.assertTrue(Thread.currentThread().isInterrupted());
                Assertions.assertTrue(Thread.interrupted());
            } finally {
                releaseServerSocket.countDown();
            }

            acceptThread.join(Duration.ofSeconds(2));
            Assertions.assertFalse(acceptThread.isAlive());
            Assertions.assertNull(acceptThreadFailure.get());
        }
    }

    @Test
    void testConstructorShouldThrowWhenConnectionCannotBeEstablished() {

        IllegalStateException exception = Assertions.assertThrows(
                IllegalStateException.class,
                () -> {
                    try (VirtualThreadClient ignored = new VirtualThreadClient(
                            "sync-client", "127.0.0.1", 1)) {
                        Assertions.fail("Constructor should fail before entering try block");
                    }
                }
        );

        Assertions.assertTrue(exception.getMessage().contains("127.0.0.1:1"));
        Assertions.assertNotNull(exception.getCause());
    }

    @Test
    void testConstructorShouldUseExplicitConnectTimeout() throws Exception {
        AtomicReference<Socket> socketUsedByClient = new AtomicReference<>();
        try (MockedConstruction<Socket> ignoredSocketConstruction = Mockito.mockConstruction(
                Socket.class,
                (mock, context) -> {
                    socketUsedByClient.set(mock);
                    Mockito.when(mock.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
                    Mockito.when(mock.getOutputStream()).thenReturn(new ByteArrayOutputStream());
                })) {
            try (VirtualThreadClient ignored = new VirtualThreadClient(
                    "sync-client",
                    "127.0.0.1",
                    8080)) {
                Assertions.assertNotNull(ignored);
            }
        }

        Assertions.assertNotNull(socketUsedByClient.get());
        Mockito.verify(socketUsedByClient.get(), Mockito.times(1)).connect(
                Mockito.argThat(address -> {
                    if (!(address instanceof InetSocketAddress socketAddress)) {
                        return false;
                    }
                    return "127.0.0.1".equals(socketAddress.getHostString())
                            && socketAddress.getPort() == 8080;
                }),
                Mockito.eq(1_000)
        );
    }

    @Test
    void testConstructorShouldUseUtf8ForSocketStreams() {
        AtomicReference<List<?>> inputReaderConstructorArguments = new AtomicReference<>();
        AtomicReference<List<?>> printWriterConstructorArguments = new AtomicReference<>();
        try (MockedConstruction<Socket> ignoredSocketConstruction = Mockito.mockConstruction(
                Socket.class,
                (mock, context) -> {
                    Mockito.when(mock.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
                    Mockito.when(mock.getOutputStream()).thenReturn(new ByteArrayOutputStream());
                });
             MockedConstruction<InputStreamReader> ignoredInputStreamReaderConstruction = Mockito.mockConstruction(
                     InputStreamReader.class,
                     (mock, context) -> {
                         inputReaderConstructorArguments.set(new ArrayList<>(context.arguments()));
                         Mockito.when(mock.read(Mockito.any(char[].class), Mockito.anyInt(), Mockito.anyInt()))
                                 .thenReturn(-1);
                     });
             MockedConstruction<PrintWriter> ignoredPrintWriterConstruction = Mockito.mockConstruction(
                     PrintWriter.class,
                     (mock, context) -> printWriterConstructorArguments.set(new ArrayList<>(context.arguments())))) {
            try (VirtualThreadClient ignored = new VirtualThreadClient(
                    "sync-client",
                    "127.0.0.1",
                    8080)) {
                Assertions.assertNotNull(ignored);
            }
        }

        Assertions.assertNotNull(inputReaderConstructorArguments.get());
        Assertions.assertEquals(2, inputReaderConstructorArguments.get().size());
        Assertions.assertEquals(StandardCharsets.UTF_8, inputReaderConstructorArguments.get().get(1));

        Assertions.assertNotNull(printWriterConstructorArguments.get());
        Assertions.assertEquals(2, printWriterConstructorArguments.get().size());
        Assertions.assertEquals(Boolean.TRUE, printWriterConstructorArguments.get().get(1));
        Assertions.assertEquals(
                "java.io.OutputStreamWriter",
                printWriterConstructorArguments.get().get(0).getClass().getName()
        );
    }

    @Test
    void testRunShouldHandleIllegalStateExceptionFromSendMessage() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0);
             VirtualThreadClient client = new VirtualThreadClient(
                     "sync-client",
                     "127.0.0.1",
                     serverSocket.getLocalPort())) {
            client.close();
            InputStream originalIn = System.in;
            try {
                System.setIn(new ByteArrayInputStream("hello\n".getBytes(StandardCharsets.UTF_8)));
                Assertions.assertDoesNotThrow(client::run);
            } finally {
                System.setIn(originalIn);
            }
        }
    }

    @Test
    void testRunShouldPreserveWhitespaceInInteractiveMode() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            List<String> receivedMessages = new ArrayList<>();
            AtomicReference<Throwable> acceptThreadFailure = new AtomicReference<>();
            Thread acceptThread = Thread.ofVirtual().start(() -> {
                try (Socket accepted = serverSocket.accept();
                     BufferedReader serverReader = new BufferedReader(new InputStreamReader(accepted.getInputStream()));
                     PrintWriter serverWriter = new PrintWriter(accepted.getOutputStream(), true)) {
                    for (int i = 0; i < 3; i++) {
                        String message = serverReader.readLine();
                        receivedMessages.add(message);
                        serverWriter.println("echo:" + message);
                    }
                } catch (Exception e) {
                    acceptThreadFailure.set(e);
                }
            });

            try (VirtualThreadClient client = new VirtualThreadClient(
                    "sync-client",
                    "127.0.0.1",
                    serverSocket.getLocalPort())) {
                InputStream originalIn = System.in;
                try {
                    System.setIn(new ByteArrayInputStream(
                            "  hello  \n   \nbye\n".getBytes(StandardCharsets.UTF_8)
                    ));
                    Assertions.assertDoesNotThrow(client::run);
                } finally {
                    System.setIn(originalIn);
                }
            }

            acceptThread.join(Duration.ofSeconds(2));
            Assertions.assertFalse(acceptThread.isAlive());
            Assertions.assertEquals(List.of("  hello  ", "   ", "bye"), receivedMessages);
            Assertions.assertNull(acceptThreadFailure.get());
        }
    }

    @Test
    void testRunShouldHandleIOExceptionFromConsoleInput() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            CountDownLatch releaseServerSocket = new CountDownLatch(1);
            AtomicReference<Throwable> acceptThreadFailure = new AtomicReference<>();
            Thread acceptThread = Thread.ofVirtual().start(() -> {
                try (Socket ignored = serverSocket.accept()) {
                    Assertions.assertTrue(releaseServerSocket.await(2, TimeUnit.SECONDS));
                } catch (Exception e) {
                    acceptThreadFailure.set(e);
                }
            });

            try (VirtualThreadClient client = new VirtualThreadClient(
                    "sync-client",
                    "127.0.0.1",
                    serverSocket.getLocalPort())) {
                InputStream originalIn = System.in;
                try {
                    System.setIn(new InputStream() {
                        @Override
                        public int read() throws IOException {
                            throw new IOException("forced read failure");
                        }
                    });
                    Assertions.assertDoesNotThrow(client::run);
                } finally {
                    System.setIn(originalIn);
                }
            } finally {
                releaseServerSocket.countDown();
            }

            acceptThread.join(Duration.ofSeconds(2));
            Assertions.assertFalse(acceptThread.isAlive());
            Assertions.assertNull(acceptThreadFailure.get());
        }
    }

    @Test
    void testIsConnectedShouldBeFalseAfterRemoteClose() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            AtomicReference<Throwable> acceptThreadFailure = new AtomicReference<>();
            Thread acceptThread = Thread.ofVirtual().start(() -> {
                try (Socket accepted = serverSocket.accept()) {
                    SocketAddress remoteAddress = accepted.getRemoteSocketAddress();
                    if (remoteAddress == null) {
                        throw new IllegalStateException("Accepted socket must have remote address");
                    }
                } catch (Exception e) {
                    acceptThreadFailure.set(e);
                }
            });

            try (VirtualThreadClient client = new VirtualThreadClient(
                    "sync-client",
                    "127.0.0.1",
                    serverSocket.getLocalPort())) {

                Assertions.assertTrue(client.isConnected());
                awaitServerCloseObserved(client);
                Assertions.assertFalse(client.isConnected());
            }

            acceptThread.join(Duration.ofSeconds(2));
            Assertions.assertFalse(acceptThread.isAlive());
            Assertions.assertNull(acceptThreadFailure.get());
        }
    }

    @Test
    void testSendMessageShouldThrowWhenWriterSignalsError() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0);
             MockedConstruction<PrintWriter> ignoredWriterConstruction = Mockito.mockConstruction(
                     PrintWriter.class,
                     (mock, context) -> Mockito.when(mock.checkError()).thenReturn(true))) {
            CountDownLatch releaseServerSocket = new CountDownLatch(1);
            AtomicReference<Throwable> acceptThreadFailure = new AtomicReference<>();

            Thread acceptThread = Thread.ofVirtual().start(() -> {
                try (Socket ignored = serverSocket.accept()) {
                    Assertions.assertTrue(releaseServerSocket.await(2, TimeUnit.SECONDS));
                } catch (Exception e) {
                    acceptThreadFailure.set(e);
                }
            });

            try (VirtualThreadClient client = new VirtualThreadClient(
                    "sync-client",
                    "127.0.0.1",
                    serverSocket.getLocalPort())) {
                IllegalStateException exception = Assertions.assertThrows(
                        IllegalStateException.class,
                        () -> client.sendMessage("hello")
                );
                Assertions.assertTrue(exception.getMessage().contains("Failed to send message"));
            } finally {
                releaseServerSocket.countDown();
            }

            acceptThread.join(Duration.ofSeconds(2));
            Assertions.assertFalse(acceptThread.isAlive());
            Assertions.assertNull(acceptThreadFailure.get());
        }
    }

    @Test
    void testCloseShouldNotMarkClosedByServerWhenClientInitiatesShutdown() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            CountDownLatch releaseServerSocket = new CountDownLatch(1);
            AtomicReference<Throwable> acceptThreadFailure = new AtomicReference<>();

            Thread acceptThread = Thread.ofVirtual().start(() -> {
                try (Socket ignored = serverSocket.accept()) {
                    Assertions.assertTrue(releaseServerSocket.await(2, TimeUnit.SECONDS));
                } catch (Exception e) {
                    acceptThreadFailure.set(e);
                }
            });

            try (VirtualThreadClient client = new VirtualThreadClient(
                    "sync-client",
                    "127.0.0.1",
                    serverSocket.getLocalPort())) {
                Assertions.assertFalse(client.isClosedByServer());
                client.close();
                Assertions.assertFalse(client.isClosedByServer());
            } finally {
                releaseServerSocket.countDown();
            }

            acceptThread.join(Duration.ofSeconds(2));
            Assertions.assertFalse(acceptThread.isAlive());
            Assertions.assertNull(acceptThreadFailure.get());
        }
    }

    @Test
    void testResponseQueueShouldBeBounded() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            CountDownLatch releaseServerSocket = new CountDownLatch(1);
            AtomicReference<Throwable> acceptThreadFailure = new AtomicReference<>();

            Thread acceptThread = Thread.ofVirtual().start(() -> {
                try (Socket accepted = serverSocket.accept();
                     PrintWriter serverWriter = new PrintWriter(accepted.getOutputStream(), true)) {
                    for (int i = 0; i < 5000; i++) {
                        serverWriter.println("response-" + i);
                    }
                    Assertions.assertTrue(releaseServerSocket.await(2, TimeUnit.SECONDS));
                } catch (Exception e) {
                    acceptThreadFailure.set(e);
                }
            });

            try (VirtualThreadClient client = new VirtualThreadClient(
                    "sync-client",
                    "127.0.0.1",
                    serverSocket.getLocalPort())) {
                awaitCondition(Duration.ofSeconds(2), () -> !client.isConnected());
                Assertions.assertFalse(client.isConnected());
                Assertions.assertFalse(client.isClosedByServer());
                Assertions.assertTrue(acceptThread.isAlive());
            } finally {
                releaseServerSocket.countDown();
            }

            acceptThread.join(Duration.ofSeconds(2));
            Assertions.assertFalse(acceptThread.isAlive());
            Assertions.assertNull(acceptThreadFailure.get());
        }
    }

    @Test
    void testResponseQueueOverflowShouldCloseClient() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            CountDownLatch releaseServerSocket = new CountDownLatch(1);
            AtomicReference<Throwable> acceptThreadFailure = new AtomicReference<>();
            Thread acceptThread = Thread.ofVirtual().start(() -> {
                try (Socket accepted = serverSocket.accept();
                     PrintWriter serverWriter = new PrintWriter(accepted.getOutputStream(), true)) {
                    for (int i = 0; i < 5000; i++) {
                        serverWriter.println("response-" + i);
                    }
                    Assertions.assertTrue(releaseServerSocket.await(2, TimeUnit.SECONDS));
                } catch (Exception e) {
                    acceptThreadFailure.set(e);
                }
            });

            try (VirtualThreadClient client = new VirtualThreadClient(
                    "sync-client",
                    "127.0.0.1",
                    serverSocket.getLocalPort())) {
                awaitCondition(Duration.ofSeconds(2), () -> !client.isConnected());
                Assertions.assertFalse(client.isConnected());
                Assertions.assertFalse(client.isClosedByServer());
            } finally {
                releaseServerSocket.countDown();
            }

            acceptThread.join(Duration.ofSeconds(2));
            Assertions.assertFalse(acceptThread.isAlive());
            Assertions.assertNull(acceptThreadFailure.get());
        }
    }

    @Test
    void testCloseShouldBeIdempotent() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            CountDownLatch releaseServerSocket = new CountDownLatch(1);
            AtomicReference<Throwable> acceptThreadFailure = new AtomicReference<>();

            Thread acceptThread = Thread.ofVirtual().start(() -> {
                try (Socket ignored = serverSocket.accept()) {
                    Assertions.assertTrue(releaseServerSocket.await(2, TimeUnit.SECONDS));
                } catch (Exception e) {
                    acceptThreadFailure.set(e);
                }
            });

            try (VirtualThreadClient client = new VirtualThreadClient(
                    "sync-client",
                    "127.0.0.1",
                    serverSocket.getLocalPort())) {
                client.close();
                Assertions.assertDoesNotThrow(client::close);
            } finally {
                releaseServerSocket.countDown();
            }

            acceptThread.join(Duration.ofSeconds(2));
            Assertions.assertFalse(acceptThread.isAlive());
            Assertions.assertNull(acceptThreadFailure.get());
        }
    }

    @Test
    void testCloseShouldHandleWriterAndSocketCloseFailures() throws Exception {
        AtomicReference<Socket> socketUsedByClient = new AtomicReference<>();
        AtomicReference<PrintWriter> writerUsedByClient = new AtomicReference<>();
        try (MockedConstruction<Socket> ignoredSocketConstruction = Mockito.mockConstruction(
                Socket.class,
                (mock, context) -> {
                    socketUsedByClient.set(mock);
                    Mockito.when(mock.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
                    Mockito.when(mock.getOutputStream()).thenReturn(new ByteArrayOutputStream());
                    Mockito.doThrow(new IOException("forced close failure")).when(mock).close();
                });
             MockedConstruction<PrintWriter> ignoredPrintWriterConstruction = Mockito.mockConstruction(
                     PrintWriter.class,
                     (mock, context) -> {
                         writerUsedByClient.set(mock);
                         Mockito.doThrow(new RuntimeException("forced writer close failure")).when(mock).close();
                     })) {
            try (VirtualThreadClient client = new VirtualThreadClient(
                    "sync-client",
                    "127.0.0.1",
                    1)) {
                Assertions.assertDoesNotThrow(client::close);
            }
        }

        Assertions.assertNotNull(socketUsedByClient.get());
        Assertions.assertNotNull(writerUsedByClient.get());
        Mockito.verify(socketUsedByClient.get(), Mockito.times(1)).close();
        Mockito.verify(writerUsedByClient.get(), Mockito.times(1)).close();
    }

    @Test
    void testListenerShouldMarkClosedByServerOnReadIOException() {
        try (MockedConstruction<Socket> ignoredSocketConstruction = Mockito.mockConstruction(
                Socket.class,
                (mock, context) -> {
                    InputStream failingInputStream = Mockito.mock(InputStream.class);
                    Mockito.when(failingInputStream.read()).thenThrow(new IOException("forced read failure"));
                    Mockito.when(failingInputStream.read(Mockito.any(byte[].class), Mockito.anyInt(), Mockito.anyInt()))
                            .thenThrow(new IOException("forced read failure"));
                    Mockito.when(mock.getInputStream()).thenReturn(failingInputStream);
                    Mockito.when(mock.getOutputStream()).thenReturn(new ByteArrayOutputStream());
                })) {
            try (VirtualThreadClient client = new VirtualThreadClient(
                    "sync-clientio-failure-",
                    "127.0.0.1",
                    1)) {
                awaitCondition(Duration.ofSeconds(2), client::isClosedByServer);
                Assertions.assertTrue(client.isClosedByServer());
            }
        }
    }

    @Test
    void testListenerShouldMarkClosedByServerOnReadSocketException() {
        try (MockedConstruction<Socket> ignoredSocketConstruction = Mockito.mockConstruction(
                Socket.class,
                (mock, context) -> {
                    InputStream failingInputStream = Mockito.mock(InputStream.class);
                    Mockito.when(failingInputStream.read()).thenThrow(new SocketException("forced socket failure"));
                    Mockito.when(failingInputStream.read(Mockito.any(byte[].class), Mockito.anyInt(), Mockito.anyInt()))
                            .thenThrow(new SocketException("forced socket failure"));
                    Mockito.when(mock.getInputStream()).thenReturn(failingInputStream);
                    Mockito.when(mock.getOutputStream()).thenReturn(new ByteArrayOutputStream());
                })) {
            try (VirtualThreadClient client = new VirtualThreadClient(
                    "sync-clientsocket-failure-",
                    "127.0.0.1",
                    1)) {
                awaitCondition(Duration.ofSeconds(2), client::isClosedByServer);
                Assertions.assertTrue(client.isClosedByServer());
            }
        }
    }

    // Waits until listener marks remote EOF, so the assertion checks the steady state after server close.
    private static void awaitServerCloseObserved(VirtualThreadClient client) {
        Assertions.assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            while (!client.isClosedByServer()) {
                Thread.onSpinWait();
            }
        });
    }

    // Polls until condition becomes true, so socket-thread transitions can settle without Thread.sleep().
    private static void awaitCondition(Duration timeout, BooleanSupplier condition) {
        Assertions.assertTimeoutPreemptively(timeout, () -> {
            while (!condition.getAsBoolean()) {
                Thread.onSpinWait();
            }
        });
    }

}
