package dev.nklip.javacraft.echo.blocking.server.common;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;

/**
 * @author Lipatov Nikita
 */
@Slf4j
public abstract class MultithreadedServer implements Runnable, AutoCloseable {

    protected final AtomicInteger connectedClients = new AtomicInteger(0);
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final int port;
    private volatile ServerSocket serverSocket;

    public MultithreadedServer(int port) {
        this.port = port;

        log.info("Use next command: telnet localhost {}", port);
    }

    @Override
    public void run() {
        if (!running.compareAndSet(false, true)) {
            log.warn("Server on port {} is already running", port);
            return;
        }

        try (ServerSocket server = new ServerSocket(port)) {
            this.serverSocket = server;

            String serverHello = """ 
                    \\{^_^}/ Hi!
                    *********************************************
                    Server stats:
                    Server canonical host name - %s
                    Server host address - %s
                    Server host name - %s
                    Server port - %s
                    *********************************************
                    """.formatted(
                    server.getInetAddress().getCanonicalHostName(),
                    server.getInetAddress().getHostAddress(),
                    server.getInetAddress().getHostName(),
                    server.getLocalPort()
            );

            log.info(serverHello);

            while (running.get()) {
                Socket client = server.accept();

                String info = String.format("New client from '%s' is connected", client);
                log.info(info);

                processClientSocket(client);
            }
        } catch (SocketException se) {
            if (running.get()) {
                log.error("Server socket failure on port {}", port, se);
            }
        } catch (IOException ioe) {
            if (running.get()) {
                log.error(ioe.getLocalizedMessage(), ioe);
            }
        } finally {
            running.set(false);
            serverSocket = null;
        }
    }

    // supports graceful shutdown
    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        closeServerSocket();
    }

    private void closeServerSocket() {
        ServerSocket socket = serverSocket;
        if (socket == null || socket.isClosed()) {
            return;
        }

        try {
            socket.close();
        } catch (IOException closeError) {
            log.error("Failed to close server socket on port {}", port, closeError);
        }
    }

    private void closeClientQuietly(Socket client) {
        try {
            client.close();
        } catch (IOException closeError) {
            log.error("Failed to close client socket after startup failure", closeError);
        }
    }

    public void processClientSocket(Socket client) {
        try {
            startUpClient(client);
        } catch (RuntimeException startupError) {
            log.error("Failed to start client {}", client, startupError);
            closeClientQuietly(client);
        }
    }

    public abstract void startUpClient(Socket client);
}
