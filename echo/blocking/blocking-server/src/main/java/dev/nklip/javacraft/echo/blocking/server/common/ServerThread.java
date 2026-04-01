package dev.nklip.javacraft.echo.blocking.server.common;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;

/**
 * @author Lipatov Nikita
 */
@Slf4j
public class ServerThread implements Runnable {

    private static final int DEFAULT_READ_TIMEOUT_MILLIS = 2_000;

    private final Socket socket;
    private final AtomicInteger connectedClients;
    private final BufferedReader inStream;
    private final BufferedWriter outStream;
    private final AtomicBoolean counted = new AtomicBoolean(false);

    public ServerThread(Socket socket, AtomicInteger connectedClients) {
        this.socket = Objects.requireNonNull(socket, "Socket must not be null");
        this.connectedClients = Objects.requireNonNull(connectedClients, "Connected clients counter must not be null");

        try {
            socket.setSoTimeout(DEFAULT_READ_TIMEOUT_MILLIS);
            this.inStream = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8));
            this.outStream = new BufferedWriter(new OutputStreamWriter(
                    socket.getOutputStream(), StandardCharsets.UTF_8));
        } catch (IOException ioe) {
            throw new IllegalStateException("Could not initialize server thread streams", ioe);
        }
    }

    @Override
    public void run() {
        markConnected();
        try {
            while (true) {
                String request = inStream.readLine();
                if (request == null) {
                    break;
                }

                String response = findServerResponse(request);
                writeResponse(response);

                if ("bye".equalsIgnoreCase(request)) {
                    break;
                }
            }
        } catch (IOException ioe) {
            log.error("I/O error for client {}: {}", socket.getPort(), ioe.getLocalizedMessage(), ioe);
        } catch (RuntimeException rte) {
            log.error("Unexpected server thread error for client {}", socket.getPort(), rte);
            throw rte;
        } finally {
            closeConnection();
        }
    }

    private String findServerResponse(String request) {
        if (request.isEmpty()) {
            return "Please type something.\r\n";
        }
        if ("stats".equalsIgnoreCase(request)) {
            return "Simultaneously connected clients: %s\r\n".formatted(connectedClients.get());
        }
        if ("bye".equalsIgnoreCase(request)) {
            return "Have a good day!\r\n";
        }
        return "Did you say '" + request + "'?\r\n";
    }

    private void closeConnection() {
        closeQuietly(outStream, "output stream");
        closeQuietly(inStream, "input stream");
        closeQuietly(socket, "socket");
        if (counted.compareAndSet(true, false)) {
            log.info("Simultaneously connected clients : {}", connectedClients.decrementAndGet());
        }
        log.info("Client {} left", socket.getPort());
    }

    private void markConnected() {
        if (counted.compareAndSet(false, true)) {
            log.info("Simultaneously connected clients : {}", connectedClients.incrementAndGet());
        }
    }

    private void writeResponse(String response) throws IOException {
        log.debug("resp {} = {}", socket.getPort(), response.stripTrailing());
        outStream.write(response);
        outStream.flush();
    }

    private void closeQuietly(AutoCloseable closeable, String target) {
        try {
            closeable.close();
        } catch (Exception ex) {
            log.error("Couldn't close {} for client {}", target, socket.getPort(), ex);
        }
    }

}
