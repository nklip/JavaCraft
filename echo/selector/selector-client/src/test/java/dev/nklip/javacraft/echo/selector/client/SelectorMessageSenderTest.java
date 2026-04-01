package dev.nklip.javacraft.echo.selector.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.nio.channels.spi.AbstractSelectableChannel;
import java.nio.channels.spi.AbstractSelector;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SelectorMessageSenderTest {

    private SelectorMessageSender sender;
    private ServerSocket serverSocket;
    private Socket acceptedSocket;
    private SocketChannel clientChannel;
    private Selector selector;

    @BeforeEach
    void setUp() {
        sender = new SelectorMessageSender();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (acceptedSocket != null && !acceptedSocket.isClosed()) {
            acceptedSocket.close();
        }
        if (selector != null && selector.isOpen()) {
            selector.close();
        }
        if (clientChannel != null && clientChannel.isOpen()) {
            clientChannel.close();
        }
        if (serverSocket != null && !serverSocket.isClosed()) {
            serverSocket.close();
        }
    }

    /**
     * Sets up an NIO client channel connected to a local server.
     * Returns the SelectionKey registered with OP_READ.
     * The accepted server-side socket is stored in {@link #acceptedSocket}.
     */
    private SelectionKey createConnection() throws IOException {
        serverSocket = new ServerSocket(0);

        clientChannel = SocketChannel.open();
        clientChannel.configureBlocking(false);
        clientChannel.connect(new InetSocketAddress("localhost", serverSocket.getLocalPort()));

        acceptedSocket = serverSocket.accept();

        while (!clientChannel.finishConnect()) {
            Thread.yield();
        }

        selector = Selector.open();
        return clientChannel.register(selector, SelectionKey.OP_READ);
    }

    /**
     * The sender now queues outbound data first and relies on the selector
     * thread to flush it later. Tests call flushPendingWrites() directly so the
     * old socket assertions can still verify the bytes that would be written.
     */
    private void flushQueuedWrites() throws IOException {
        sender.flushPendingWrites();
    }

    @Test
    void testSendWritesDataToChannel() throws Exception {
        // Verifies that a normal queued command is flushed to the socket verbatim.
        SelectionKey key = createConnection();
        sender.setKey(key, selector);

        sender.send("hello server");
        flushQueuedWrites();

        InputStream in = acceptedSocket.getInputStream();
        byte[] buf = new byte[256];
        int len = in.read(buf);
        String received = new String(buf, 0, len);

        Assertions.assertEquals("hello server\r\n", received);
    }

    @Test
    void testSendMultipleMessages() throws Exception {
        // Verifies that multiple queued commands keep their order when flushed.
        SelectionKey key = createConnection();
        sender.setKey(key, selector);

        sender.send("first");
        sender.send("second");
        sender.send("third");
        flushQueuedWrites();

        InputStream in = acceptedSocket.getInputStream();
        byte[] buf = new byte[256];
        int totalLen = 0;
        String expected = "first\r\nsecond\r\nthird\r\n";
        while (totalLen < expected.length()) {
            int len = in.read(buf, totalLen, buf.length - totalLen);
            if (len == -1) break;
            totalLen += len;
        }
        Assertions.assertEquals(expected, new String(buf, 0, totalLen));
    }

    @Test
    void testSendResetsInterestOpsToRead() throws Exception {
        // Verifies that draining the queue removes OP_WRITE and leaves OP_READ enabled.
        SelectionKey key = createConnection();
        sender.setKey(key, selector);

        sender.send("test");
        Assertions.assertEquals(SelectionKey.OP_READ | SelectionKey.OP_WRITE, key.interestOps());
        flushQueuedWrites();

        // After the queued write is flushed, the key should return to read mode.
        Assertions.assertEquals(SelectionKey.OP_READ, key.interestOps());
    }

    @Test
    void testSendEmptyString() throws Exception {
        // Verifies that an empty command still becomes one framed CRLF message.
        SelectionKey key = createConnection();
        sender.setKey(key, selector);

        // Empty input still becomes a framed CRLF message and is flushed later.
        sender.send("");
        Assertions.assertEquals(SelectionKey.OP_READ | SelectionKey.OP_WRITE, key.interestOps());
        flushQueuedWrites();

        Assertions.assertEquals(SelectionKey.OP_READ, key.interestOps());
    }

    @Test
    void testSendPreservesExistingCrLfDelimiter() throws Exception {
        // Verifies that already framed commands are not double-terminated.
        SelectionKey key = createConnection();
        sender.setKey(key, selector);

        sender.send("already framed\r\n");
        flushQueuedWrites();

        InputStream in = acceptedSocket.getInputStream();
        byte[] buf = new byte[256];
        int len = in.read(buf);

        Assertions.assertEquals("already framed\r\n", new String(buf, 0, len));
    }

    @Test
    void testSendNormalizesTrailingLfToCrLf() throws Exception {
        // Verifies that Unix line endings are normalized to the wire delimiter.
        SelectionKey key = createConnection();
        sender.setKey(key, selector);

        sender.send("unix newline\n");
        flushQueuedWrites();

        InputStream in = acceptedSocket.getInputStream();
        byte[] buf = new byte[256];
        int len = in.read(buf);

        Assertions.assertEquals("unix newline\r\n", new String(buf, 0, len));
    }

    @Test
    void testSendNormalizesTrailingCrToCrLf() throws Exception {
        // Verifies that a trailing CR is completed into one CRLF delimiter.
        SelectionKey key = createConnection();
        sender.setKey(key, selector);

        sender.send("carriage return only\r");
        flushQueuedWrites();

        InputStream in = acceptedSocket.getInputStream();
        byte[] buf = new byte[256];
        int len = in.read(buf);

        Assertions.assertEquals("carriage return only\r\n", new String(buf, 0, len));
    }

    @Test
    void testSendLargeMessage() throws Exception {
        // Verifies that long payloads are fully flushed without truncation.
        SelectionKey key = createConnection();
        sender.setKey(key, selector);

        // Send a large message — exercises the write loop's hasRemaining() check
        String largeMessage = "X".repeat(5000);
        sender.send(largeMessage);
        flushQueuedWrites();

        InputStream in = acceptedSocket.getInputStream();
        byte[] buf = new byte[8192];
        int totalLen = 0;
        while (totalLen < largeMessage.length() + 2) {
            int len = in.read(buf, totalLen, buf.length - totalLen);
            if (len == -1) break;
            totalLen += len;
        }
        Assertions.assertEquals(largeMessage + "\r\n", new String(buf, 0, totalLen));
    }

    @Test
    void testSendBlocksUntilKeyIsSet() throws Exception {
        // Verifies that send() waits for the listener to publish a selection key.
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> future = executor.submit(() -> sender.send("blocked"));

            // Give time for send() to enter wait()
            Thread.sleep(150);
            Assertions.assertFalse(future.isDone(), "send() should block until setKey() is called");

            // Set the key — this should unblock send()
            SelectionKey key = createConnection();
            sender.setKey(key, selector);

            future.get(2, TimeUnit.SECONDS);
            Assertions.assertTrue(future.isDone());
            flushQueuedWrites();

            // Verify the queued bytes are written once the selector flushes them.
            InputStream in = acceptedSocket.getInputStream();
            byte[] buf = new byte[256];
            int len = in.read(buf);
            Assertions.assertEquals("blocked\r\n", new String(buf, 0, len));
        }
    }

    @Test
    void testSendHandlesCancelledKey() throws Exception {
        // Verifies that a cancelled key does not escape send() as an exception.
        SelectionKey key = createConnection();
        sender.setKey(key, selector);

        // Close the channel → key becomes cancelled
        clientChannel.close();

        // send() calls key.interestOps() which throws CancelledKeyException — now caught gracefully
        Assertions.assertDoesNotThrow(() -> sender.send("after close"));
    }

    @Test
    void testSendHandlesInterruptDuringWait() {
        // Verifies that send() preserves the interrupt flag when wait() is interrupted.
        // key is null by default → send() will enter wait()
        // With interrupt flag set, wait() immediately throws InterruptedException
        Thread.currentThread().interrupt();

        Assertions.assertDoesNotThrow(() -> sender.send("interrupted"));

        // send() catches InterruptedException and re-sets the interrupt flag
        Assertions.assertTrue(Thread.interrupted());
    }

    @Test
    void testSetKeyToNullCausesSendToBlockAgain() throws Exception {
        // Verifies that clearing the key makes later sends wait for a new connection again.
        SelectionKey key = createConnection();
        sender.setKey(key, selector);

        // Setting key to null should cause next send() to block
        sender.setKey(null, null);

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> future = executor.submit(() -> sender.send("should block"));

            Thread.sleep(150);
            Assertions.assertFalse(future.isDone(), "send() should block after key was set to null");
        }
    }

    @Test
    void testFlushPendingWritesThrowsIOExceptionFromChannelWrite() {
        // Verifies that a channel write failure is propagated out of the flush step.
        ScriptedWriteSocketChannel channel = new ScriptedWriteSocketChannel(new IOException("write failed"));
        SelectionKey key = new FakeSelectionKey(channel);

        sender.setKey(key, null);
        sender.send("test");

        Assertions.assertThrows(IOException.class, () -> sender.flushPendingWrites());
    }

    @Test
    void testFlushPendingWritesReturnsWhenKeyMissing() {
        // Verifies that flushPendingWrites() becomes a no-op when no connection key is available.
        sender.setKey(null, null);

        Assertions.assertDoesNotThrow(() -> sender.flushPendingWrites());
    }

    @Test
    void testFlushPendingWritesHandlesCancelledKeyDuringRestore() throws Exception {
        // Verifies that a channel becoming invalid during restore still fails the flush.
        SelectionKey key = createConnection();
        sender.setKey(key, selector);

        sender.send("after flush");
        clientChannel.close();

        Assertions.assertThrows(IOException.class, () -> sender.flushPendingWrites());
    }

    @Test
    void testSendUsesUtf8EvenWhenDefaultCharsetDiffers() throws Exception {
        // Verifies that the sender always frames bytes as UTF-8, regardless of JVM default charset.
        ProcessBuilder processBuilder = new ProcessBuilder(
                javaExecutable(),
                "-Dfile.encoding=ISO-8859-1",
                "-cp",
                System.getProperty("java.class.path"),
                SelectorMessageSenderTest.class.getName(),
                "--encoding-probe");
        processBuilder.environment().remove("JAVA_TOOL_OPTIONS");

        Process process = processBuilder.start();

        int exitCode = process.waitFor();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        String errorOutput = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();

        Assertions.assertEquals(0, exitCode, errorOutput);
        Assertions.assertEquals("D09FD180D0B8D0B2D0B5D1820D0A", output);
    }

    @Test
    void testSendQueuesMessageAndEnablesWriteInterestWithoutWritingImmediately() {
        // Verifies that send() only queues data and leaves the actual write for the selector thread.
        TrackingSelector trackingSelector = new TrackingSelector();
        ScriptedWriteSocketChannel channel = new ScriptedWriteSocketChannel();
        FakeSelectionKey key = new FakeSelectionKey(channel);
        sender.setKey(key, trackingSelector);

        Assertions.assertDoesNotThrow(() -> sender.send("hello"));

        Assertions.assertEquals(SelectionKey.OP_READ | SelectionKey.OP_WRITE, key.interestOps());
        Assertions.assertEquals("", channel.writtenText());
        Assertions.assertTrue(trackingSelector.wakeupCount > 0);
    }

    @Test
    void testFlushPendingWritesStopsOnZeroWriteAndKeepsWriteInterest() throws IOException {
        // Verifies that a zero-byte non-blocking write keeps OP_WRITE enabled for a retry later.
        TrackingSelector trackingSelector = new TrackingSelector();
        ScriptedWriteSocketChannel channel = new ScriptedWriteSocketChannel(null, 3, 0, 4);
        FakeSelectionKey key = new FakeSelectionKey(channel);
        sender.setKey(key, trackingSelector);

        sender.send("hello");
        sender.flushPendingWrites();

        Assertions.assertEquals("hel", channel.writtenText());
        Assertions.assertEquals(SelectionKey.OP_READ | SelectionKey.OP_WRITE, key.interestOps());
    }

    @Test
    void testFlushPendingWritesRestoresReadInterestAfterQueueDrains() throws IOException {
        // Verifies that finishing the queued write turns OP_WRITE back off.
        TrackingSelector trackingSelector = new TrackingSelector();
        ScriptedWriteSocketChannel channel = new ScriptedWriteSocketChannel(null, 3, 0, 4);
        FakeSelectionKey key = new FakeSelectionKey(channel);
        sender.setKey(key, trackingSelector);

        sender.send("hello");
        sender.flushPendingWrites();
        sender.flushPendingWrites();

        Assertions.assertEquals("hello\r\n", channel.writtenText());
        Assertions.assertEquals(SelectionKey.OP_READ, key.interestOps());
    }

    @Test
    void testSendReturnsWhenKeyIsClearedBeforeQueueing() throws Exception {
        // Verifies the race where key exists before awaitSelectionKey() returns but is cleared before queueing.
        TrackingSelector trackingSelector = new TrackingSelector();
        ScriptedWriteSocketChannel channel = new ScriptedWriteSocketChannel();
        FakeSelectionKey key = new FakeSelectionKey(channel);
        sender.setKey(key, trackingSelector);
        Thread senderThread = new Thread(() -> sender.send("lost"));
        SelectorMessageSender lockedSender = sender;

        synchronized (lockedSender) {
            senderThread.start();
            waitForBlockedState(senderThread);
            clearSenderState();
        }

        senderThread.join(TimeUnit.SECONDS.toMillis(2));

        Assertions.assertFalse(senderThread.isAlive());
        Assertions.assertEquals("", channel.writtenText());
        Assertions.assertEquals(SelectionKey.OP_READ, key.interestOps());
        Assertions.assertEquals(0, trackingSelector.wakeupCount);
    }

    /**
     * Gives the forked JVM a small standalone probe so the UTF-8 test can reuse
     * this class instead of keeping a separate encoding-only test class.
     */
    public static void main(String[] args) throws IOException {
        if (args.length == 0 || !"--encoding-probe".equals(args[0])) {
            return;
        }

        ScriptedWriteSocketChannel channel = new ScriptedWriteSocketChannel();
        FakeSelectionKey key = new FakeSelectionKey(channel);
        SelectorMessageSender sender = new SelectorMessageSender();
        sender.setKey(key, null);
        sender.send("Привет");
        sender.flushPendingWrites();
        System.out.print(channel.hexDump());
    }

    private static String javaExecutable() {
        return System.getProperty("java.home") + "/bin/java";
    }

    /**
     * Uses reflection to recreate the narrow race where another thread clears
     * the sender state after awaitSelectionKey() returns but before queueing starts.
     */
    private void clearSenderState() throws Exception {
        java.lang.reflect.Field keyField = SelectorMessageSender.class.getDeclaredField("key");
        keyField.setAccessible(true);
        keyField.set(sender, null);

        java.lang.reflect.Field selectorField = SelectorMessageSender.class.getDeclaredField("selector");
        selectorField.setAccessible(true);
        selectorField.set(sender, null);
    }

    /**
     * Supplies the sender with a minimal selection key so the test can focus
     * on sender state changes instead of selector framework setup.
     */
    private static final class FakeSelectionKey extends SelectionKey {
        private final SocketChannel channel;
        private int interestOps = SelectionKey.OP_READ;

        private FakeSelectionKey(SocketChannel channel) {
            this.channel = channel;
        }

        @Override
        public SelectableChannel channel() {
            return channel;
        }

        @Override
        public Selector selector() {
            return null;
        }

        @Override
        public boolean isValid() {
            return true;
        }

        @Override
        public void cancel() {
            // no-op
        }

        @Override
        @SuppressWarnings("MagicConstant")
        public int interestOps() {
            return interestOps;
        }

        @Override
        public SelectionKey interestOps(int ops) {
            interestOps = ops;
            return this;
        }

        @Override
        @SuppressWarnings("MagicConstant")
        public int readyOps() {
            return interestOps;
        }
    }

    /**
     * Records wakeups so state-based tests can verify that send() notifies the
     * selector when new outbound data is queued.
     */
    private static final class TrackingSelector extends AbstractSelector {
        private int wakeupCount;

        private TrackingSelector() {
            super(SelectorProvider.provider());
        }

        @Override
        protected void implCloseSelector() {
            // no-op
        }

        @Override
        protected SelectionKey register(AbstractSelectableChannel ch, int ops, Object att) {
            throw new UnsupportedOperationException("register is not used in this test");
        }

        @Override
        public Set<SelectionKey> keys() {
            return Collections.emptySet();
        }

        @Override
        public Set<SelectionKey> selectedKeys() {
            return Collections.emptySet();
        }

        @Override
        public int selectNow() {
            return 0;
        }

        @Override
        public int select(long timeout) {
            return 0;
        }

        @Override
        public int select() {
            return 0;
        }

        @Override
        public Selector wakeup() {
            wakeupCount++;
            return this;
        }
    }

    /**
     * Replays scripted non-blocking write behavior and records the emitted
     * bytes so sender tests can cover queueing, backpressure, encoding, and errors.
     */
    private static final class ScriptedWriteSocketChannel extends SocketChannel {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final IOException writeFailure;
        private final int[] writeResults;
        private int writeIndex;

        private ScriptedWriteSocketChannel(int... writeResults) {
            this(null, writeResults);
        }

        private ScriptedWriteSocketChannel(IOException writeFailure, int... writeResults) {
            super(SelectorProvider.provider());
            this.writeFailure = writeFailure;
            this.writeResults = writeResults.clone();
        }

        String writtenText() {
            return bytes.toString(StandardCharsets.UTF_8);
        }

        String hexDump() {
            byte[] data = bytes.toByteArray();
            StringBuilder result = new StringBuilder(data.length * 2);
            for (byte value : data) {
                result.append(String.format("%02X", value));
            }
            return result.toString();
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            if (writeFailure != null) {
                throw writeFailure;
            }
            if (writeIndex < writeResults.length) {
                int scriptedResult = writeResults[writeIndex++];
                if (scriptedResult == 0) {
                    return 0;
                }
                return copyBytes(src, scriptedResult);
            }
            return copyBytes(src, src.remaining());
        }

        private int copyBytes(ByteBuffer src, int requestedBytes) {
            int bytesToCopy = Math.min(requestedBytes, src.remaining());
            byte[] data = new byte[bytesToCopy];
            src.get(data);
            bytes.writeBytes(data);
            return bytesToCopy;
        }

        @Override
        public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
            long total = 0;
            for (int i = offset; i < offset + length; i++) {
                total += write(srcs[i]);
            }
            return total;
        }

        @Override
        public int read(ByteBuffer dst) {
            return 0;
        }

        @Override
        public long read(ByteBuffer[] dsts, int offset, int length) {
            return 0;
        }

        @Override
        public SocketChannel bind(SocketAddress local) {
            return this;
        }

        @Override
        public <T> SocketChannel setOption(SocketOption<T> name, T value) {
            return this;
        }

        @Override
        public SocketChannel shutdownInput() {
            return this;
        }

        @Override
        public SocketChannel shutdownOutput() {
            return this;
        }

        @Override
        public Socket socket() {
            return new Socket();
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public boolean isConnectionPending() {
            return false;
        }

        @Override
        public boolean connect(SocketAddress remote) {
            return true;
        }

        @Override
        public boolean finishConnect() {
            return true;
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return InetSocketAddress.createUnresolved("localhost", 8077);
        }

        @Override
        public SocketAddress getLocalAddress() {
            return InetSocketAddress.createUnresolved("localhost", 0);
        }

        @Override
        public <T> T getOption(SocketOption<T> name) {
            return null;
        }

        @Override
        public Set<SocketOption<?>> supportedOptions() {
            return Collections.emptySet();
        }

        @Override
        protected void implCloseSelectableChannel() {
            // no-op
        }

        @Override
        protected void implConfigureBlocking(boolean block) {
            // no-op
        }
    }

    /**
     * Waits until the background sender thread is blocked on the sender monitor
     * so the race test can clear the key at the precise point it needs.
     */
    private void waitForBlockedState(Thread thread) {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(1);
        while (thread.getState() != Thread.State.BLOCKED && System.currentTimeMillis() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        Assertions.assertEquals(Thread.State.BLOCKED, thread.getState());
    }
}
