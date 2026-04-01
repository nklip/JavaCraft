package dev.nklip.javacraft.echo.blocking.client.common;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.Getter;
import org.slf4j.Logger;

public abstract class UserClient implements AutoCloseable {

    public static final int CONNECT_TIMEOUT_MILLIS = 1_000;
    public static final int MAX_QUEUED_RESPONSES = 128;

    private final BlockingQueue<String> responseQueue = new LinkedBlockingQueue<>(MAX_QUEUED_RESPONSES);

    private final String host;
    private final int port;
    private Socket socket;
    private PrintWriter clientWritingStreamToServerSocket;
    private Logger connectionLogger;

    // closedByClient tracks whether close() has been called (by us, intentionally).
    // It guards the close logic so it runs exactly once and makes isConnected() return false after close.
    private final AtomicBoolean closedByClient = new AtomicBoolean(false);

    // closedByServer tracks whether the server closed the connection
    // (detected by the listener thread hitting EOF on readLine()).
    // Tests poll this to know the server has finished processing "bye" and decremented its counter
    // before the next step runs.
    // Sequence:
    // Client sends "bye" -> server responds -> server closes its side -> listener detects EOF -> closedByServer = true
    @Getter
    private volatile boolean closedByServer = false;

    public UserClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    protected final void initializeConnection(String threadName, String clientType, Logger log) {
        this.connectionLogger = log;

        try {
            this.socket = new Socket();
            this.socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);

            Writer outputStreamWriter = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
            // Send text commands/messages from client to server.
            this.clientWritingStreamToServerSocket = new PrintWriter(outputStreamWriter, true);

            if (log != null) {
                log.info("{} client '{}' is connected", clientType, socket);
            }

            Reader inputStreamReader = new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8);
            BufferedReader clientReadingStreamFromServerSocket = new BufferedReader(inputStreamReader);
            startResponseListenerThread(threadName + "-" + port, () -> listen(clientReadingStreamFromServerSocket));
        } catch (Exception e) {
            close();
            throw new IllegalStateException("Failed to connect to %s:%d".formatted(host, port), e);
        }
    }

    protected abstract void startResponseListenerThread(String listenerThreadName, Runnable listenerTask);

    private void listen(BufferedReader clientReadingStreamFromServerSocket) {
        boolean serverClosedConnection = false;
        try {
            String line;
            while ((line = clientReadingStreamFromServerSocket.readLine()) != null) {
                if (!enqueueResponse(line)) {
                    break;
                }
            }
            // EOF means server closed its output stream.
            if (!closedByClient.get()) {
                serverClosedConnection = true;
            }
        } catch (SocketException e) {
            // A local close can interrupt readLine(); don't mark it as server-initiated closure.
            if (!closedByClient.get()) {
                serverClosedConnection = true;
                logWarn("Listener socket error: {}", e.getMessage());
            }
        } catch (IOException e) {
            // Read I/O failure while client is still open indicates the remote side is no longer readable.
            if (!closedByClient.get()) {
                serverClosedConnection = true;
                logWarn("Listener error: {}", e.getMessage());
            }
        } finally {
            if (serverClosedConnection) {
                closedByServer = true;
            }
        }
    }

    /**
     * Bounds in-memory buffering of server responses so a noisy peer cannot grow memory unboundedly.
     */
    private boolean enqueueResponse(String line) {
        if (responseQueue.offer(line)) {
            return true;
        }
        logWarn(
                "Response queue overflow (max {}). Closing connection to protect memory.",
                MAX_QUEUED_RESPONSES
        );
        close();
        return false;
    }

    public void sendMessage(String userInput) {
        if (!isConnected()) {
            throw new IllegalStateException("Client is not connected to %s:%d".formatted(host, port));
        }

        clientWritingStreamToServerSocket.println(userInput);

        if (clientWritingStreamToServerSocket.checkError()) {
            close();
            throw new IllegalStateException("Failed to send message to %s:%d".formatted(host, port));
        }
    }

    public String readMessage() {
        try {
            return responseQueue.poll(CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    public boolean isConnected() {
        return !closedByClient.get()
                && !closedByServer
                && socket != null
                && socket.isConnected()
                && !socket.isClosed()
                && clientWritingStreamToServerSocket != null;
    }

    @Override
    public void close() {
        if (!closedByClient.compareAndSet(false, true)) {
            return;
        }

        if (clientWritingStreamToServerSocket != null) {
            try {
                clientWritingStreamToServerSocket.close();
            } catch (Exception e) {
                logError("Couldn't close output stream", e);
            } finally {
                clientWritingStreamToServerSocket = null;
            }
        }
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
                logError("Couldn't close socket", e);
            } finally {
                socket = null;
            }
        }
    }

    private void logWarn(String message, Object... args) {
        if (connectionLogger != null) {
            connectionLogger.warn(message, args);
        }
    }

    private void logError(String message, Throwable error) {
        if (connectionLogger != null) {
            connectionLogger.error(message, error);
        }
    }

    /**
     * Runs the interactive user loop for console clients.
     * <p>
     * It repeatedly prompts for input, sends the message to the server, and prints one response.
     * The loop exits on EOF (for example Ctrl+D/Ctrl+Z) or when the user enters "bye".
     * Client resources are always closed in the {@code finally} block.
     */
    public void readUserMessages(Logger log) {
        log.info("Starting...");

        try (Reader inputStreamReader = new InputStreamReader(System.in, StandardCharsets.UTF_8);
             BufferedReader stdIn = new BufferedReader(inputStreamReader)) {

            while (true) {
                System.out.print("type: ");
                String userInput = stdIn.readLine();
                if (userInput == null) {
                    // readLine() returns null when the input stream reaches end-of-file (EOF)
                    // for example, Ctrl+D (Unix/Mac) or Ctrl+Z (Windows) — the user signals EOF on the terminal
                    log.info("Detected end-of-file (EOF). Thread terminating...");
                    break;
                }
                // Send exactly what the user entered in the console.
                sendMessage(userInput);

                // Print one response line received from the server.
                System.out.println(readMessage());

                // Explicit user command to terminate the session.
                if ("bye".equalsIgnoreCase(userInput)) {
                    break;
                }
            }
        } catch (IllegalStateException e) {
            log.warn(
                    "Client loop stopped because connection to: '{}:{}' is not available: {}",
                    host, port, e.getMessage()
            );
        } catch (IOException e) {
            log.warn(
                    "Couldn't get I/O for the connection to: '{}:{}' because: {}",
                    host, port, e.getMessage()
            );
        } catch (Exception e) {
            log.error(
                    "Exception the connection to: '{}:{}' because: {}",
                    host, port, e.getMessage()
            );
        } finally {
            close();
        }
    }

}
