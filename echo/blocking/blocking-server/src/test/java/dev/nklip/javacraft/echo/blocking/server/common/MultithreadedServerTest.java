package dev.nklip.javacraft.echo.blocking.server.common;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;

class MultithreadedServerTest {

    @Test
    void testRunShouldAcceptClientAndDelegateStartUp() throws Exception {
        AtomicReference<Socket> startedClient = new AtomicReference<>();
        MultithreadedServer server = new MultithreadedServer(8080) {
            @Override
            public void startUpClient(Socket client) {
                startedClient.set(client);
            }
        };
        InetAddress loopback = InetAddress.getLoopbackAddress();
        try (Socket acceptedClient = Mockito.mock(Socket.class);
             MockedConstruction<ServerSocket> construction = Mockito.mockConstruction(
                     ServerSocket.class,
                     (mock, context) -> {
                         Mockito.when(mock.getInetAddress()).thenReturn(loopback);
                         Mockito.when(mock.getLocalPort()).thenReturn(8080);
                         Mockito.when(mock.accept()).thenReturn(acceptedClient).thenThrow(new IOException("stop"));
                     }
             )) {

            Assertions.assertDoesNotThrow(server::run);

            Assertions.assertSame(acceptedClient, startedClient.get(), "Accepted socket should be delegated");
            ServerSocket serverSocket = construction.constructed().getFirst();
            Mockito.verify(serverSocket, Mockito.times(2)).accept();
            Mockito.verify(serverSocket).close();
        }
    }

    @Test
    void testRunShouldCloseClientWhenStartUpClientFails() throws Exception {
        MultithreadedServer server = new MultithreadedServer(9090) {
            @Override
            public void startUpClient(Socket client) {
                throw new IllegalStateException("startup failure");
            }
        };
        InetAddress loopback = InetAddress.getLoopbackAddress();
        Socket acceptedClient = Mockito.mock(Socket.class);
        try (MockedConstruction<ServerSocket> construction = Mockito.mockConstruction(
                ServerSocket.class,
                (mock, context) -> {
                    Mockito.when(mock.getInetAddress()).thenReturn(loopback);
                    Mockito.when(mock.getLocalPort()).thenReturn(9090);
                    Mockito.when(mock.accept()).thenReturn(acceptedClient).thenThrow(new IOException("stop"));
                }
        )) {

            Assertions.assertDoesNotThrow(server::run);
            Mockito.verify(acceptedClient).close();

            ServerSocket serverSocket = construction.constructed().getFirst();
            Mockito.verify(serverSocket, Mockito.times(2)).accept();
            Mockito.verify(serverSocket).close();
        }
    }

    @Test
    void testRunShouldHandleClientCloseFailureWhenStartUpClientFails() throws Exception {
        MultithreadedServer server = new MultithreadedServer(9091) {
            @Override
            public void startUpClient(Socket client) {
                throw new IllegalStateException("startup failure");
            }
        };
        InetAddress loopback = InetAddress.getLoopbackAddress();
        Socket acceptedClient = Mockito.mock(Socket.class);
        try (MockedConstruction<ServerSocket> construction = Mockito.mockConstruction(
                ServerSocket.class,
                (mock, context) -> {
                    Mockito.when(mock.getInetAddress()).thenReturn(loopback);
                    Mockito.when(mock.getLocalPort()).thenReturn(9091);
                    Mockito.when(mock.accept()).thenReturn(acceptedClient).thenThrow(new IOException("stop"));
                }
        )) {
            Mockito.doThrow(new IOException("forced close failure")).when(acceptedClient).close();

            Assertions.assertDoesNotThrow(server::run);
            Mockito.verify(acceptedClient).close();

            ServerSocket serverSocket = construction.constructed().getFirst();
            Mockito.verify(serverSocket, Mockito.times(2)).accept();
            Mockito.verify(serverSocket).close();
        }
    }

    @Test
    void testRunShouldHandleSocketExceptionWhileStillRunning() throws Exception {
        MultithreadedServer server = new MultithreadedServer(9292) {
            @Override
            public void startUpClient(Socket client) {
                // not needed in this test
            }
        };
        InetAddress loopback = InetAddress.getLoopbackAddress();
        try (MockedConstruction<ServerSocket> construction = Mockito.mockConstruction(
                ServerSocket.class,
                (mock, context) -> {
                    Mockito.when(mock.getInetAddress()).thenReturn(loopback);
                    Mockito.when(mock.getLocalPort()).thenReturn(9292);
                    Mockito.when(mock.accept()).thenThrow(new SocketException("accept failed"));
                }
        )) {

            Assertions.assertDoesNotThrow(server::run);
            ServerSocket serverSocket = construction.constructed().getFirst();
            Mockito.verify(serverSocket).accept();
            Mockito.verify(serverSocket).close();
        }
    }

    @Test
    void testRunShouldStopGracefullyWhenShutdownIsCalled() throws Exception {
        MultithreadedServer server = new MultithreadedServer(9191) {
            @Override
            public void startUpClient(Socket client) {
                // not needed in this test
            }
        };
        InetAddress loopback = InetAddress.getLoopbackAddress();
        try (MockedConstruction<ServerSocket> construction = Mockito.mockConstruction(
                ServerSocket.class,
                (mock, context) -> {
                    Mockito.when(mock.getInetAddress()).thenReturn(loopback);
                    Mockito.when(mock.getLocalPort()).thenReturn(9191);
                    Mockito.when(mock.accept()).thenAnswer(invocation -> {
                        server.close();
                        throw new SocketException("socket closed");
                    });
                }
        )) {

            Assertions.assertDoesNotThrow(server::run);
            ServerSocket serverSocket = construction.constructed().getFirst();
            Mockito.verify(serverSocket).accept();
            Mockito.verify(serverSocket, Mockito.atLeastOnce()).close();
        }
    }

    @Test
    void testRunShouldHandleServerSocketCloseFailureDuringShutdown() throws Exception {
        MultithreadedServer server = new MultithreadedServer(9293) {
            @Override
            public void startUpClient(Socket client) {
                // not needed in this test
            }
        };
        InetAddress loopback = InetAddress.getLoopbackAddress();
        try (MockedConstruction<ServerSocket> construction = Mockito.mockConstruction(
                ServerSocket.class,
                (mock, context) -> {
                    Mockito.when(mock.getInetAddress()).thenReturn(loopback);
                    Mockito.when(mock.getLocalPort()).thenReturn(9293);
                    Mockito.doThrow(new IOException("forced close failure")).when(mock).close();
                    Mockito.when(mock.accept()).thenAnswer(invocation -> {
                        server.close();
                        throw new SocketException("socket closed");
                    });
                }
        )) {

            Assertions.assertDoesNotThrow(server::run);
            ServerSocket serverSocket = construction.constructed().getFirst();
            Mockito.verify(serverSocket).accept();
            Mockito.verify(serverSocket, Mockito.atLeastOnce()).close();
        }
    }

    @Test
    void testShutdownShouldBeNoOpWhenServerIsNotRunning() {
        try (MultithreadedServer server = new MultithreadedServer(9494) {
            @Override
            public void startUpClient(Socket client) {
                // not needed
            }
        }) {
            Assertions.assertDoesNotThrow(server::close);
        }
    }

    @Test
    void testRunShouldReturnWhenAlreadyRunning() throws Exception {
        AtomicBoolean nestedRunAttempted = new AtomicBoolean(false);
        AtomicReference<MultithreadedServer> serverRef = new AtomicReference<>();
        MultithreadedServer server = new MultithreadedServer(9393) {
            @Override
            public void startUpClient(Socket client) {
                nestedRunAttempted.set(true);
                serverRef.get().run();
            }
        };
        serverRef.set(server);
        InetAddress loopback = InetAddress.getLoopbackAddress();
        Socket acceptedClient = Mockito.mock(Socket.class);
        try (MockedConstruction<ServerSocket> construction = Mockito.mockConstruction(
                ServerSocket.class,
                (mock, context) -> {
                    Mockito.when(mock.getInetAddress()).thenReturn(loopback);
                    Mockito.when(mock.getLocalPort()).thenReturn(9393);
                    Mockito.when(mock.accept()).thenReturn(acceptedClient).thenThrow(new IOException("stop"));
                }
        )) {
            Assertions.assertDoesNotThrow(server::run);
            Assertions.assertTrue(nestedRunAttempted.get(), "run() should be called recursively by startUpClient");
            Assertions.assertEquals(1, construction.constructed().size(), "Second run() should not open another socket");

            ServerSocket serverSocket = construction.constructed().getFirst();
            Mockito.verify(serverSocket, Mockito.times(2)).accept();
            Mockito.verify(serverSocket).close();
        }
    }
}
