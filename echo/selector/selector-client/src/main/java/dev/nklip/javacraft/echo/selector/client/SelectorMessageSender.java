package dev.nklip.javacraft.echo.selector.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.Selector;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import lombok.extern.slf4j.Slf4j;

/**
 * Sends one line-delimited command at a time.
 * <p>
 * The transport is stream-based, so each payload must be framed explicitly
 * before it is written to the socket.
 * <p>
 * @author Lipatov Nikita
 */
@Slf4j
public class SelectorMessageSender {

    private static final String MESSAGE_DELIMITER = "\r\n";
    private static final long SEND_WAIT_TIMEOUT_MS = 5_000;

    private final Deque<ByteBuffer> pendingWrites = new ArrayDeque<>();
    private volatile SelectionKey key;
    private volatile Selector selector;

    /**
     * Unblocks pending senders once the selector thread has finished the
     * connection handshake and published the key for the socket channel.
     */
    public void setKey(SelectionKey key, Selector selector) {
        synchronized (this) {
            this.key = key;
            this.selector = selector;
            if (key == null) {
                pendingWrites.clear();
            }
            notifyAll();
        }
    }

    /**
     * Queues the framed command and lets the selector thread flush it later.
     * This keeps send() non-blocking even when the socket cannot accept bytes
     * immediately and write() would otherwise return zero.
     */
    public void send(String command) {
        try {
            SelectionKey currentKey = awaitSelectionKey();
            if (currentKey == null) {
                log.warn("Selection key became null before send");
                return;
            }

            Selector currentSelector;
            synchronized (this) {
                currentKey = key;
                if (currentKey == null) {
                    log.warn("Selection key became null before send");
                    return;
                }
                pendingWrites.addLast(ByteBuffer.wrap(frameCommand(command).getBytes(StandardCharsets.UTF_8)));
                updateInterestOps(currentKey, true);
                currentSelector = selector;
            }
            if (currentSelector != null) {
                currentSelector.wakeup();
            }
        } catch (CancelledKeyException e) {
            log.error(e.getMessage(), e);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.error(ie.getMessage(), ie);
        }
    }

    /**
     * Flushes queued bytes only when the selector says the socket is writable.
     * This avoids busy-spinning on non-blocking channels that report zero bytes
     * written while backpressure is present.
     */
    @SuppressWarnings("resource")
    void flushPendingWrites() throws IOException {
        synchronized (this) {
            SelectionKey currentKey = key;
            if (currentKey == null) {
                return;
            }

            // The network manager owns the channel lifecycle, so the sender only
            // borrows the already-open channel here and must not close it.
            SocketChannel channel = (SocketChannel) currentKey.channel();
            ByteBuffer currentBuffer = pendingWrites.peekFirst();
            while (currentBuffer != null) {
                int written = channel.write(currentBuffer);
                if (written == 0) {
                    updateInterestOps(currentKey, true);
                    return;
                }
                if (!currentBuffer.hasRemaining()) {
                    pendingWrites.removeFirst();
                }
                currentBuffer = pendingWrites.peekFirst();
            }

            updateInterestOps(currentKey, false);
        }
    }

    /**
     * Keeps all send wait logic in one place so queueing and selector updates
     * do not duplicate the timeout and interrupt handling rules.
     */
    private SelectionKey awaitSelectionKey() throws InterruptedException {
        if (key != null) {
            return key;
        }

        synchronized (this) {
            long deadline = System.currentTimeMillis() + SEND_WAIT_TIMEOUT_MS;
            while (key == null) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    log.warn("Timed out waiting for selection key");
                    return null;
                }
                wait(remaining);
            }
            return key;
        }
    }

    /**
     * Leaves OP_READ enabled even when OP_WRITE is also needed so one selector
     * cycle can process incoming data and pending outgoing data together.
     */
    private void updateInterestOps(SelectionKey key, boolean hasPendingWrites) {
        int interestOps = SelectionKey.OP_READ;
        if (hasPendingWrites) {
            interestOps |= SelectionKey.OP_WRITE;
        }
        key.interestOps(interestOps);
    }

    /**
     * Normalizes caller input to the single wire format used by the project.
     * Existing trailing line endings are preserved only when they already match
     * the protocol delimiter.
     */
    private String frameCommand(String command) {
        if (command.endsWith(MESSAGE_DELIMITER)) {
            return command;
        }
        if (command.endsWith("\n")) {
            return command.substring(0, command.length() - 1) + MESSAGE_DELIMITER;
        }
        if (command.endsWith("\r")) {
            return command + "\n";
        }
        return command + MESSAGE_DELIMITER;
    }
}
