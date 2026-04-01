package dev.nklip.javacraft.echo.selector.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Reads line-delimited server responses from the single client connection.
 * <p>
 * TCP is a byte stream, so responses can arrive fragmented across several reads or combined in a single read.
 * This listener keeps a small raw-byte buffer and only decodes complete framed messages for the client queue.
 * <p>
 * @author Lipatov Nikita
 */
@Slf4j
@RequiredArgsConstructor
public class SelectorMessageListener implements Runnable {

    static final int BUFFER_SIZE = 2 * 1024;
    static final int MAX_FRAME_BYTES = 8 * BUFFER_SIZE;
    static final long SELECTOR_TIMEOUT = 1_000L;
    private static final String MESSAGE_DELIMITER = "\r\n";
    private static final byte[] MESSAGE_DELIMITER_BYTES = MESSAGE_DELIMITER.getBytes(StandardCharsets.US_ASCII);

    private final SelectorNetworkManager selectorNetworkManager;
    private final ByteArrayOutputStream responseBuffer = new ByteArrayOutputStream();
    private final Deque<String> pendingMessages = new ArrayDeque<>();

    @Override
    public void run() {
        while (isNotInterrupted()) {
            Selector selector = selectorNetworkManager.getSelector();
            if (selector == null) {
                log.info("Listener interrupted while waiting for selector.");
                break;
            }

            SelectorMessageSender selectorMessageSender = selectorNetworkManager.getSelectorMessageSender();

            try {
                while (isNotInterrupted()) {
                    selector.select(SELECTOR_TIMEOUT);
                    Iterator<SelectionKey> keys = selector.selectedKeys().iterator();

                    while(keys.hasNext()) {
                        SelectionKey key = keys.next();
                        keys.remove();

                        processReadyKey(key, selectorMessageSender);
                    }
                }
            } catch (IOException err) {
                log.error("IO error in listener, resetting connection", err);
                selectorNetworkManager.closeSocket();
                selectorMessageSender.setKey(null, null);
                break;
            } catch (Exception err) {
                // Handles ClosedSelectorException, CancelledKeyException, etc.
                // it's a normal BAU closing process, so the log level should be  'debug'
                log.debug("Listener loop terminated", err);
                selectorNetworkManager.closeSocket();
                selectorMessageSender.setKey(null, null);
                break;
            }
        }
        log.info("Listener thread terminated.");
    }

    private boolean isNotInterrupted() {
        return !Thread.currentThread().isInterrupted();
    }

    /**
     * Handles both writable and readable readiness for the same key because a
     * non-blocking socket can report both states in one selector cycle.
     */
    void processReadyKey(SelectionKey key, SelectorMessageSender selectorMessageSender) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();

        if (key.isWritable()) {
            selectorMessageSender.flushPendingWrites();
        }
        if (key.isReadable()) {
            queueAvailableResponses(channel);
        }
    }

    /**
     * Drains every complete response that is already buffered for the socket.
     * This avoids losing coalesced frames when the server sends multiple
     * responses in a single TCP packet.
     */
    private void queueAvailableResponses(SocketChannel channel) {
        while (true) {
            String message = newResponse(channel);
            if (message == null) {
                return;
            }
            selectorNetworkManager.addMessage(message);
            log.info(message);
        }
    }

    /**
     * Returns the next complete response without stripping meaningful
     * whitespace from the payload. Only the framing delimiter is removed.
     */
    public String newResponse(SocketChannel channel) {
        String bufferedMessage = pollPendingMessage();
        if (bufferedMessage != null) {
            return bufferedMessage;
        }

        ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);

        try {
            while (true) {
                int numRead = channel.read(buffer); // get message from client

                if (numRead == -1) {
                    log.debug("Connection closed by: {}", channel.getRemoteAddress());
                    channel.close();
                    return null;
                }

                if (numRead == 0) {
                    return pollPendingMessage();
                }

                buffer.flip();
                byte[] data = new byte[numRead];
                buffer.get(data);
                buffer.clear();

                responseBuffer.write(data, 0, numRead);
                if (!extractCompleteFrames(channel)) {
                    return null;
                }

                String nextMessage = pollPendingMessage();
                if (nextMessage != null) {
                    return nextMessage;
                }
            }
        } catch (ClosedChannelException e) {
            return null;
        } catch (IOException e) {
            log.error("Unable to read from channel", e);
            try {
                channel.close();
            } catch (IOException e1) {
                //nothing to do, channel dead
            }
        }
        return null;
    }

    /**
     * Splits the accumulated raw bytes into complete frames before decoding.
     * This keeps UTF-8 characters intact even when one code point is spread
     * across multiple socket reads.
     */
    private boolean extractCompleteFrames(SocketChannel channel) {
        byte[] bufferedBytes = responseBuffer.toByteArray();
        int frameStart = 0;

        for (int index = 0; index <= bufferedBytes.length - MESSAGE_DELIMITER_BYTES.length; index++) {
            if (!matchesDelimiter(bufferedBytes, index)) {
                continue;
            }

            int frameLength = index - frameStart;
            if (frameLength > MAX_FRAME_BYTES) {
                closeOversizedFrame(channel, frameLength);
                return false;
            }

            pendingMessages.addLast(new String(
                    bufferedBytes,
                    frameStart,
                    frameLength,
                    StandardCharsets.UTF_8));
            frameStart = index + MESSAGE_DELIMITER_BYTES.length;
            index = frameStart - 1;
        }

        int unreadBytes = bufferedBytes.length - frameStart;
        if (unreadBytes > MAX_FRAME_BYTES) {
            closeOversizedFrame(channel, unreadBytes);
            return false;
        }

        retainUnreadBytes(bufferedBytes, frameStart);
        return true;
    }

    /**
     * Compares raw bytes against the delimiter so framing stays correct before
     * any UTF-8 decoding happens.
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
     * Drops already-consumed bytes while keeping any unfinished UTF-8 suffix
     * for the next read call.
     */
    private void retainUnreadBytes(byte[] bufferedBytes, int frameStart) {
        if (frameStart == 0) {
            return;
        }

        responseBuffer.reset();
        responseBuffer.write(bufferedBytes, frameStart, bufferedBytes.length - frameStart);
    }

    /**
     * Closes the connection as soon as one frame grows beyond the protocol
     * limit so a missing delimiter cannot keep expanding the in-memory buffer.
     */
    private void closeOversizedFrame(SocketChannel channel, int frameBytes) {
        log.warn("Closing channel because frame reached {} bytes (max {})", frameBytes, MAX_FRAME_BYTES);
        pendingMessages.clear();
        responseBuffer.reset();
        try {
            channel.close();
        } catch (IOException closeError) {
            log.debug("Unable to close oversized frame channel", closeError);
        }
    }

    private String pollPendingMessage() {
        return pendingMessages.pollFirst();
    }
}
