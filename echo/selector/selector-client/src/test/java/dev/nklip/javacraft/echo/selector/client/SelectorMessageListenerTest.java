package dev.nklip.javacraft.echo.selector.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SelectableChannel;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.AbstractSelectableChannel;
import java.nio.channels.spi.AbstractSelector;
import java.nio.channels.spi.SelectorProvider;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SelectorMessageListenerTest {

    private ServerSocket serverSocket;

    @AfterEach
    void tearDown() throws IOException {
        if (serverSocket != null && !serverSocket.isClosed()) {
            serverSocket.close();
        }
    }

    // newResponse() behavior

    @Test
    void testBufferSizeConstant() {
        // Verifies that the listener keeps the expected socket read buffer size.
        Assertions.assertEquals(2048, SelectorMessageListener.BUFFER_SIZE);
    }

    @Test
    void testNewResponseReadsMessage() throws Exception {
        // Verifies that one framed server response is returned as one message.
        openServerSocket();
        try (SocketChannel client = SocketChannel.open(new InetSocketAddress("localhost", serverSocket.getLocalPort()));
             Socket server = serverSocket.accept()) {
            server.getOutputStream().write("hello world\r\n".getBytes(StandardCharsets.UTF_8));
            server.getOutputStream().flush();
            Thread.sleep(50);

            SelectorMessageListener listener = new SelectorMessageListener(new SelectorNetworkManager());

            Assertions.assertEquals("hello world", listener.newResponse(client));
        }
    }

    @Test
    void testNewResponsePreservesWhitespaceInsideFrame() throws Exception {
        // Verifies that payload whitespace survives framing removal unchanged.
        openServerSocket();
        try (SocketChannel client = SocketChannel.open(new InetSocketAddress("localhost", serverSocket.getLocalPort()));
             Socket server = serverSocket.accept()) {
            server.getOutputStream().write("  padded message  \r\n".getBytes(StandardCharsets.UTF_8));
            server.getOutputStream().flush();
            Thread.sleep(50);

            SelectorMessageListener listener = new SelectorMessageListener(new SelectorNetworkManager());

            Assertions.assertEquals("  padded message  ", listener.newResponse(client));
        }
    }

    @Test
    void testNewResponseReturnsNullOnEof() throws Exception {
        // Verifies that EOF closes the channel and does not fabricate a message.
        openServerSocket();
        try (SocketChannel client = SocketChannel.open(new InetSocketAddress("localhost", serverSocket.getLocalPort()))) {
            Socket server = serverSocket.accept();
            server.close();
            Thread.sleep(50);

            SelectorMessageListener listener = new SelectorMessageListener(new SelectorNetworkManager());

            Assertions.assertNull(listener.newResponse(client));
        }
    }

    @Test
    void testNewResponseReturnsNullOnClosedChannel() throws Exception {
        // Verifies that reading from an already closed channel just yields no message.
        openServerSocket();
        SocketChannel client = SocketChannel.open(new InetSocketAddress("localhost", serverSocket.getLocalPort()));
        serverSocket.accept().close();
        client.close();

        SelectorMessageListener listener = new SelectorMessageListener(new SelectorNetworkManager());

        Assertions.assertNull(listener.newResponse(client));
    }

    @Test
    void testNewResponseHandlesLargeMessage() throws Exception {
        // Verifies that a large but valid frame is returned without truncation.
        openServerSocket();
        try (SocketChannel client = SocketChannel.open(new InetSocketAddress("localhost", serverSocket.getLocalPort()));
             Socket server = serverSocket.accept()) {
            byte[] largePayload = new byte[2000];
            Arrays.fill(largePayload, (byte) 'A');
            server.getOutputStream().write(largePayload);
            server.getOutputStream().write("\r\n".getBytes(StandardCharsets.UTF_8));
            server.getOutputStream().flush();
            Thread.sleep(100);

            SelectorMessageListener listener = new SelectorMessageListener(new SelectorNetworkManager());
            String result = listener.newResponse(client);

            Assertions.assertNotNull(result);
            Assertions.assertEquals(2000, result.length());
        }
    }

    @Test
    void testNewResponseWaitsForCompleteFrameAcrossReads() {
        // Verifies that partial bytes stay buffered until the delimiter arrives.
        ScriptedSocketChannel channel = new ScriptedSocketChannel(
                new int[] {3, 0, 4, 0},
                new String[] {"hel", "", "lo\r\n", ""});
        SelectorMessageListener listener = new SelectorMessageListener(new SelectorNetworkManager());

        Assertions.assertNull(listener.newResponse(channel));
        Assertions.assertEquals("hello", listener.newResponse(channel));
    }

    @Test
    void testNewResponseSplitsMultipleFramesFromSingleRead() {
        // Verifies that one socket read containing two frames yields two queued messages.
        ScriptedSocketChannel channel = new ScriptedSocketChannel(
                new int[] {15, 0},
                new String[] {"first\r\nsecond\r\n", ""});
        SelectorMessageListener listener = new SelectorMessageListener(new SelectorNetworkManager());

        Assertions.assertEquals("first", listener.newResponse(channel));
        Assertions.assertEquals("second", listener.newResponse(channel));
        Assertions.assertNull(listener.newResponse(channel));
    }

    @Test
    void testNewResponseDecodesMultibyteCharacterSplitAcrossReads() {
        // Verifies that UTF-8 decoding waits for the full frame before decoding bytes.
        byte[] payload = "Привет\r\n".getBytes(StandardCharsets.UTF_8);
        ScriptedSocketChannel channel = new ScriptedSocketChannel(
                Arrays.copyOfRange(payload, 0, 1),
                Arrays.copyOfRange(payload, 1, payload.length));
        SelectorMessageListener listener = new SelectorMessageListener(new SelectorNetworkManager());

        Assertions.assertEquals("Привет", listener.newResponse(channel));
    }

    @Test
    void testNewResponseClosesChannelWhenFrameExceedsMaxBytes() {
        // Verifies that an unterminated oversized frame is rejected and the channel is closed.
        byte[] oversizedFrame = new byte[SelectorMessageListener.MAX_FRAME_BYTES + 1];
        Arrays.fill(oversizedFrame, (byte) 'A');
        ScriptedSocketChannel channel = new ScriptedSocketChannel(splitIntoChunks(oversizedFrame));
        SelectorMessageListener listener = new SelectorMessageListener(new SelectorNetworkManager());

        Assertions.assertNull(listener.newResponse(channel));
        Assertions.assertFalse(channel.isOpen());
    }

    @Test
    void testNewResponseIgnoresCloseFailureWhenOversizedFrameIsRejected() {
        // Verifies that oversized-frame cleanup does not leak a close exception back to the caller.
        byte[] oversizedFrame = new byte[SelectorMessageListener.MAX_FRAME_BYTES + 1];
        Arrays.fill(oversizedFrame, (byte) 'A');
        ScriptedSocketChannel channel = new ScriptedSocketChannel(splitIntoChunks(oversizedFrame));
        channel.failClose(new java.nio.channels.ClosedChannelException());
        SelectorMessageListener listener = new SelectorMessageListener(new SelectorNetworkManager());

        Assertions.assertNull(listener.newResponse(channel));
        Assertions.assertEquals(1, channel.getCloseCalls());
    }

    @Test
    void testNewResponseClosesChannelWhenDelimitedFrameExceedsMaxBytes() {
        // Verifies that an oversized frame is rejected even when the delimiter is already present.
        byte[] oversizedFrame = new byte[SelectorMessageListener.MAX_FRAME_BYTES + 3];
        Arrays.fill(oversizedFrame, 0, SelectorMessageListener.MAX_FRAME_BYTES + 1, (byte) 'A');
        oversizedFrame[SelectorMessageListener.MAX_FRAME_BYTES + 1] = '\r';
        oversizedFrame[SelectorMessageListener.MAX_FRAME_BYTES + 2] = '\n';
        ScriptedSocketChannel channel = new ScriptedSocketChannel(splitIntoChunks(oversizedFrame));
        SelectorMessageListener listener = new SelectorMessageListener(new SelectorNetworkManager());

        Assertions.assertNull(listener.newResponse(channel));
        Assertions.assertFalse(channel.isOpen());
    }

    @Test
    void testNewResponseIgnoresCloseFailureWhenDelimitedFrameIsRejected() {
        // Verifies that delimited oversized-frame cleanup ignores close failures too.
        byte[] oversizedFrame = new byte[SelectorMessageListener.MAX_FRAME_BYTES + 3];
        Arrays.fill(oversizedFrame, 0, SelectorMessageListener.MAX_FRAME_BYTES + 1, (byte) 'A');
        oversizedFrame[SelectorMessageListener.MAX_FRAME_BYTES + 1] = '\r';
        oversizedFrame[SelectorMessageListener.MAX_FRAME_BYTES + 2] = '\n';
        ScriptedSocketChannel channel = new ScriptedSocketChannel(splitIntoChunks(oversizedFrame));
        channel.failClose(new java.nio.channels.ClosedChannelException());
        SelectorMessageListener listener = new SelectorMessageListener(new SelectorNetworkManager());

        Assertions.assertNull(listener.newResponse(channel));
        Assertions.assertEquals(1, channel.getCloseCalls());
    }

    @Test
    void testNewResponseClosesChannelOnReadIOException() {
        // Verifies that a read failure closes the channel and returns no message.
        ReadFailingSocketChannel channel = new ReadFailingSocketChannel(null);
        SelectorMessageListener listener = new SelectorMessageListener(new SelectorNetworkManager());

        Assertions.assertNull(listener.newResponse(channel));
        Assertions.assertEquals(1, channel.getCloseCalls());
    }

    @Test
    void testNewResponseHandlesCloseFailureAfterReadIOException() {
        // Verifies that a read failure still returns cleanly when close() also fails.
        ReadFailingSocketChannel channel = new ReadFailingSocketChannel(new IOException("close also failed"));
        SelectorMessageListener listener = new SelectorMessageListener(new SelectorNetworkManager());

        Assertions.assertNull(listener.newResponse(channel));
        Assertions.assertEquals(1, channel.getCloseCalls());
    }

    // processReadyKey() behavior

    @Test
    void testProcessReadyKeyFlushesWritesAndReadsResponsesInSameCycle() throws IOException {
        // Verifies that one ready key can flush outbound writes and read a response in the same loop.
        SelectorNetworkManager manager = new SelectorNetworkManager();
        RecordingMessageSender sender = new RecordingMessageSender();
        manager.setSelectorMessageSender(sender);
        SelectorMessageListener listener = new SelectorMessageListener(manager);
        ReadyKey key = new ReadyKey(new ScriptedSocketChannel("pong\r\n".getBytes(StandardCharsets.UTF_8)),
                SelectionKey.OP_READ | SelectionKey.OP_WRITE);

        listener.processReadyKey(key, sender);

        Assertions.assertTrue(sender.flushCalled);
        Assertions.assertEquals("pong", manager.getMessage());
    }

    @Test
    void testProcessReadyKeyReadsWithoutFlushingWhenOnlyReadable() throws IOException {
        // Verifies that a readable-only key does not trigger an outbound flush.
        SelectorNetworkManager manager = new SelectorNetworkManager();
        RecordingMessageSender sender = new RecordingMessageSender();
        manager.setSelectorMessageSender(sender);
        SelectorMessageListener listener = new SelectorMessageListener(manager);
        ScriptedSocketChannel channel = new ScriptedSocketChannel("pong\r\n".getBytes(StandardCharsets.UTF_8));
        ReadyKey key = new ReadyKey(channel, SelectionKey.OP_READ);

        listener.processReadyKey(key, sender);

        Assertions.assertFalse(sender.flushCalled);
        Assertions.assertEquals("pong", manager.getMessage());
        Assertions.assertTrue(channel.getReadCalls() > 0);
    }

    @Test
    void testProcessReadyKeyFlushesWithoutReadingWhenOnlyWritable() throws IOException {
        // Verifies that a writable-only key does not consume inbound bytes.
        SelectorNetworkManager manager = new SelectorNetworkManager();
        RecordingMessageSender sender = new RecordingMessageSender();
        manager.setSelectorMessageSender(sender);
        SelectorMessageListener listener = new SelectorMessageListener(manager);
        ScriptedSocketChannel channel = new ScriptedSocketChannel("pong\r\n".getBytes(StandardCharsets.UTF_8));
        ReadyKey key = new ReadyKey(channel, SelectionKey.OP_WRITE);

        listener.processReadyKey(key, sender);

        Assertions.assertTrue(sender.flushCalled);
        Assertions.assertNull(manager.getMessage());
        Assertions.assertEquals(0, channel.getReadCalls());
    }

    // run() unit branches

    @Test
    void testRunStopsWhenSelectorIsUnavailable() {
        // Verifies that the loop exits quietly when the selector never becomes available.
        RecordingMessageSender sender = new RecordingMessageSender();
        RecordingNetworkManager manager = new RecordingNetworkManager(null, sender);
        SelectorMessageListener listener = new SelectorMessageListener(manager);

        listener.run();

        Assertions.assertFalse(manager.closeSocketCalled);
        Assertions.assertEquals(0, sender.clearCalls);
    }

    @Test
    void testRunClosesSocketAndClearsSenderOnIoException() {
        // Verifies that I/O failures trigger connection cleanup and sender reset.
        RecordingMessageSender sender = new RecordingMessageSender();
        sender.ioFailure = new IOException("flush failed");
        RecordingSelector selector = new RecordingSelector(new ReadyKey(new ScriptedSocketChannel(), SelectionKey.OP_WRITE));
        RecordingNetworkManager manager = new RecordingNetworkManager(selector, sender);
        SelectorMessageListener listener = new SelectorMessageListener(manager);

        listener.run();

        Assertions.assertTrue(manager.closeSocketCalled);
        Assertions.assertEquals(1, sender.clearCalls);
    }

    @Test
    void testRunClosesSocketAndClearsSenderOnUnexpectedException() {
        // Verifies that runtime failures still reset the client state like I/O failures do.
        RecordingMessageSender sender = new RecordingMessageSender();
        sender.runtimeFailure = new IllegalStateException("boom");
        RecordingSelector selector = new RecordingSelector(new ReadyKey(new ScriptedSocketChannel(), SelectionKey.OP_WRITE));
        RecordingNetworkManager manager = new RecordingNetworkManager(selector, sender);
        SelectorMessageListener listener = new SelectorMessageListener(manager);

        listener.run();

        Assertions.assertTrue(manager.closeSocketCalled);
        Assertions.assertEquals(1, sender.clearCalls);
    }

    @Test
    void testRunStopsImmediatelyWhenThreadIsAlreadyInterrupted() {
        // Verifies that an already interrupted thread never enters the select loop.
        RecordingMessageSender sender = new RecordingMessageSender();
        RecordingSelector selector = new RecordingSelector(new ReadyKey(new ScriptedSocketChannel(), SelectionKey.OP_WRITE));
        RecordingNetworkManager manager = new RecordingNetworkManager(selector, sender);
        SelectorMessageListener listener = new SelectorMessageListener(manager);

        Thread.currentThread().interrupt();
        try {
            listener.run();
        } finally {
            Assertions.assertTrue(Thread.interrupted());
        }

        Assertions.assertFalse(manager.closeSocketCalled);
        Assertions.assertEquals(0, sender.clearCalls);
    }

    // run() integration behavior

    @Test
    void testRunSetsKeyOnSenderAfterConnect() throws Exception {
        // Verifies that the listener publishes the connected selection key to the sender.
        openServerSocket();
        SelectorNetworkManager manager = new SelectorNetworkManager();
        SelectorMessageSender sender = new SelectorMessageSender();
        manager.setSelectorMessageSender(sender);
        SelectorMessageListener listener = new SelectorMessageListener(manager);
        ExecutorService listenerExec = Executors.newSingleThreadExecutor();
        try {
            listenerExec.execute(listener);

            manager.openSocket("localhost", serverSocket.getLocalPort());
            Socket serverSide = serverSocket.accept();
            Thread.sleep(300);

            try (ExecutorService sendExec = Executors.newSingleThreadExecutor()) {
                Future<?> sendFuture = sendExec.submit(() -> sender.send("ping"));
                sendFuture.get(2, TimeUnit.SECONDS);
            }

            serverSide.close();
        } finally {
            manager.closeSocket();
            listenerExec.shutdownNow();
        }
    }

    @Test
    void testRunQueuesReceivedMessages() throws Exception {
        // Verifies that one normal request-response round trip reaches the client queue.
        openServerSocket();
        SelectorNetworkManager manager = new SelectorNetworkManager();
        SelectorMessageSender sender = new SelectorMessageSender();
        manager.setSelectorMessageSender(sender);
        SelectorMessageListener listener = new SelectorMessageListener(manager);
        ExecutorService listenerExec = Executors.newSingleThreadExecutor();
        try {
            listenerExec.execute(listener);

            manager.openSocket("localhost", serverSocket.getLocalPort());
            Socket serverSide = serverSocket.accept();
            Thread.sleep(300);

            sender.send("hello");
            Thread.sleep(100);
            byte[] buffer = new byte[256];
            int length = serverSide.getInputStream().read(buffer);
            Assertions.assertEquals("hello\r\n", new String(buffer, 0, length, StandardCharsets.UTF_8));

            serverSide.getOutputStream().write("echo: hello\r\n".getBytes(StandardCharsets.UTF_8));
            serverSide.getOutputStream().flush();
            Thread.sleep(300);

            Assertions.assertEquals("echo: hello", manager.getMessage());
            serverSide.close();
        } finally {
            manager.closeSocket();
            listenerExec.shutdownNow();
        }
    }

    @Test
    void testRunDrainsMultipleResponsesFromSingleReadEvent() throws Exception {
        // Verifies that coalesced server responses are all drained from one read event.
        openServerSocket();
        SelectorNetworkManager manager = new SelectorNetworkManager();
        SelectorMessageSender sender = new SelectorMessageSender();
        manager.setSelectorMessageSender(sender);
        SelectorMessageListener listener = new SelectorMessageListener(manager);
        ExecutorService listenerExec = Executors.newSingleThreadExecutor();
        try {
            listenerExec.execute(listener);

            manager.openSocket("localhost", serverSocket.getLocalPort());
            Socket serverSide = serverSocket.accept();
            Thread.sleep(300);

            sender.send("ping");
            Thread.sleep(100);
            byte[] buffer = new byte[256];
            int length = serverSide.getInputStream().read(buffer);
            Assertions.assertEquals("ping\r\n", new String(buffer, 0, length, StandardCharsets.UTF_8));

            serverSide.getOutputStream().write("reply1\r\nreply2\r\n".getBytes(StandardCharsets.UTF_8));
            serverSide.getOutputStream().flush();
            Thread.sleep(300);

            Assertions.assertEquals("reply1", manager.getMessage());
            Assertions.assertEquals("reply2", manager.getMessage());
        } finally {
            manager.closeSocket();
            listenerExec.shutdownNow();
        }
    }

    @Test
    void testRunQueuesMultipleMessages() throws Exception {
        // Verifies that repeated request-response rounds keep working on one connection.
        openServerSocket();
        SelectorNetworkManager manager = new SelectorNetworkManager();
        SelectorMessageSender sender = new SelectorMessageSender();
        manager.setSelectorMessageSender(sender);
        SelectorMessageListener listener = new SelectorMessageListener(manager);
        ExecutorService listenerExec = Executors.newSingleThreadExecutor();
        try {
            listenerExec.execute(listener);

            manager.openSocket("localhost", serverSocket.getLocalPort());
            Socket serverSide = serverSocket.accept();
            Thread.sleep(300);

            byte[] buffer = new byte[256];
            sender.send("msg1");
            Thread.sleep(100);
            int length = serverSide.getInputStream().read(buffer);
            Assertions.assertEquals("msg1\r\n", new String(buffer, 0, length, StandardCharsets.UTF_8));

            serverSide.getOutputStream().write("reply1\r\n".getBytes(StandardCharsets.UTF_8));
            serverSide.getOutputStream().flush();
            Thread.sleep(300);
            Assertions.assertEquals("reply1", manager.getMessage());

            sender.send("msg2");
            Thread.sleep(100);
            length = serverSide.getInputStream().read(buffer);
            Assertions.assertEquals("msg2\r\n", new String(buffer, 0, length, StandardCharsets.UTF_8));

            serverSide.getOutputStream().write("reply2\r\n".getBytes(StandardCharsets.UTF_8));
            serverSide.getOutputStream().flush();
            Thread.sleep(300);
            Assertions.assertEquals("reply2", manager.getMessage());

            serverSide.close();
        } finally {
            manager.closeSocket();
            listenerExec.shutdownNow();
        }
    }

    @Test
    void testRunDoesNotQueueNullResponse() throws Exception {
        // Verifies that EOF after a real response does not enqueue a stray null entry.
        openServerSocket();
        SelectorNetworkManager manager = new SelectorNetworkManager();
        SelectorMessageSender sender = new SelectorMessageSender();
        manager.setSelectorMessageSender(sender);
        SelectorMessageListener listener = new SelectorMessageListener(manager);
        ExecutorService listenerExec = Executors.newSingleThreadExecutor();
        try {
            listenerExec.execute(listener);

            manager.openSocket("localhost", serverSocket.getLocalPort());
            Socket serverSide = serverSocket.accept();
            Thread.sleep(300);

            sender.send("ping");
            Thread.sleep(100);
            byte[] buffer = new byte[256];
            int length = serverSide.getInputStream().read(buffer);
            Assertions.assertEquals("ping\r\n", new String(buffer, 0, length, StandardCharsets.UTF_8));

            serverSide.getOutputStream().write("pong\r\n".getBytes(StandardCharsets.UTF_8));
            serverSide.getOutputStream().flush();
            serverSide.close();
            Thread.sleep(500);

            Assertions.assertEquals("pong", manager.getMessage());
            Assertions.assertNull(manager.getMessage());
        } finally {
            manager.closeSocket();
            listenerExec.shutdownNow();
        }
    }

    @Test
    void testOpenSocketThrowsBeforeListenerHandlesConnectionFailure() throws Exception {
        // Verifies that connect failures still surface from openSocket() before the listener can react.
        int deadPort;
        try (ServerSocket temp = new ServerSocket(0)) {
            deadPort = temp.getLocalPort();
        }

        SelectorNetworkManager manager = new SelectorNetworkManager();
        SelectorMessageSender sender = new SelectorMessageSender();
        manager.setSelectorMessageSender(sender);
        SelectorMessageListener listener = new SelectorMessageListener(manager);
        ExecutorService listenerExec = Executors.newSingleThreadExecutor();
        try {
            listenerExec.execute(listener);

            Assertions.assertThrows(IOException.class, () -> manager.openSocket("localhost", deadPort));
        } finally {
            manager.closeSocket();
            listenerExec.shutdownNow();
        }
    }

    /**
     * Opens the local server only for tests that need a real client-server socket pair.
     */
    private void openServerSocket() throws IOException {
        serverSocket = new ServerSocket(0);
    }

    /**
     * Splits a large synthetic payload into read-sized chunks so byte-stream tests
     * can mimic the production listener's fixed buffer reads.
     */
    private static byte[][] splitIntoChunks(byte[] payload) {
        int chunkSize = SelectorMessageListener.BUFFER_SIZE;
        int chunks = (payload.length + chunkSize - 1) / chunkSize;
        byte[][] result = new byte[chunks][];
        for (int index = 0; index < chunks; index++) {
            int start = index * chunkSize;
            int end = Math.min(payload.length, start + chunkSize);
            result[index] = Arrays.copyOfRange(payload, start, end);
        }
        return result;
    }

    /**
     * Feeds scripted read results to the listener so framing, UTF-8, and close
     * handling can be tested without depending on timing-sensitive socket I/O.
     */
    private static class ScriptedSocketChannel extends SocketChannel {
        private final int[] reads;
        private final byte[][] payloads;
        private int readIndex;
        private int readCalls;
        private int closeCalls;
        private IOException closeFailure;

        private ScriptedSocketChannel() {
            this(new int[0], new byte[0][]);
        }

        private ScriptedSocketChannel(byte[]... chunks) {
            this(readLengths(chunks), cloneChunks(chunks));
        }

        private ScriptedSocketChannel(int[] reads, String[] payloads) {
            this(reads, encodePayloads(payloads));
        }

        private ScriptedSocketChannel(int[] reads, byte[][] payloads) {
            super(SelectorProvider.provider());
            this.reads = reads.clone();
            this.payloads = cloneChunks(payloads);
        }

        int getReadCalls() {
            return readCalls;
        }

        int getCloseCalls() {
            return closeCalls;
        }

        /**
         * Lets defensive-close tests inject a close failure without a real failing socket.
         */
        void failClose(IOException failure) {
            closeFailure = failure;
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            readCalls++;
            if (readIndex >= reads.length) {
                return 0;
            }

            int next = reads[readIndex];
            if (next <= 0) {
                readIndex++;
                return next;
            }

            byte[] source = payloads[readIndex];
            int toCopy = Math.min(next, Math.min(source.length, dst.remaining()));
            dst.put(source, 0, toCopy);
            readIndex++;
            return toCopy;
        }

        @Override
        public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
            long total = 0;
            for (int i = offset; i < offset + length; i++) {
                int read = read(dsts[i]);
                if (read <= 0) {
                    return total == 0 ? read : total;
                }
                total += read;
            }
            return total;
        }

        @Override
        public int write(ByteBuffer src) {
            int written = src.remaining();
            src.position(src.limit());
            return written;
        }

        @Override
        public long write(ByteBuffer[] srcs, int offset, int length) {
            long total = 0;
            for (int i = offset; i < offset + length; i++) {
                total += write(srcs[i]);
            }
            return total;
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
        protected void implCloseSelectableChannel() throws IOException {
            closeCalls++;
            if (closeFailure != null) {
                throw closeFailure;
            }
        }

        @Override
        protected void implConfigureBlocking(boolean block) {
            // no-op
        }

        private static int[] readLengths(byte[][] chunks) {
            int[] result = new int[chunks.length];
            for (int index = 0; index < chunks.length; index++) {
                result[index] = chunks[index].length;
            }
            return result;
        }

        private static byte[][] encodePayloads(String[] payloads) {
            byte[][] result = new byte[payloads.length][];
            for (int index = 0; index < payloads.length; index++) {
                result[index] = payloads[index].getBytes(StandardCharsets.UTF_8);
            }
            return result;
        }

        private static byte[][] cloneChunks(byte[][] chunks) {
            byte[][] result = new byte[chunks.length][];
            for (int index = 0; index < chunks.length; index++) {
                result[index] = chunks[index].clone();
            }
            return result;
        }
    }

    /**
     * Forces the listener down the read-error branch while still reusing the
     * scripted close tracking from the base test channel.
     */
    private static final class ReadFailingSocketChannel extends ScriptedSocketChannel {
        private ReadFailingSocketChannel(IOException closeFailure) {
            if (closeFailure != null) {
                failClose(closeFailure);
            }
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            throw new IOException("read failed");
        }
    }

    /**
     * Records whether the listener flushes pending writes or clears the sender
     * after a run-loop failure.
     */
    private static final class RecordingMessageSender extends SelectorMessageSender {
        private boolean flushCalled;
        private IOException ioFailure;
        private RuntimeException runtimeFailure;
        private int clearCalls;

        @Override
        void flushPendingWrites() throws IOException {
            flushCalled = true;
            if (ioFailure != null) {
                throw ioFailure;
            }
            if (runtimeFailure != null) {
                throw runtimeFailure;
            }
        }

        @Override
        public void setKey(SelectionKey key, Selector selector) {
            if (key == null && selector == null) {
                clearCalls++;
            }
        }
    }

    /**
     * Lets ready-key tests expose exactly which selector flags the listener sees.
     */
    private static final class ReadyKey extends SelectionKey {
        private final SocketChannel channel;
        private final int readyOps;
        private int interestOps;
        private boolean valid = true;

        private ReadyKey(SocketChannel channel, int readyOps) {
            this.channel = channel;
            this.readyOps = readyOps;
            this.interestOps = readyOps;
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
            return valid;
        }

        @Override
        public void cancel() {
            valid = false;
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
            return readyOps;
        }
    }

    /**
     * Supplies a deterministic selector for run-loop tests that should not
     * depend on a real network connection.
     */
    private static final class RecordingSelector extends AbstractSelector {
        private final Set<SelectionKey> selectedKeys = new LinkedHashSet<>();

        private RecordingSelector(SelectionKey... keys) {
            super(SelectorProvider.provider());
            selectedKeys.addAll(Arrays.asList(keys));
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
            return selectedKeys;
        }

        @Override
        public int selectNow() {
            return selectedKeys.size();
        }

        @Override
        public int select(long timeout) {
            return selectedKeys.size();
        }

        @Override
        public int select() {
            return selectedKeys.size();
        }

        @Override
        public Selector wakeup() {
            return this;
        }
    }

    /**
     * Lets run-loop tests inject a selector and observe whether the listener
     * requests socket cleanup from the network manager.
     */
    private static final class RecordingNetworkManager extends SelectorNetworkManager {
        private final Selector selector;
        private final SelectorMessageSender sender;
        private boolean closeSocketCalled;

        private RecordingNetworkManager(Selector selector, SelectorMessageSender sender) {
            this.selector = selector;
            this.sender = sender;
        }

        @Override
        public Selector getSelector() {
            return selector;
        }

        @Override
        public SelectorMessageSender getSelectorMessageSender() {
            return sender;
        }

        @Override
        public void closeSocket() {
            closeSocketCalled = true;
        }
    }
}
