package dev.nklip.javacraft.echo.selector.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SelectableChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;

/**
 * Single-threaded echo server built on top of a selector.
 * <p>
 * The server speaks a simple line-delimited protocol.
 * Each connection keeps its own decode buffer so fragmented TCP reads
 * and coalesced TCP reads are handled deterministically.
 * <p>
 * @author Lipatov Nikita
 * <p>
 * Framing Strategy: Line-delimited framing with - \r\n.
 * <p>
 * Strategy on both sides:
 * <p>
 * 1) sender always appends - \r\n
 * 2) receiver keeps a per-connection raw byte buffer
 * 3) each read appends raw bytes to that buffer
 * 4) receiver extracts only complete frames ending with \r\n and then decodes them as UTF-8
 * 5) any incomplete suffix stays buffered for the next read
 * 6) if multiple frames arrive in one read, they are queued and processed in order
 */
@Slf4j
public class SelectorServer implements Runnable {

    static final int BUFFER_SIZE = 2 * 1024;
    static final int MAX_FRAME_BYTES = 8 * BUFFER_SIZE;
    static final int MAX_PENDING_WRITE_BYTES = MAX_FRAME_BYTES;
    // A CRLF-terminated text frame keeps the protocol simple and readable while
    // still handling empty messages and fragmented/coalesced TCP packets.
    private static final String MESSAGE_DELIMITER = "\r\n";
    private static final byte[] MESSAGE_DELIMITER_BYTES = MESSAGE_DELIMITER.getBytes(StandardCharsets.US_ASCII);

    private final AtomicInteger connections = new AtomicInteger(0);
    private final AtomicBoolean running = new AtomicBoolean(true);

    private final int port;
    private final ByteBuffer buffer;
    private final ServerSocketChannel server;
    private final Map<SocketChannel, ByteArrayOutputStream> requestBuffers = new HashMap<>();
    private final Map<SocketChannel, Deque<String>> pendingRequests = new HashMap<>();
    private final Map<SocketChannel, Deque<ByteBuffer>> pendingWrites = new HashMap<>();
    private final Set<SocketChannel> closeAfterWrite = new HashSet<>();
    private volatile Selector selectorRef;

    /**
     * Tells writeOp() whether a channel still has queued bytes, finished cleanly,
     * or failed and must be closed.
     */
    private enum WriteDrainResult {
        COMPLETE,
        PENDING,
        FAILED
    }

    /**
     * Allocates the shared read buffer once and keeps the rest of the per-socket
     * state in lightweight maps keyed by the channel itself.
     */
    public SelectorServer(int port) {
        this.port = port;
        this.buffer = ByteBuffer.allocate(BUFFER_SIZE);
        try {
            this.server = port > 0 ? openServerChannel() : null;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to bind server socket on port " + port, e);
        }

        log.info("Use next command: telnet localhost {}", port);
    }

    /**
     * Owns the full server lifecycle so callers only need to construct the
     * instance and run it on a thread.
     */
    public void run() {
        Selector selector = null;
        ServerSocketChannel server = null;

        try {
            log.info("Starting server...");

            selector = Selector.open();
            selectorRef = selector;
            server = this.server != null ? this.server : openServerChannel();
            server.configureBlocking(false);
            server.register(selector, SelectionKey.OP_ACCEPT);

            log.info("Server ready, now ready to accept connections...");
            loop(selector, server);

        } catch (Exception e) {
            log.error("Server failure", e);
        } finally {
            selectorRef = null;
            closeTrackedClients();
            try {
                if (selector != null) {
                    selector.close();
                }
                if (server != null) {
                    server.close();
                }
            } catch (Exception e) {
                // server failed
                log.error(e.getMessage(), e);
            }
        }
    }

    /**
     * Closes every client channel still referenced by the server state so
     * shutdown does not leak accepted sockets after the selector stops.
     */
    private void closeTrackedClients() {
        Set<SocketChannel> trackedClients = new HashSet<>();
        trackedClients.addAll(requestBuffers.keySet());
        trackedClients.addAll(pendingRequests.keySet());
        trackedClients.addAll(pendingWrites.keySet());
        trackedClients.addAll(closeAfterWrite);

        for (SocketChannel client : trackedClients) {
            clearChannelState(client);
            try {
                client.close();
            } catch (IOException closeError) {
                log.debug("Error closing tracked client", closeError);
            }
        }
        connections.set(0);
    }

    /**
     * Wakes the selector because a plain flag change would not unblock
     * selector.select() promptly during shutdown.
     */
    public void stop() {
        running.set(false);
        Selector selector = selectorRef;
        if (selector != null) {
            selector.wakeup();
        }
    }

    /**
     * Centralizes server-socket creation so constructor reservation and the
     * port-zero startup path cannot drift apart.
     */
    private ServerSocketChannel openServerChannel() throws IOException {
        ServerSocketChannel server = ServerSocketChannel.open();
        server.socket().bind(new InetSocketAddress(port));
        return server;
    }

    private void loop(Selector selector, ServerSocketChannel server) throws IOException {
        while (running.get() && isNotInterrupted()) {
            int num = selector.select();
            if (num == 0) {
                continue;
            }
            Iterator<SelectionKey> keys = selector.selectedKeys().iterator();

            while (keys.hasNext()) {
                SelectionKey key = keys.next();
                keys.remove();

                try {
                    if (key.isAcceptable()) {
                        acceptOp(selector, server);
                    }
                    if (key.isValid() && key.isReadable()) {
                        readOp(key);
                    }
                    if (key.isValid() && key.isWritable()) {
                        writeOp(key);
                    }
                } catch (Exception e) {
                    log.error("Failed to process selected key", e);
                    closeKey(key);
                }
            }
        }
    }

    private boolean isNotInterrupted() {
        return !Thread.currentThread().isInterrupted();
    }

    private void acceptOp(Selector selector, ServerSocketChannel server) throws IOException {
        SocketChannel client = server.accept();
        if (client == null) {
            return;
        }

        log.info("New socket has been accepted!");
        try {
            client.configureBlocking(false);
            client.register(selector, SelectionKey.OP_READ);
            requestBuffers.put(client, new ByteArrayOutputStream());
            pendingRequests.put(client, new ArrayDeque<>());
            connections.incrementAndGet();
        } catch (Exception setupError) {
            closeAcceptedClient(client, setupError);
        }
    }

    /**
     * Cleans up a half-initialized accepted client so setup failures do not
     * bubble up to the server accept key and stop new connections.
     */
    private void closeAcceptedClient(SocketChannel client, Exception setupError) {
        log.error("Unable to initialize accepted client", setupError);
        try {
            client.close();
        } catch (IOException closeError) {
            log.debug("Unable to close accepted client after setup failure", closeError);
        }
    }

    /**
     * Queues responses once a complete framed request is available and keeps
     * read interest enabled so the selector can handle both directions.
     */
    private void readOp(SelectionKey key) {
        log.debug("Data received, going to read them");
        SocketChannel channel = (SocketChannel) key.channel();

        String result = read(channel);
        if (result == null) {
            return;
        }

        if (!result.isEmpty()) {
            if (!queueReadyResponses(channel, result)) {
                key.cancel();
                return;
            }
            key.attach(null);
            key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
        } else {
            clearChannelState(channel);
            key.cancel();
        }
    }

    /**
     * Returns the next complete request frame for the channel.
     * <p>
     * The returned value still includes the line delimiter so an empty client message remains distinguishable from EOF.
     */
    String read(SocketChannel channel) {
        String pendingRequest = pollPendingRequest(channel);
        if (pendingRequest != null) {
            return pendingRequest;
        }

        ByteArrayOutputStream requestBuffer = requestBuffers.computeIfAbsent(channel, ignored -> new ByteArrayOutputStream());
        Deque<String> readyRequests = pendingRequests.computeIfAbsent(channel, ignored -> new ArrayDeque<>());

        try {
            while (true) {
                buffer.clear();
                int numRead = channel.read(buffer);
                if (numRead == 0) {
                    return pollPendingRequest(channel);
                }
                if (numRead == -1) {
                    log.debug("Connection closed by: {}", channel.getRemoteAddress());
                    clearChannelState(channel);
                    decrementConnections();
                    channel.close();
                    return "";
                }

                buffer.flip();
                byte[] data = new byte[numRead];
                buffer.get(data);

                requestBuffer.write(data, 0, numRead);
                if (!bufferCompleteRequests(requestBuffer, readyRequests)) {
                    closeOversizedRequest(channel);
                    return "";
                }

                String nextRequest = readyRequests.pollFirst();
                if (nextRequest != null) {
                    return nextRequest;
                }
            }
        } catch (IOException e) {
            log.error("Unable to read from channel", e);
            clearChannelState(channel);
            decrementConnections();
            try {
                channel.close();
            } catch (IOException e1) {
                // nothing to do, channel dead
                log.error(e1.getMessage(), e1);
            }
        }

        return "";
    }

    /**
     * Flushes queued response bytes only when the selector reports the channel
     * writable, which avoids spinning on zero-byte non-blocking writes.
     */
    private void writeOp(SelectionKey key) {
        SocketChannel channel = (SocketChannel) key.channel();
        WriteDrainResult drainResult = drainPendingWrites(channel);
        if (drainResult == WriteDrainResult.FAILED) {
            clearChannelState(channel);
            closeKey(key);
            return;
        }
        if (drainResult == WriteDrainResult.PENDING) {
            key.attach(null);
            key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
            return;
        }
        if (closeAfterWrite.remove(channel)) {
            decrementConnections();
            clearChannelState(channel);
            closeKey(key);
            return;
        }

        key.attach(null);
        key.interestOps(SelectionKey.OP_READ);
    }

    /**
     * Removes only the protocol delimiter so embedded line breaks remain part of
     * the echoed payload.
     */
    private String trimTrailingLineDelimiters(String request) {
        int end = request.length();
        while (end > 0) {
            char current = request.charAt(end - 1);
            if (current != '\r' && current != '\n') {
                break;
            }
            end--;
        }
        return request.substring(0, end);
    }

    /**
     * Converts every ready request into queued UTF-8 response bytes so the
     * selector thread can flush them incrementally across writable events.
     */
    private boolean queueReadyResponses(SocketChannel channel, String firstRequest) {
        String request = firstRequest;
        while (request != null) {
            String normalizedRequest = trimTrailingLineDelimiters(request);
            if (normalizedRequest.isEmpty()) {
                if (tryQueueResponse(channel, "Please type something.\r\n")) {
                    request = pollPendingRequest(channel);
                    continue;
                }
                return false;
            } else if ("bye".equalsIgnoreCase(normalizedRequest)) {
                if (tryQueueResponse(channel, "Have a good day!\r\n")) {
                    closeAfterWrite.add(channel);
                    clearReadState(channel);
                    return true;
                }
                return false;
            } else if ("stats".equalsIgnoreCase(normalizedRequest)) {
                if (tryQueueResponse(channel, "Simultaneously connected clients: %s\r\n".formatted(connections.get()))) {
                    request = pollPendingRequest(channel);
                    continue;
                }
                return false;
            } else {
                if (tryQueueResponse(channel, "Did you say '" + normalizedRequest + "'?\r\n")) {
                    request = pollPendingRequest(channel);
                    continue;
                }
                return false;
            }
        }
        return true;
    }

    /**
     * Stores encoded response buffers per channel so partial writes can resume
     * from the exact remaining byte position on the next writable callback.
     */
    private boolean queueResponse(SocketChannel channel, String response) {
        byte[] encodedResponse = response.getBytes(StandardCharsets.UTF_8);
        Deque<ByteBuffer> queuedWrites = pendingWrites.computeIfAbsent(channel, ignored -> new ArrayDeque<>());
        if (queuedResponseBytes(queuedWrites) + encodedResponse.length > MAX_PENDING_WRITE_BYTES) {
            return false;
        }
        queuedWrites.addLast(ByteBuffer.wrap(encodedResponse));
        return true;
    }

    /**
     * Closes a slow client as soon as its queued response backlog exceeds the
     * configured cap so reads cannot grow server memory without bound.
     */
    private boolean tryQueueResponse(SocketChannel channel, String response) {
        if (queueResponse(channel, response)) {
            return true;
        }
        closeOverloadedClient(channel);
        return false;
    }

    /**
     * Counts only the remaining bytes because partially flushed buffers should
     * contribute just their unfinished portion to the backlog cap.
     */
    private int queuedResponseBytes(Deque<ByteBuffer> queuedWrites) {
        int queuedBytes = 0;
        for (ByteBuffer queuedWrite : queuedWrites) {
            queuedBytes += queuedWrite.remaining();
        }
        return queuedBytes;
    }

    /**
     * Treats an oversized outbound backlog as a slow-client failure so one
     * connection cannot keep accumulating unsent server responses forever.
     */
    private void closeOverloadedClient(SocketChannel channel) {
        log.warn("Closing channel because queued responses exceeded {} bytes", MAX_PENDING_WRITE_BYTES);
        clearChannelState(channel);
        decrementConnections();
        try {
            channel.close();
        } catch (IOException closeError) {
            log.debug("Unable to close overloaded client channel", closeError);
        }
    }

    /**
     * Drains queued response buffers until the socket stops making progress,
     * all bytes are sent, or one write fails.
     */
    private WriteDrainResult drainPendingWrites(SocketChannel channel) {
        Deque<ByteBuffer> queuedWrites = pendingWrites.get(channel);
        if (queuedWrites == null || queuedWrites.isEmpty()) {
            pendingWrites.remove(channel);
            return WriteDrainResult.COMPLETE;
        }

        try {
            ByteBuffer currentWrite = queuedWrites.peekFirst();
            while (currentWrite != null) {
                int written = channel.write(currentWrite);
                if (written == 0) {
                    return WriteDrainResult.PENDING;
                }
                if (!currentWrite.hasRemaining()) {
                    queuedWrites.removeFirst();
                }
                currentWrite = queuedWrites.peekFirst();
            }

            pendingWrites.remove(channel);
            return WriteDrainResult.COMPLETE;
        } catch (ClosedChannelException cce) {
            decrementConnections();
            log.info("Client terminated connection.");
            return WriteDrainResult.FAILED;
        } catch (IOException e) {
            decrementConnections();
            log.error("Unable to write content", e);
            try {
                channel.close();
            } catch (IOException e1) {
                // dead channel, nothing to do
            }
            return WriteDrainResult.FAILED;
        }
    }

    /**
     * Extracts complete frames from raw bytes before decoding them. This avoids
     * corrupting UTF-8 characters that arrive split across socket reads.
     */
    private boolean bufferCompleteRequests(ByteArrayOutputStream requestBuffer, Deque<String> readyRequests) {
        byte[] bufferedBytes = requestBuffer.toByteArray();
        int frameStart = 0;

        for (int index = 0; index <= bufferedBytes.length - MESSAGE_DELIMITER_BYTES.length; index++) {
            if (!matchesDelimiter(bufferedBytes, index)) {
                continue;
            }

            int frameLength = index - frameStart;
            if (frameLength > MAX_FRAME_BYTES) {
                return false;
            }

            readyRequests.addLast(new String(
                    bufferedBytes,
                    frameStart,
                    frameLength,
                    StandardCharsets.UTF_8) + MESSAGE_DELIMITER);
            frameStart = index + MESSAGE_DELIMITER_BYTES.length;
            index = frameStart - 1;
        }

        if (bufferedBytes.length - frameStart > MAX_FRAME_BYTES) {
            return false;
        }

        retainUnreadBytes(requestBuffer, bufferedBytes, frameStart);
        return true;
    }

    /**
     * Compares raw bytes with the CRLF delimiter so frame detection stays
     * independent from UTF-8 character boundaries.
     */
    private boolean matchesDelimiter(byte[] bufferedBytes, int index) {
        for (int delimiterOffset = 0; delimiterOffset < MESSAGE_DELIMITER_BYTES.length; delimiterOffset++) {
            if (bufferedBytes[index + delimiterOffset] != MESSAGE_DELIMITER_BYTES[delimiterOffset]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Removes already-decoded frames and keeps only the unfinished byte suffix
     * for the next non-blocking read event.
     */
    private void retainUnreadBytes(ByteArrayOutputStream requestBuffer, byte[] bufferedBytes, int frameStart) {
        if (frameStart == 0) {
            return;
        }

        requestBuffer.reset();
        requestBuffer.write(bufferedBytes, frameStart, bufferedBytes.length - frameStart);
    }

    private String pollPendingRequest(SocketChannel channel) {
        Deque<String> readyRequests = pendingRequests.get(channel);
        if (readyRequests == null) {
            return null;
        }
        return readyRequests.pollFirst();
    }

    /**
     * Drops only inbound framing state so a close-after-write request can still
     * finish sending its queued response bytes.
     */
    private void clearReadState(SocketChannel channel) {
        requestBuffers.remove(channel);
        pendingRequests.remove(channel);
    }

    private void clearChannelState(SocketChannel channel) {
        clearReadState(channel);
        pendingWrites.remove(channel);
        closeAfterWrite.remove(channel);
    }

    /**
     * Treats an oversized request as a protocol violation and closes the
     * client so one unterminated frame cannot consume memory indefinitely.
     */
    private void closeOversizedRequest(SocketChannel channel) {
        log.warn("Closing channel because request exceeded {} bytes", MAX_FRAME_BYTES);
        clearChannelState(channel);
        decrementConnections();
        try {
            channel.close();
        } catch (IOException closeError) {
            log.debug("Unable to close oversized request channel", closeError);
        }
    }

    private void decrementConnections() {
        connections.updateAndGet(value -> value > 0 ? value - 1 : 0);
    }

    private void closeKey(SelectionKey key) {
        SelectableChannel channel = key.channel();
        try {
            if (channel instanceof SocketChannel socketChannel) {
                clearChannelState(socketChannel);
            }
            channel.close();
        } catch (IOException closeError) {
            log.debug("Error closing channel", closeError);
        } finally {
            key.cancel();
        }
    }

}
