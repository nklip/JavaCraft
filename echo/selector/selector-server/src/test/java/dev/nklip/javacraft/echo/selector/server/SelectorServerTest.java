package dev.nklip.javacraft.echo.selector.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.AbstractSelectableChannel;
import java.nio.channels.spi.AbstractSelector;
import java.nio.channels.spi.SelectorProvider;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.time.Duration;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SelectorServerTest {

    private static final int PORT = 19076;
    private static ExecutorService executorService;

    @BeforeAll
    static void startServer() throws InterruptedException {
        executorService = Executors.newSingleThreadExecutor();
        executorService.execute(new SelectorServer(PORT));
        // Give the server time to bind
        Thread.sleep(500);
    }

    @AfterAll
    static void stopServer() {
        executorService.shutdownNow();
    }

    private String sendAndReceive(Socket socket, String message)
            throws IOException, InterruptedException {
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();

        out.write(frameClientMessage(message).getBytes());
        out.flush();
        Thread.sleep(200);

        byte[] buf = new byte[1024];
        int len = in.read(buf);
        return new String(buf, 0, len).trim();
    }

    private String frameClientMessage(String message) {
        if (message.endsWith("\r\n")) {
            return message;
        }
        return message + "\r\n";
    }

    @Test
    void testServerAcceptsConnection() throws IOException {
        try (Socket socket = new Socket("localhost", PORT)) {
            Assertions.assertTrue(socket.isConnected());
        }
    }

    @Test
    void testServerAcceptsMultipleConnections() throws IOException {
        try (Socket s1 = new Socket("localhost", PORT);
             Socket s2 = new Socket("localhost", PORT);
             Socket s3 = new Socket("localhost", PORT)) {
            Assertions.assertTrue(s1.isConnected());
            Assertions.assertTrue(s2.isConnected());
            Assertions.assertTrue(s3.isConnected());
        }
    }

    @Test
    void testServerEchosMessage() throws IOException, InterruptedException {
        try (Socket socket = new Socket("localhost", PORT)) {
            socket.setSoTimeout(2000);

            String response = sendAndReceive(socket, "hello world");
            Assertions.assertEquals("Did you say 'hello world'?", response);
        }
    }

    @Test
    void testServerRespondsToBye() throws IOException, InterruptedException {
        try (Socket socket = new Socket("localhost", PORT)) {
            socket.setSoTimeout(2000);

            String response = sendAndReceive(socket, "bye");
            Assertions.assertEquals("Have a good day!", response);
        }
    }

    @Test
    void testServerRespondsToStats() throws IOException, InterruptedException {
        try (Socket socket = new Socket("localhost", PORT)) {
            socket.setSoTimeout(2000);

            String response = sendAndReceive(socket, "stats");
            Assertions.assertTrue(response.endsWith("Simultaneously connected clients: 1"));
        }
    }

    @Test
    void testServerRespondsToCrLfOnly() throws IOException, InterruptedException {
        try (Socket socket = new Socket("localhost", PORT)) {
            socket.setSoTimeout(2000);

            String response = sendAndReceive(socket, "\r\n");
            Assertions.assertEquals("Please type something.", response);
        }
    }

    @Test
    void testServerHandlesMultipleMessagesOnSameConnection() throws IOException, InterruptedException {
        try (Socket socket = new Socket("localhost", PORT)) {
            socket.setSoTimeout(2000);

            String r1 = sendAndReceive(socket, "first");
            Assertions.assertEquals("Did you say 'first'?", r1);

            String r2 = sendAndReceive(socket, "second");
            Assertions.assertEquals("Did you say 'second'?", r2);
        }
    }

    // ── Direct unit tests for read() ─────────────────────────────────

    @Test
    void testReadReturnsContent() {
        SelectorServer server = new SelectorServer(0);
        ScriptedSocketChannel channel = new ScriptedSocketChannel(new int[] {7}, new String[] {"hello\r\n"});

        String result = server.read(channel);

        Assertions.assertEquals("hello\r\n", result);
    }

    @Test
    void testReadReturnsEmptyOnEof() throws Exception {
        SelectorServer server = new SelectorServer(0);
        try (ServerSocket ss = new ServerSocket(0)) {
            SocketChannel client = SocketChannel.open(
                    new InetSocketAddress("localhost", ss.getLocalPort()));
            Socket serverSide = ss.accept();
            serverSide.close(); // causes EOF on client side
            Thread.sleep(50);

            String result = server.read(client);
            Assertions.assertEquals("", result);

            client.close();
        }
    }

    @Test
    void testReadReturnsEmptyOnClosedChannel() throws Exception {
        SelectorServer server = new SelectorServer(0);
        try (ServerSocket ss = new ServerSocket(0)) {
            SocketChannel client = SocketChannel.open(
                    new InetSocketAddress("localhost", ss.getLocalPort()));
            ss.accept();
            client.close(); // close before read → IOException

            String result = server.read(client);
            Assertions.assertEquals("", result);
        }
    }

    // ── Direct unit tests for queued drainPendingWrites() ────────────

    @Test
    void testWriteReturnsTrueOnSuccess() throws Exception {
        SelectorServer server = new SelectorServer(0);
        try (ServerSocket ss = new ServerSocket(0)) {
            SocketChannel client = SocketChannel.open(
                    new InetSocketAddress("localhost", ss.getLocalPort()));
            Socket serverSide = ss.accept();

            queueResponse(server, client, "hello");
            Object result = drainPendingWrites(server, client);
            Assertions.assertEquals("COMPLETE", result.toString());

            byte[] buf = new byte[256];
            int len = serverSide.getInputStream().read(buf);
            Assertions.assertEquals("hello", new String(buf, 0, len));

            client.close();
            serverSide.close();
        }
    }

    @Test
    void testWriteReturnsFalseOnClosedChannel() throws Exception {
        SelectorServer server = new SelectorServer(0);
        try (ServerSocket ss = new ServerSocket(0)) {
            SocketChannel client = SocketChannel.open(
                    new InetSocketAddress("localhost", ss.getLocalPort()));
            ss.accept();
            client.close(); // close before write

            queueResponse(server, client, "hello");
            Object result = drainPendingWrites(server, client);
            Assertions.assertEquals("FAILED", result.toString());
        }
    }

    @Test
    void testBufferSizeConstant() {
        Assertions.assertEquals(2048, SelectorServer.BUFFER_SIZE);
    }

    @Test
    void testReadReturnsNullWhenNoDataAvailable() {
        SelectorServer server = new SelectorServer(0);
        ScriptedSocketChannel channel = new ScriptedSocketChannel(new int[] {0}, new String[] {""});

        String result = server.read(channel);

        Assertions.assertNull(result, "Non-blocking read with 0 bytes should not be treated as disconnect");
    }

    @Test
    void testReadOpAggregatesChunksAvailableInSameReadCycle() throws Exception {
        SelectorServer server = new SelectorServer(0);
        ScriptedSocketChannel channel = new ScriptedSocketChannel(
                new int[] {3, 4, 0},
                new String[] {"hel", "lo\r\n"});
        channel.configureBlocking(false);
        FakeSelectionKey key = new FakeSelectionKey(channel, SelectionKey.OP_READ);

        Method readOp = SelectorServer.class.getDeclaredMethod("readOp", SelectionKey.class);
        readOp.setAccessible(true);

        readOp.invoke(server, key);
        Assertions.assertEquals(SelectionKey.OP_READ | SelectionKey.OP_WRITE, key.interestOps(),
                "Complete payload should keep read interest while enabling queued writes");
        Assertions.assertNull(key.attachment());
    }

    @Test
    void testReadOpWaitsForCompleteFrameAcrossReadEvents() throws Exception {
        SelectorServer server = new SelectorServer(0);
        RecordingScriptedSocketChannel channel = new RecordingScriptedSocketChannel(
                new int[] {3, 0, 4, 0},
                new String[] {"hel", "", "lo\r\n", ""});
        channel.configureBlocking(false);
        FakeSelectionKey key = new FakeSelectionKey(channel, SelectionKey.OP_READ);

        Method readOp = SelectorServer.class.getDeclaredMethod("readOp", SelectionKey.class);
        readOp.setAccessible(true);

        readOp.invoke(server, key);
        Assertions.assertEquals(SelectionKey.OP_READ, key.interestOps(),
                "Partial data without the line delimiter must stay buffered");

        readOp.invoke(server, key);
        Assertions.assertEquals(SelectionKey.OP_READ | SelectionKey.OP_WRITE, key.interestOps());

        Method writeOp = SelectorServer.class.getDeclaredMethod("writeOp", SelectionKey.class);
        writeOp.setAccessible(true);
        writeOp.invoke(server, key);

        Assertions.assertEquals("Did you say 'hello'?\r\n", channel.writtenText());
    }

    @Test
    void testReadReturnsBufferedPendingRequestBeforeReadingAgain() {
        SelectorServer server = new SelectorServer(0);
        ScriptedSocketChannel channel = new ScriptedSocketChannel(
                new int[] {15, 0},
                new String[] {"first\r\nsecond\r\n", ""});

        String first = server.read(channel);
        String second = server.read(channel);

        Assertions.assertEquals("first\r\n", first);
        Assertions.assertEquals("second\r\n", second);
    }

    @Test
    void testWriteOpRespondsToEachFrameBufferedFromSingleRead() throws Exception {
        SelectorServer server = new SelectorServer(0);
        RecordingScriptedSocketChannel channel = new RecordingScriptedSocketChannel(
                new int[] {15, 0},
                new String[] {"first\r\nsecond\r\n", ""});
        channel.configureBlocking(false);
        FakeSelectionKey key = new FakeSelectionKey(channel, SelectionKey.OP_READ);

        Method readOp = SelectorServer.class.getDeclaredMethod("readOp", SelectionKey.class);
        readOp.setAccessible(true);
        readOp.invoke(server, key);

        Method writeOp = SelectorServer.class.getDeclaredMethod("writeOp", SelectionKey.class);
        writeOp.setAccessible(true);
        writeOp.invoke(server, key);

        Assertions.assertEquals(
                "Did you say 'first'?\r\nDid you say 'second'?\r\n",
                channel.writtenText());
        Assertions.assertEquals(SelectionKey.OP_READ, key.interestOps());
    }

    @Test
    void testWriteOpFlushesBufferedPendingWriteWhenAttachmentMissing() throws Exception {
        SelectorServer server = new SelectorServer(0);
        RecordingScriptedSocketChannel channel = new RecordingScriptedSocketChannel(
                new int[] {0},
                new String[] {""});
        FakeSelectionKey key = new FakeSelectionKey(channel, SelectionKey.OP_WRITE);
        key.attach(null);

        queueResponse(server, channel, "Simultaneously connected clients: 0\r\n");

        Method writeOp = SelectorServer.class.getDeclaredMethod("writeOp", SelectionKey.class);
        writeOp.setAccessible(true);
        writeOp.invoke(server, key);

        Assertions.assertEquals("Simultaneously connected clients: 0\r\n", channel.writtenText());
        Assertions.assertEquals(SelectionKey.OP_READ, key.interestOps());
    }

    @Test
    void testAcceptOpIgnoresNullClient() throws Exception {
        SelectorServer server = new SelectorServer(0);
        try (Selector selector = Selector.open()) {
            NullAcceptServerSocketChannel serverChannel = new NullAcceptServerSocketChannel();

            Method acceptOp = SelectorServer.class.getDeclaredMethod("acceptOp", Selector.class, ServerSocketChannel.class);
            acceptOp.setAccessible(true);
            acceptOp.invoke(server, selector, serverChannel);

            Assertions.assertEquals(0, getConnections(server));
        }
    }

    @Test
    void testAcceptOpTracksClientAfterSuccessfulSetup() throws Exception {
        // Verifies that a fully initialized accepted client is registered and counted once.
        SelectorServer server = new SelectorServer(0);
        AcceptingServerSocketChannel serverChannel = new AcceptingServerSocketChannel(new SetupAwareSocketChannel());
        try (RegisteringSelector selector = new RegisteringSelector()) {
            Method acceptOp = SelectorServer.class.getDeclaredMethod("acceptOp", Selector.class, ServerSocketChannel.class);
            acceptOp.setAccessible(true);
            acceptOp.invoke(server, selector, serverChannel);
        }

        Assertions.assertEquals(1, getConnections(server));
        Assertions.assertEquals(1, trackedRequestBuffers(server).size());
        Assertions.assertEquals(1, trackedPendingRequests(server).size());
    }

    @Test
    void testAcceptOpClosesAcceptedClientWhenSetupFails() throws Exception {
        // Verifies that a client-setup failure closes only the accepted socket and leaves the server state unchanged.
        SelectorServer server = new SelectorServer(0);
        SetupAwareSocketChannel acceptedClient = SetupAwareSocketChannel.failOnConfigure();
        AcceptingServerSocketChannel serverChannel = new AcceptingServerSocketChannel(acceptedClient);
        try (RegisteringSelector selector = new RegisteringSelector()) {
            Method acceptOp = SelectorServer.class.getDeclaredMethod("acceptOp", Selector.class, ServerSocketChannel.class);
            acceptOp.setAccessible(true);

            Assertions.assertDoesNotThrow(() -> acceptOp.invoke(server, selector, serverChannel));
        }
        Assertions.assertEquals(1, acceptedClient.closeAttempts);
        Assertions.assertFalse(acceptedClient.isOpen());
        Assertions.assertEquals(0, getConnections(server));
        Assertions.assertTrue(trackedRequestBuffers(server).isEmpty());
        Assertions.assertTrue(trackedPendingRequests(server).isEmpty());
    }

    @Test
    void testAcceptOpIgnoresAcceptedClientCloseFailureAfterSetupFails() throws Exception {
        // Verifies that cleanup still swallows a close failure after client setup already failed.
        SelectorServer server = new SelectorServer(0);
        SetupAwareSocketChannel acceptedClient = SetupAwareSocketChannel.failOnConfigureAndClose();
        AcceptingServerSocketChannel serverChannel = new AcceptingServerSocketChannel(acceptedClient);
        try (RegisteringSelector selector = new RegisteringSelector()) {
            Method acceptOp = SelectorServer.class.getDeclaredMethod("acceptOp", Selector.class, ServerSocketChannel.class);
            acceptOp.setAccessible(true);

            Assertions.assertDoesNotThrow(() -> acceptOp.invoke(server, selector, serverChannel));
        }
        Assertions.assertEquals(1, acceptedClient.closeAttempts);
        Assertions.assertEquals(0, getConnections(server));
    }

    @Test
    void testWriteReturnsFalseWhenChannelMakesNoProgress() {
        SelectorServer server = new SelectorServer(0);
        try (ScriptedSocketChannel channel = ScriptedSocketChannel.alwaysZeroWrites()) {
            Object result = Assertions.assertTimeoutPreemptively(
                    Duration.ofMillis(300),
                    () -> {
                        queueResponse(server, channel, "hello");
                        return drainPendingWrites(server, channel);
                    });

            Assertions.assertEquals("PENDING", result.toString(),
                    "Queued writes should stay pending if the channel never accepts bytes");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void testStopWithNullSelectorRef() {
        SelectorServer server = new SelectorServer(0);
        Assertions.assertDoesNotThrow(server::stop);
    }

    @Test
    void testStopWakesUpSelectorWhenPresent() throws Exception {
        SelectorServer server = new SelectorServer(0);
        WakeupSelector selector = new WakeupSelector();

        var selectorRef = SelectorServer.class.getDeclaredField("selectorRef");
        selectorRef.setAccessible(true);
        selectorRef.set(server, selector);

        server.stop();

        Assertions.assertTrue(selector.wokenUp);
    }

    @Test
    void testCloseTrackedClientsClosesEveryTrackedChannelAndClearsState() throws Exception {
        SelectorServer server = new SelectorServer(0);
        ScriptedSocketChannel requestBufferChannel = new ScriptedSocketChannel(new int[] {0}, new String[] {""});
        ScriptedSocketChannel pendingRequestChannel = new ScriptedSocketChannel(new int[] {0}, new String[] {""});
        ScriptedSocketChannel pendingWriteChannel = new ScriptedSocketChannel(new int[] {0}, new String[] {""});
        ScriptedSocketChannel closeAfterWriteChannel = new ScriptedSocketChannel(new int[] {0}, new String[] {""});
        setConnectionsToOne(server);

        trackedRequestBuffers(server).put(requestBufferChannel, new ByteArrayOutputStream());
        trackedPendingRequests(server).put(
                pendingRequestChannel,
                new java.util.ArrayDeque<>(java.util.List.of("hello\r\n")));
        trackedPendingWrites(server).put(
                pendingWriteChannel,
                new java.util.ArrayDeque<>(java.util.List.of(ByteBuffer.wrap("reply".getBytes(StandardCharsets.UTF_8)))));
        trackedCloseAfterWrite(server).add(closeAfterWriteChannel);

        invokeCloseTrackedClients(server);

        Assertions.assertFalse(requestBufferChannel.isOpen());
        Assertions.assertFalse(pendingRequestChannel.isOpen());
        Assertions.assertFalse(pendingWriteChannel.isOpen());
        Assertions.assertFalse(closeAfterWriteChannel.isOpen());
        Assertions.assertTrue(trackedRequestBuffers(server).isEmpty());
        Assertions.assertTrue(trackedPendingRequests(server).isEmpty());
        Assertions.assertTrue(trackedPendingWrites(server).isEmpty());
        Assertions.assertTrue(trackedCloseAfterWrite(server).isEmpty());
        Assertions.assertEquals(0, getConnections(server));
    }

    @Test
    void testCloseTrackedClientsContinuesAfterCloseFailure() throws Exception {
        SelectorServer server = new SelectorServer(0);
        SocketChannel failingChannel = mock(SocketChannel.class);
        SocketChannel healthyChannel = mock(SocketChannel.class);
        doThrow(new IOException("close failed")).when(failingChannel).close();

        trackedRequestBuffers(server).put(failingChannel, new ByteArrayOutputStream());
        trackedPendingWrites(server).put(
                healthyChannel,
                new java.util.ArrayDeque<>(java.util.List.of(ByteBuffer.wrap("reply".getBytes(StandardCharsets.UTF_8)))));

        invokeCloseTrackedClients(server);

        verify(failingChannel).close();
        verify(healthyChannel).close();
        Assertions.assertTrue(trackedRequestBuffers(server).isEmpty());
        Assertions.assertTrue(trackedPendingWrites(server).isEmpty());
    }

    @Test
    void testReadOpKeepsInterestOpsWhenReadReturnsNull() throws Exception {
        SelectorServer server = new SelectorServer(0);
        ScriptedSocketChannel channel = new ScriptedSocketChannel(new int[] {0}, new String[] {""});
        channel.configureBlocking(false);
        FakeSelectionKey key = new FakeSelectionKey(channel, SelectionKey.OP_READ);

        Method readOp = SelectorServer.class.getDeclaredMethod("readOp", SelectionKey.class);
        readOp.setAccessible(true);
        readOp.invoke(server, key);

        Assertions.assertEquals(SelectionKey.OP_READ, key.interestOps());
        Assertions.assertTrue(key.isValid());
    }

    @Test
    void testWriteOpWithNullAttachmentSwitchesBackToRead() throws Exception {
        SelectorServer server = new SelectorServer(0);
        ScriptedSocketChannel channel = new ScriptedSocketChannel(new int[] {0}, new String[] {""});
        FakeSelectionKey key = new FakeSelectionKey(channel, SelectionKey.OP_WRITE);
        key.attach(null);

        Method writeOp = SelectorServer.class.getDeclaredMethod("writeOp", SelectionKey.class);
        writeOp.setAccessible(true);
        writeOp.invoke(server, key);

        Assertions.assertEquals(SelectionKey.OP_READ, key.interestOps());
    }

    @Test
    void testWriteOpWithEmptyAttachmentSwitchesBackToRead() throws Exception {
        SelectorServer server = new SelectorServer(0);
        ScriptedSocketChannel channel = new ScriptedSocketChannel(new int[] {0}, new String[] {""});
        FakeSelectionKey key = new FakeSelectionKey(channel, SelectionKey.OP_WRITE);
        key.attach("");

        Method writeOp = SelectorServer.class.getDeclaredMethod("writeOp", SelectionKey.class);
        writeOp.setAccessible(true);
        writeOp.invoke(server, key);

        Assertions.assertEquals(SelectionKey.OP_READ, key.interestOps());
    }

    @Test
    void testCloseKeyHandlesChannelCloseIOException() throws Exception {
        SelectorServer server = new SelectorServer(0);
        ThrowOnCloseSocketChannel channel = new ThrowOnCloseSocketChannel();
        FakeSelectionKey key = new FakeSelectionKey(channel, SelectionKey.OP_READ);

        Method closeKey = SelectorServer.class.getDeclaredMethod("closeKey", SelectionKey.class);
        closeKey.setAccessible(true);
        closeKey.invoke(server, key);

        Assertions.assertFalse(key.isValid());
    }

    @Test
    void testCloseKeyCancelsNonSocketChannelKey() throws Exception {
        SelectorServer server = new SelectorServer(0);
        FakeServerSelectionKey key = new FakeServerSelectionKey(new NullAcceptServerSocketChannel(), SelectionKey.OP_ACCEPT);

        Method closeKey = SelectorServer.class.getDeclaredMethod("closeKey", SelectionKey.class);
        closeKey.setAccessible(true);
        closeKey.invoke(server, key);

        Assertions.assertFalse(key.isValid());
    }

    @Test
    void testReadOpCancelsKeyOnEof() throws Exception {
        SelectorServer server = new SelectorServer(0);
        ScriptedSocketChannel channel = new ScriptedSocketChannel(new int[] {-1}, new String[] {""});
        FakeSelectionKey key = new FakeSelectionKey(channel, SelectionKey.OP_READ);

        Method readOp = SelectorServer.class.getDeclaredMethod("readOp", SelectionKey.class);
        readOp.setAccessible(true);
        readOp.invoke(server, key);

        Assertions.assertFalse(key.isValid());
    }

    @Test
    void testWriteOpByeClosesKeyAndDecrementsConnections() throws Exception {
        SelectorServer server = new SelectorServer(0);
        ScriptedSocketChannel channel = new ScriptedSocketChannel(new int[] {5, 0}, new String[] {"bye\r\n", ""});
        FakeSelectionKey key = new FakeSelectionKey(channel, SelectionKey.OP_READ);

        setConnectionsToOne(server);
        Method readOp = SelectorServer.class.getDeclaredMethod("readOp", SelectionKey.class);
        readOp.setAccessible(true);
        readOp.invoke(server, key);

        Method writeOp = SelectorServer.class.getDeclaredMethod("writeOp", SelectionKey.class);
        writeOp.setAccessible(true);
        writeOp.invoke(server, key);

        Assertions.assertFalse(key.isValid());
        Assertions.assertEquals(0, getConnections(server));
    }

    @Test
    void testWriteOpKeepsKeyOpenWhenWriteIsPending() throws Exception {
        SelectorServer server = new SelectorServer(0);
        ScriptedSocketChannel channel = new ScriptedSocketChannel(new int[] {7, 0}, new String[] {"hello\r\n", ""});
        channel.alwaysZeroWrites = true;
        FakeSelectionKey key = new FakeSelectionKey(channel, SelectionKey.OP_READ);

        Method readOp = SelectorServer.class.getDeclaredMethod("readOp", SelectionKey.class);
        readOp.setAccessible(true);
        readOp.invoke(server, key);

        Method writeOp = SelectorServer.class.getDeclaredMethod("writeOp", SelectionKey.class);
        writeOp.setAccessible(true);
        writeOp.invoke(server, key);

        Assertions.assertTrue(key.isValid());
        Assertions.assertEquals(SelectionKey.OP_READ | SelectionKey.OP_WRITE, key.interestOps());
    }

    @Test
    void testWriteOpByeKeepsKeyOpenWhenGoodbyeWriteIsPending() throws Exception {
        SelectorServer server = new SelectorServer(0);
        ScriptedSocketChannel channel = new ScriptedSocketChannel(new int[] {5, 0}, new String[] {"bye\r\n", ""});
        channel.alwaysZeroWrites = true;
        FakeSelectionKey key = new FakeSelectionKey(channel, SelectionKey.OP_READ);

        setConnectionsToOne(server);
        Method readOp = SelectorServer.class.getDeclaredMethod("readOp", SelectionKey.class);
        readOp.setAccessible(true);
        readOp.invoke(server, key);

        Method writeOp = SelectorServer.class.getDeclaredMethod("writeOp", SelectionKey.class);
        writeOp.setAccessible(true);
        writeOp.invoke(server, key);

        Assertions.assertTrue(key.isValid());
        Assertions.assertEquals(1, getConnections(server));
        Assertions.assertEquals(SelectionKey.OP_READ | SelectionKey.OP_WRITE, key.interestOps());
    }

    @Test
    void testLoopHandlesSelectZeroAndStopsCleanly() throws Exception {
        SelectorServer server = new SelectorServer(0);
        ScriptedSelector selector = new ScriptedSelector(server, new int[] {0}, Collections.emptySet());
        NullAcceptServerSocketChannel serverChannel = new NullAcceptServerSocketChannel();

        Method loop = SelectorServer.class.getDeclaredMethod("loop", Selector.class, ServerSocketChannel.class);
        loop.setAccessible(true);
        loop.invoke(server, selector, serverChannel);

        Assertions.assertTrue(selector.selectCalls >= 1);
    }

    @Test
    void testLoopProcessesReadableKey() throws Exception {
        SelectorServer server = new SelectorServer(0);
        ScriptedSocketChannel channel = new ScriptedSocketChannel(new int[] {6, 0}, new String[] {"ping\r\n", ""});
        channel.configureBlocking(false);
        FakeSelectionKey key = new FakeSelectionKey(channel, SelectionKey.OP_READ);
        Set<SelectionKey> selectedKeys = new java.util.LinkedHashSet<>();
        selectedKeys.add(key);
        ScriptedSelector selector = new ScriptedSelector(server, new int[] {1}, selectedKeys);

        Method loop = SelectorServer.class.getDeclaredMethod("loop", Selector.class, ServerSocketChannel.class);
        loop.setAccessible(true);
        loop.invoke(server, selector, new NullAcceptServerSocketChannel());

        Assertions.assertEquals(SelectionKey.OP_READ | SelectionKey.OP_WRITE, key.interestOps());
        Assertions.assertNull(key.attachment());
    }

    @Test
    void testLoopProcessesWritableKey() throws Exception {
        SelectorServer server = new SelectorServer(0);
        RecordingScriptedSocketChannel channel = new RecordingScriptedSocketChannel(new int[] {0}, new String[] {""});
        channel.configureBlocking(false);
        FakeSelectionKey key = new FakeSelectionKey(channel, SelectionKey.OP_WRITE);
        queueResponse(server, channel, "Simultaneously connected clients: 0\r\n");
        Set<SelectionKey> selectedKeys = new java.util.LinkedHashSet<>();
        selectedKeys.add(key);
        ScriptedSelector selector = new ScriptedSelector(server, new int[] {1}, selectedKeys);

        Method loop = SelectorServer.class.getDeclaredMethod("loop", Selector.class, ServerSocketChannel.class);
        loop.setAccessible(true);
        loop.invoke(server, selector, new NullAcceptServerSocketChannel());

        Assertions.assertEquals("Simultaneously connected clients: 0\r\n", channel.writtenText());
        Assertions.assertEquals(SelectionKey.OP_READ, key.interestOps());
    }

    @Test
    void testLoopProcessesReadAndWriteWhenBothStatesAreReady() throws Exception {
        // Verifies that one selected key can drain a ready read and a ready write in the same selector cycle.
        SelectorServer server = new SelectorServer(0);
        RecordingScriptedSocketChannel channel = new RecordingScriptedSocketChannel(
                new int[] {6, 0},
                new String[] {"ping\r\n", ""});
        channel.configureBlocking(false);
        FakeSelectionKey key = new FakeSelectionKey(channel, SelectionKey.OP_READ | SelectionKey.OP_WRITE);
        Set<SelectionKey> selectedKeys = new java.util.LinkedHashSet<>();
        selectedKeys.add(key);
        ScriptedSelector selector = new ScriptedSelector(server, new int[] {1}, selectedKeys);

        Method loop = SelectorServer.class.getDeclaredMethod("loop", Selector.class, ServerSocketChannel.class);
        loop.setAccessible(true);
        loop.invoke(server, selector, new NullAcceptServerSocketChannel());

        Assertions.assertEquals("Did you say 'ping'?\r\n", channel.writtenText());
        Assertions.assertEquals(SelectionKey.OP_READ, key.interestOps());
    }

    @Test
    void testLoopCatchesProcessingExceptionAndClosesKey() throws Exception {
        SelectorServer server = new SelectorServer(0);
        ScriptedSocketChannel channel = new ScriptedSocketChannel(new int[] {3, 0}, new String[] {"x\r\n", ""});
        channel.configureBlocking(false);
        ThrowingInterestOpsKey key = new ThrowingInterestOpsKey(channel, SelectionKey.OP_READ);
        Set<SelectionKey> selectedKeys = new java.util.LinkedHashSet<>();
        selectedKeys.add(key);
        ScriptedSelector selector = new ScriptedSelector(server, new int[] {1}, selectedKeys);

        Method loop = SelectorServer.class.getDeclaredMethod("loop", Selector.class, ServerSocketChannel.class);
        loop.setAccessible(true);
        loop.invoke(server, selector, new NullAcceptServerSocketChannel());

        Assertions.assertFalse(key.isValid());
    }

    @Test
    void testLoopKeepsServerAcceptKeyOpenWhenAcceptedClientSetupFails() throws Exception {
        // Verifies the accept regression: a broken accepted client must not cause the listening key to be closed.
        SelectorServer server = new SelectorServer(0);
        AcceptingServerSocketChannel serverChannel = new AcceptingServerSocketChannel(SetupAwareSocketChannel.failOnConfigure());
        FakeServerSelectionKey key = new FakeServerSelectionKey(serverChannel, SelectionKey.OP_ACCEPT);
        Set<SelectionKey> selectedKeys = new java.util.LinkedHashSet<>();
        selectedKeys.add(key);
        ScriptedSelector selector = new ScriptedSelector(server, new int[] {1}, selectedKeys);

        Method loop = SelectorServer.class.getDeclaredMethod("loop", Selector.class, ServerSocketChannel.class);
        loop.setAccessible(true);
        loop.invoke(server, selector, serverChannel);

        Assertions.assertTrue(key.isValid());
        Assertions.assertEquals(0, getConnections(server));
    }

    // ── run() error handling ─────────────────────────────────────────

    @Test
    void testConstructorFailsWhenPortAlreadyBound() {
        Assertions.assertThrows(java.io.UncheckedIOException.class, () -> new SelectorServer(PORT),
                "Constructor should fail fast when the listening port is already in use");
    }

    // ── readOp cancel and connection counter ─────────────────────────

    @Test
    void testServerContinuesAfterClientAbruptClose() throws Exception {
        // Client connects and immediately closes → readOp gets EOF → key.cancel()
        Socket abrupt = new Socket("localhost", PORT);
        abrupt.setSoLinger(true, 0); // send RST on close
        abrupt.close();
        Thread.sleep(300);

        // Server should still be running
        try (Socket socket = new Socket("localhost", PORT)) {
            socket.setSoTimeout(2000);
            String response = sendAndReceive(socket, "still alive");
            Assertions.assertEquals("Did you say 'still alive'?", response);
        }
    }

    @Test
    void testConnectionCounterShowsActiveClients() throws IOException, InterruptedException {
        try (Socket s1 = new Socket("localhost", PORT);
             Socket s2 = new Socket("localhost", PORT)) {
            s1.setSoTimeout(2000);
            s2.setSoTimeout(2000);
            Thread.sleep(200);

            // With 2 clients connected, stats should show at least 2
            String response = sendAndReceive(s1, "stats");
            int count = Integer.parseInt(response.replace("Simultaneously connected clients: ", ""));
            Assertions.assertTrue(count >= 2,
                    "Expected at least 2 connections but got " + count);
        }
    }

    // ── read() edge cases ────────────────────────────────────────────

    @Test
    void testReadMultipleCallsReuseBuffer() {
        SelectorServer server = new SelectorServer(0);
        ScriptedSocketChannel channel = new ScriptedSocketChannel(
                new int[] {7, 8},
                new String[] {"first\r\n", "second\r\n"});

        String result1 = server.read(channel);
        Assertions.assertEquals("first\r\n", result1);

        String result2 = server.read(channel);
        Assertions.assertEquals("second\r\n", result2);
    }

    @Test
    void testReadHandlesUtf8Content() {
        SelectorServer server = new SelectorServer(0);
        String utf8Message = "Привет мир";
        String framedMessage = utf8Message + "\r\n";
        int byteCount = framedMessage.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        ScriptedSocketChannel channel = new ScriptedSocketChannel(
                new int[] {byteCount},
                new String[] {framedMessage});

        String result = server.read(channel);

        Assertions.assertEquals(framedMessage, result);
    }

    @Test
    void testReadDecodesMultibyteCharacterSplitAcrossReads() {
        SelectorServer server = new SelectorServer(0);
        byte[] payload = "Привет\r\n".getBytes(StandardCharsets.UTF_8);
        ScriptedByteSocketChannel channel = new ScriptedByteSocketChannel(
                Arrays.copyOfRange(payload, 0, 1),
                Arrays.copyOfRange(payload, 1, payload.length));

        String result = server.read(channel);

        Assertions.assertEquals("Привет\r\n", result);
    }

    @Test
    void testReadClosesChannelWhenFrameExceedsMaxBytes() {
        SelectorServer server = new SelectorServer(0);
        byte[] oversizedFrame = new byte[SelectorServer.MAX_FRAME_BYTES + 1];
        Arrays.fill(oversizedFrame, (byte) 'A');
        ScriptedByteSocketChannel channel = new ScriptedByteSocketChannel(splitIntoChunks(oversizedFrame));

        String result = server.read(channel);

        Assertions.assertEquals("", result);
        Assertions.assertFalse(channel.isOpen(), "Oversized frame should close the channel");
    }

    @Test
    void testReadIgnoresCloseFailureWhenOversizedFrameIsRejected() {
        SelectorServer server = new SelectorServer(0);
        byte[] oversizedFrame = new byte[SelectorServer.MAX_FRAME_BYTES + 1];
        Arrays.fill(oversizedFrame, (byte) 'A');
        CloseFailingScriptedByteSocketChannel channel = new CloseFailingScriptedByteSocketChannel(
                splitIntoChunks(oversizedFrame));

        String result = server.read(channel);

        Assertions.assertEquals("", result);
        Assertions.assertEquals(1, channel.closeAttempts, "Oversized request should still attempt channel close");
    }

    @Test
    void testReadClosesChannelWhenDelimitedFrameExceedsMaxBytes() {
        SelectorServer server = new SelectorServer(0);
        byte[] oversizedFrame = new byte[SelectorServer.MAX_FRAME_BYTES + 3];
        Arrays.fill(oversizedFrame, 0, SelectorServer.MAX_FRAME_BYTES + 1, (byte) 'A');
        oversizedFrame[SelectorServer.MAX_FRAME_BYTES + 1] = '\r';
        oversizedFrame[SelectorServer.MAX_FRAME_BYTES + 2] = '\n';
        ScriptedByteSocketChannel channel = new ScriptedByteSocketChannel(splitIntoChunks(oversizedFrame));

        String result = server.read(channel);

        Assertions.assertEquals("", result);
        Assertions.assertFalse(channel.isOpen(), "Delimited oversized request should close the channel");
    }

    @Test
    void testReadOpClosesSlowClientWhenQueuedResponsesExceedMaxBytes() throws Exception {
        // Verifies that one slow client cannot accumulate an unlimited outbound response backlog.
        SelectorServer server = new SelectorServer(0);
        String requestPayload = "x".repeat(maxEchoPayloadThatFitsQueuedWriteLimit());
        byte[] combinedPayload = (requestPayload + "\r\n" + requestPayload + "\r\n").getBytes(StandardCharsets.UTF_8);
        ScriptedByteSocketChannel channel = new ScriptedByteSocketChannel(splitIntoChunks(combinedPayload));
        channel.configureBlocking(false);
        FakeSelectionKey key = new FakeSelectionKey(channel, SelectionKey.OP_READ);
        setConnectionsToOne(server);

        Method readOp = SelectorServer.class.getDeclaredMethod("readOp", SelectionKey.class);
        readOp.setAccessible(true);
        readOp.invoke(server, key);
        Assertions.assertTrue(key.isValid(), "The first queued response should fit within the configured backlog limit");

        readOp.invoke(server, key);

        Assertions.assertFalse(key.isValid(), "The key should be cancelled once the queued response backlog exceeds the limit");
        Assertions.assertFalse(channel.isOpen(), "The slow client should be closed when its queued responses exceed the limit");
        Assertions.assertFalse(trackedPendingWrites(server).containsKey(channel));
        Assertions.assertEquals(0, getConnections(server));
    }

    @Test
    void testReadOpReturnsFalseForEmptyMessageWhenQueuedGuidanceWouldOverflowAndCloseFails() throws Exception {
        // Verifies the empty-message overload branch and the defensive close-failure catch for overloaded clients.
        SelectorServer server = new SelectorServer(0);
        CloseFailingScriptedByteSocketChannel channel = new CloseFailingScriptedByteSocketChannel("\r\n".getBytes(StandardCharsets.UTF_8));
        channel.configureBlocking(false);
        FakeSelectionKey key = new FakeSelectionKey(channel, SelectionKey.OP_READ);
        setConnectionsToOne(server);
        seedQueuedBytes(server, channel, SelectorServer.MAX_PENDING_WRITE_BYTES - "Please type something.\r\n".length() + 1);

        invokeReadOp(server, key);

        Assertions.assertFalse(key.isValid());
        Assertions.assertEquals(1, channel.closeAttempts);
        Assertions.assertEquals(0, getConnections(server));
    }

    @Test
    void testReadOpReturnsFalseForByeWhenQueuedGoodbyeWouldOverflow() throws Exception {
        // Verifies the goodbye overload branch so a slow client cannot queue an unbounded final response.
        SelectorServer server = new SelectorServer(0);
        ScriptedByteSocketChannel channel = new ScriptedByteSocketChannel("bye\r\n".getBytes(StandardCharsets.UTF_8));
        channel.configureBlocking(false);
        FakeSelectionKey key = new FakeSelectionKey(channel, SelectionKey.OP_READ);
        setConnectionsToOne(server);
        seedQueuedBytes(server, channel, SelectorServer.MAX_PENDING_WRITE_BYTES - "Have a good day!\r\n".length() + 1);

        invokeReadOp(server, key);

        Assertions.assertFalse(key.isValid());
        Assertions.assertFalse(channel.isOpen());
        Assertions.assertFalse(trackedCloseAfterWrite(server).contains(channel));
        Assertions.assertEquals(0, getConnections(server));
    }

    @Test
    void testReadOpReturnsFalseForStatsWhenQueuedStatsWouldOverflow() throws Exception {
        // Verifies the stats overload branch so the server closes a slow client instead of growing the write queue.
        SelectorServer server = new SelectorServer(0);
        ScriptedByteSocketChannel channel = new ScriptedByteSocketChannel("stats\r\n".getBytes(StandardCharsets.UTF_8));
        channel.configureBlocking(false);
        FakeSelectionKey key = new FakeSelectionKey(channel, SelectionKey.OP_READ);
        setConnectionsToOne(server);
        String statsResponse = "Simultaneously connected clients: 1\r\n";
        seedQueuedBytes(server, channel, SelectorServer.MAX_PENDING_WRITE_BYTES - statsResponse.length() + 1);

        invokeReadOp(server, key);

        Assertions.assertFalse(key.isValid());
        Assertions.assertFalse(channel.isOpen());
        Assertions.assertEquals(0, getConnections(server));
    }

    @Test
    void testOpenServerChannelCreatesSocketWhenPortIsZero() throws Exception {
        assumeSocketBindingAvailable();
        SelectorServer server = new SelectorServer(0);

        Method openServerChannel = SelectorServer.class.getDeclaredMethod("openServerChannel");
        openServerChannel.setAccessible(true);

        try (ServerSocketChannel channel = (ServerSocketChannel) openServerChannel.invoke(server)) {
            Assertions.assertTrue(channel.isOpen());
            Assertions.assertNotNull(channel.getLocalAddress());
        }
    }

    // ── Mockito-based tests for defensive catch blocks ────────────────

    @Test
    void testWriteReturnsEmptyOnGenericIOException() throws Exception {
        // Covers queued write failure on a generic IOException (not ClosedChannelException)
        SelectorServer server = new SelectorServer(0);
        SocketChannel mockChannel = mock(SocketChannel.class);
        when(mockChannel.write(any(ByteBuffer.class))).thenThrow(new IOException("broken pipe"));

        queueResponse(server, mockChannel, "test");
        Object result = drainPendingWrites(server, mockChannel);

        Assertions.assertEquals("FAILED", result.toString());
        verify(mockChannel).close();
    }

    @Test
    void testWriteHandlesCloseFailureAfterIOException() throws Exception {
        // Covers queued write close failure after the write itself throws
        SelectorServer server = new SelectorServer(0);
        SocketChannel mockChannel = mock(SocketChannel.class);
        when(mockChannel.write(any(ByteBuffer.class))).thenThrow(new IOException("broken pipe"));
        doThrow(new IOException("close failed")).when(mockChannel).close();

        queueResponse(server, mockChannel, "test");
        Object result = drainPendingWrites(server, mockChannel);

        Assertions.assertEquals("FAILED", result.toString());
    }

    @Test
    void testReadHandlesCloseFailureAfterIOException() throws Exception {
        // Covers read() inner catch(IOException) on channel.close() L141-143
        SelectorServer server = new SelectorServer(0);
        SocketChannel mockChannel = mock(SocketChannel.class);
        when(mockChannel.read(any(ByteBuffer.class))).thenThrow(new IOException("read failed"));
        doThrow(new IOException("close failed")).when(mockChannel).close();

        String result = server.read(mockChannel);

        Assertions.assertEquals("", result);
    }

    @Test
    void testWriteOpElseBranchClosesChannelAndCancelsKey() throws Exception {
        // Covers writeOp() else branch L181-184 — when write() returns false
        SelectorServer server = new SelectorServer(0);

        SocketChannel mockChannel = mock(SocketChannel.class);
        when(mockChannel.write(any(ByteBuffer.class))).thenThrow(new IOException("write failed"));

        SelectionKey mockKey = mock(SelectionKey.class);
        queueResponse(server, mockChannel, "Did you say 'hello'?\r\n");
        when(mockKey.attachment()).thenReturn(null);
        when(mockKey.channel()).thenReturn(mockChannel);

        // Call private writeOp() via reflection
        Method writeOp = SelectorServer.class.getDeclaredMethod("writeOp", SelectionKey.class);
        writeOp.setAccessible(true);
        writeOp.invoke(server, mockKey);

        // write() returned false → else branch executed: key.channel().close() + key.cancel()
        verify(mockKey).cancel();
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testRunFinallyCatchesExceptionOnClose() throws Exception {
        // Covers run() finally catch(Exception) L62-64 — when selector.close() throws
        try (MockedStatic<Selector> mockedSel = Mockito.mockStatic(Selector.class);
             MockedStatic<ServerSocketChannel> mockedSSC = Mockito.mockStatic(ServerSocketChannel.class)) {

            Selector badSelector = mock(Selector.class);
            doThrow(new IOException("selector close failed")).when(badSelector).close();
            mockedSel.when(Selector::open).thenReturn(badSelector);

            ServerSocketChannel badSSC = mock(ServerSocketChannel.class);
            java.net.ServerSocket mockSocket = mock(java.net.ServerSocket.class);
            doThrow(new IOException("bind failed")).when(mockSocket).bind(any());
            when(badSSC.socket()).thenReturn(mockSocket);
            mockedSSC.when(ServerSocketChannel::open).thenReturn(badSSC);

            SelectorServer server = new SelectorServer(0);
            // run(): bind() throws → catch(Exception) → finally: selector.close() throws → catch(Exception) L62-64
            Assertions.assertDoesNotThrow(server::run);

            verify(badSelector).close();
        }
    }

    private static int getConnections(SelectorServer server) throws Exception {
        var field = SelectorServer.class.getDeclaredField("connections");
        field.setAccessible(true);
        return ((java.util.concurrent.atomic.AtomicInteger) field.get(server)).get();
    }

    /**
     * Seeds one encoded response buffer without exposing the production helper
     * publicly just for test setup.
     */
    private static void queueResponse(SelectorServer server, SocketChannel channel, String response) throws Exception {
        Method queueResponse = SelectorServer.class.getDeclaredMethod("queueResponse", SocketChannel.class, String.class);
        queueResponse.setAccessible(true);
        queueResponse.invoke(server, channel, response);
    }

    /**
     * Invokes the private queued-write drain helper so the old direct write
     * tests can verify the new selector-driven behavior.
     */
    private static Object drainPendingWrites(SelectorServer server, SocketChannel channel) throws Exception {
        Method drainPendingWrites = SelectorServer.class.getDeclaredMethod("drainPendingWrites", SocketChannel.class);
        drainPendingWrites.setAccessible(true);
        return drainPendingWrites.invoke(server, channel);
    }

    /**
     * Invokes the private read readiness handler so overload tests can drive
     * the same path the selector loop uses in production.
     */
    private static void invokeReadOp(SelectorServer server, SelectionKey key) throws Exception {
        Method readOp = SelectorServer.class.getDeclaredMethod("readOp", SelectionKey.class);
        readOp.setAccessible(true);
        readOp.invoke(server, key);
    }

    /**
     * Invokes the shutdown cleanup helper directly so the tests can verify that
     * tracked clients are always closed even without running the full server loop.
     */
    private static void invokeCloseTrackedClients(SelectorServer server) throws Exception {
        Method closeTrackedClients = SelectorServer.class.getDeclaredMethod("closeTrackedClients");
        closeTrackedClients.setAccessible(true);
        closeTrackedClients.invoke(server);
    }

    private static void setConnectionsToOne(SelectorServer server) throws Exception {
        var field = SelectorServer.class.getDeclaredField("connections");
        field.setAccessible(true);
        ((java.util.concurrent.atomic.AtomicInteger) field.get(server)).set(1);
    }

    /**
     * Exposes the tracked request-buffer map so shutdown tests can seed the
     * same state that accepted sockets create in production.
     */
    @SuppressWarnings("unchecked")
    private static java.util.Map<SocketChannel, ByteArrayOutputStream> trackedRequestBuffers(SelectorServer server) throws Exception {
        var field = SelectorServer.class.getDeclaredField("requestBuffers");
        field.setAccessible(true);
        return (java.util.Map<SocketChannel, ByteArrayOutputStream>) field.get(server);
    }

    /**
     * Exposes the pending-request map so shutdown tests can cover channels
     * that were read but not yet fully handled when the server stops.
     */
    @SuppressWarnings("unchecked")
    private static java.util.Map<SocketChannel, java.util.Deque<String>> trackedPendingRequests(SelectorServer server) throws Exception {
        var field = SelectorServer.class.getDeclaredField("pendingRequests");
        field.setAccessible(true);
        return (java.util.Map<SocketChannel, java.util.Deque<String>>) field.get(server);
    }

    /**
     * Exposes the queued-write map so shutdown tests can cover channels that
     * are waiting for writable readiness during stop.
     */
    @SuppressWarnings("unchecked")
    private static java.util.Map<SocketChannel, java.util.Deque<ByteBuffer>> trackedPendingWrites(SelectorServer server) throws Exception {
        var field = SelectorServer.class.getDeclaredField("pendingWrites");
        field.setAccessible(true);
        return (java.util.Map<SocketChannel, java.util.Deque<ByteBuffer>>) field.get(server);
    }

    /**
     * Seeds queued bytes directly so overload tests can put one client just
     * past the backlog limit without going through the capped production helper.
     */
    private static void seedQueuedBytes(SelectorServer server, SocketChannel channel, int queuedBytes) throws Exception {
        trackedPendingWrites(server).put(
                channel,
                new java.util.ArrayDeque<>(java.util.List.of(ByteBuffer.wrap(new byte[queuedBytes]))));
    }

    /**
     * Exposes the close-after-write set so shutdown tests can cover goodbye
     * clients whose reads were already cleared before shutdown.
     */
    @SuppressWarnings("unchecked")
    private static java.util.Set<SocketChannel> trackedCloseAfterWrite(SelectorServer server) throws Exception {
        var field = SelectorServer.class.getDeclaredField("closeAfterWrite");
        field.setAccessible(true);
        return (java.util.Set<SocketChannel>) field.get(server);
    }

    /**
     * Splits a large payload into buffer-sized byte chunks so UTF-8 framing
     * tests can break multibyte characters across synthetic reads.
     */
    private static byte[][] splitIntoChunks(byte[] payload) {
        int chunkSize = SelectorServer.BUFFER_SIZE;
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
     * Skips the port-zero startup test when the environment forbids local
     * socket binding, which happens in this sandbox but not in normal runs.
     */
    private static void assumeSocketBindingAvailable() {
        try (ServerSocketChannel probe = ServerSocketChannel.open()) {
            probe.bind(new InetSocketAddress(0));
        } catch (Exception blocked) {
            Assumptions.assumeTrue(false, "Local socket binding is unavailable in this environment");
        }
    }

    /**
     * Keeps the slow-client backlog test aligned with the real echo framing so
     * the first response fits exactly once and the second one crosses the cap.
     */
    private static int maxEchoPayloadThatFitsQueuedWriteLimit() {
        return SelectorServer.MAX_PENDING_WRITE_BYTES - "Did you say ''?\r\n".length();
    }

    private static class FakeSelectionKey extends SelectionKey {
        private final SocketChannel channel;
        private int interestOps;
        private final int readyOps;
        private boolean valid = true;

        private FakeSelectionKey(SocketChannel channel, int interestOps) {
            this.channel = channel;
            this.interestOps = interestOps;
            this.readyOps = interestOps;
        }

        @Override
        public java.nio.channels.SelectableChannel channel() {
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

    private static final class FakeServerSelectionKey extends SelectionKey {
        private final java.nio.channels.SelectableChannel channel;
        private int interestOps;
        private final int readyOps;
        private boolean valid = true;

        private FakeServerSelectionKey(java.nio.channels.SelectableChannel channel, int interestOps) {
            this.channel = channel;
            this.interestOps = interestOps;
            this.readyOps = interestOps;
        }

        @Override
        public java.nio.channels.SelectableChannel channel() {
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

    private static final class ThrowingInterestOpsKey extends FakeSelectionKey {
        private ThrowingInterestOpsKey(SocketChannel channel, int interestOps) {
            super(channel, interestOps);
        }

        @Override
        public SelectionKey interestOps(int ops) {
            throw new RuntimeException("cannot update ops");
        }
    }

    private static final class NullAcceptServerSocketChannel extends ServerSocketChannel {
        private NullAcceptServerSocketChannel() {
            super(SelectorProvider.provider());
        }

        @Override
        public SocketChannel accept() {
            return null;
        }

        @Override
        public ServerSocket socket() {
            return null;
        }

        @Override
        public ServerSocketChannel bind(SocketAddress local, int backlog) {
            return this;
        }

        @Override
        public <T> ServerSocketChannel setOption(SocketOption<T> name, T value) {
            return this;
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
        public SocketAddress getLocalAddress() {
            return null;
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
     * Returns one scripted client from accept() so accept-path tests can drive
     * server-socket behavior without binding a real listening port.
     */
    private static final class AcceptingServerSocketChannel extends ServerSocketChannel {
        private final SocketChannel acceptedClient;

        private AcceptingServerSocketChannel(SocketChannel acceptedClient) {
            super(SelectorProvider.provider());
            this.acceptedClient = acceptedClient;
        }

        @Override
        public SocketChannel accept() {
            return acceptedClient;
        }

        @Override
        public ServerSocket socket() {
            return null;
        }

        @Override
        public ServerSocketChannel bind(SocketAddress local, int backlog) {
            return this;
        }

        @Override
        public <T> ServerSocketChannel setOption(SocketOption<T> name, T value) {
            return this;
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
        public SocketAddress getLocalAddress() {
            return null;
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
     * Supplies a minimal selector registration path for acceptOp() tests so a
     * synthetic accepted socket can be registered without a real selector.
     */
    private static final class RegisteringSelector extends AbstractSelector {
        private RegisteringSelector() {
            super(SelectorProvider.provider());
        }

        @Override
        protected void implCloseSelector() {
            // no-op
        }

        @Override
        protected SelectionKey register(AbstractSelectableChannel ch, int ops, Object att) {
            return new FakeSelectionKey((SocketChannel) ch, ops);
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
            return this;
        }
    }

    private static final class WakeupSelector extends Selector {
        private boolean wokenUp;

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public SelectorProvider provider() {
            return SelectorProvider.provider();
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
            wokenUp = true;
            return this;
        }

        @Override
        public void close() {
            // no-op
        }
    }

    private static final class ScriptedSelector extends Selector {
        private final SelectorServer server;
        private final int[] selectResults;
        private final Set<SelectionKey> selectedKeys;
        private int cursor;
        private int selectCalls;

        private ScriptedSelector(SelectorServer server, int[] selectResults, Set<SelectionKey> selectedKeys) {
            this.server = server;
            this.selectResults = Arrays.copyOf(selectResults, selectResults.length);
            this.selectedKeys = selectedKeys;
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public SelectorProvider provider() {
            return SelectorProvider.provider();
        }

        @Override
        public Set<SelectionKey> keys() {
            return selectedKeys;
        }

        @Override
        public Set<SelectionKey> selectedKeys() {
            return selectedKeys;
        }

        @Override
        public int selectNow() {
            return 0;
        }

        @Override
        public int select(long timeout) {
            return select();
        }

        @Override
        public int select() {
            selectCalls++;
            if (cursor < selectResults.length) {
                int result = selectResults[cursor++];
                server.stop();
                return result;
            }
            return 0;
        }

        @Override
        public Selector wakeup() {
            return this;
        }

        @Override
        public void close() {
            // no-op
        }
    }

    private static class ScriptedSocketChannel extends SocketChannel {
        private static final int MAX_ZERO_WRITE_SPINS = 2048;

        private final int[] reads;
        private final byte[][] payloads;
        private int readIndex;
        private boolean alwaysZeroWrites;
        private boolean open = true;

        private ScriptedSocketChannel(int[] reads, String[] payloads) {
            super(SelectorProvider.provider());
            this.reads = Arrays.copyOf(reads, reads.length);
            this.payloads = new byte[payloads.length][];
            for (int i = 0; i < payloads.length; i++) {
                this.payloads[i] = payloads[i].getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
        }

        static ScriptedSocketChannel alwaysZeroWrites() {
            ScriptedSocketChannel channel = new ScriptedSocketChannel(new int[] {0}, new String[] {""});
            channel.alwaysZeroWrites = true;
            return channel;
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            if (!open) {
                throw new ClosedChannelException();
            }
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
            if (alwaysZeroWrites) {
                return 0;
            }
            int len = src.remaining();
            src.position(src.limit());
            return len;
        }

        @Override
        public long write(ByteBuffer[] srcs, int offset, int length) {
            long total = 0;
            int zeroSpins = 0;
            for (int i = offset; i < offset + length; i++) {
                while (srcs[i].hasRemaining()) {
                    int written = write(srcs[i]);
                    if (written == 0) {
                        zeroSpins++;
                        if (zeroSpins >= MAX_ZERO_WRITE_SPINS) {
                            return total;
                        }
                        continue;
                    }
                    total += written;
                }
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
            open = false;
        }

        @Override
        protected void implConfigureBlocking(boolean block) {
            // no-op
        }
    }

    /**
     * Lets acceptOp() tests force client setup and close failures without
     * depending on real accepted sockets.
     */
    private static class SetupAwareSocketChannel extends SocketChannel {
        private final IOException configureFailure;
        private final IOException closeFailure;
        private int closeAttempts;

        private SetupAwareSocketChannel(IOException configureFailure, IOException closeFailure) {
            super(SelectorProvider.provider());
            this.configureFailure = configureFailure;
            this.closeFailure = closeFailure;
        }

        static SetupAwareSocketChannel failOnConfigure() {
            return new SetupAwareSocketChannel(new IOException("configure failed"), null);
        }

        static SetupAwareSocketChannel failOnConfigureAndClose() {
            return new SetupAwareSocketChannel(new IOException("configure failed"), new IOException("close failed"));
        }

        private SetupAwareSocketChannel() {
            this(null, null);
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
        public int write(ByteBuffer src) {
            int written = src.remaining();
            src.position(src.limit());
            return written;
        }

        @Override
        public long write(ByteBuffer[] srcs, int offset, int length) {
            long total = 0;
            for (int index = offset; index < offset + length; index++) {
                total += write(srcs[index]);
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
            closeAttempts++;
            if (closeFailure != null) {
                throw closeFailure;
            }
        }

        @Override
        protected void implConfigureBlocking(boolean block) throws IOException {
            if (configureFailure != null) {
                throw configureFailure;
            }
        }
    }

    /**
     * Feeds raw byte chunks directly so UTF-8 framing tests can split one code
     * point across reads without converting those bytes into broken Strings.
     */
    private static class ScriptedByteSocketChannel extends SocketChannel {
        private final byte[][] chunks;
        private int chunkIndex;

        private ScriptedByteSocketChannel(byte[]... chunks) {
            super(SelectorProvider.provider());
            this.chunks = chunks.clone();
        }

        @Override
        public int read(ByteBuffer dst) {
            if (chunkIndex >= chunks.length) {
                return 0;
            }

            byte[] chunk = chunks[chunkIndex++];
            dst.put(chunk);
            return chunk.length;
        }

        @Override
        public long read(ByteBuffer[] dsts, int offset, int length) {
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
            // no-op
        }

        @Override
        protected void implConfigureBlocking(boolean block) {
            // no-op
        }
    }

    /**
     * Lets the oversized-request tests hit the defensive close-error branch
     * without relying on a real socket that fails during close().
     */
    private static final class CloseFailingScriptedByteSocketChannel extends ScriptedByteSocketChannel {
        private int closeAttempts;

        private CloseFailingScriptedByteSocketChannel(byte[]... chunks) {
            super(chunks);
        }

        @Override
        protected void implCloseSelectableChannel() throws IOException {
            closeAttempts++;
            throw new IOException("close failed");
        }
    }

    private static final class RecordingScriptedSocketChannel extends ScriptedSocketChannel {
        private final ByteBuffer writes = ByteBuffer.allocate(4096);

        private RecordingScriptedSocketChannel(int[] reads, String[] payloads) {
            super(reads, payloads);
        }

        String writtenText() {
            ByteBuffer copy = writes.duplicate();
            copy.flip();
            byte[] bytes = new byte[copy.remaining()];
            copy.get(bytes);
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }

        @Override
        public int write(ByteBuffer src) {
            int len = src.remaining();
            writes.put(src);
            return len;
        }
    }

    private static final class ThrowOnCloseSocketChannel extends ScriptedSocketChannel {
        private ThrowOnCloseSocketChannel() {
            super(new int[] {0}, new String[] {""});
        }

        @Override
        protected void implCloseSelectableChannel() throws IOException {
            throw new IOException("close failed");
        }
    }

}
