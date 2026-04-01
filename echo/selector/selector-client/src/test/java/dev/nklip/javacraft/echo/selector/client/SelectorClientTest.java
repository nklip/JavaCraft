package dev.nklip.javacraft.echo.selector.client;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.NonNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class SelectorClientTest {

    private static final int PORT = 19077;
    private static ServerSocket testServer;
    private InputStream originalIn;

    @BeforeAll
    static void startTestServer() throws IOException {
        testServer = new ServerSocket(PORT);
        Thread serverThread = new Thread(() -> {
            while (!testServer.isClosed()) {
                try {
                    Socket client = testServer.accept();
                    Thread handler = new Thread(() -> handleClient(client));
                    handler.setDaemon(true);
                    handler.start();
                } catch (IOException e) {
                    break;
                }
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();
    }

    private static void handleClient(Socket client) {
        try {
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            byte[] buf = new byte[1024];
            int len;
            while ((len = in.read(buf)) != -1) {
                String msg = new String(buf, 0, len).trim();
                if (msg.isEmpty()) {
                    continue;
                }
                String response;
                if ("bye".equalsIgnoreCase(msg)) {
                    response = "Have a good day!";
                    out.write((response + "\r\n").getBytes());
                    out.flush();
                    client.close();
                    return;
                } else {
                    response = "Did you say '" + msg + "'?";
                    out.write((response + "\r\n").getBytes());
                    out.flush();
                }
            }
        } catch (IOException e) {
            // client disconnected
        }
    }

    @BeforeEach
    void saveStdin() {
        originalIn = System.in;
    }

    @AfterEach
    void restoreStdin() {
        System.setIn(originalIn);
    }

    @AfterAll
    static void stopTestServer() throws IOException {
        if (testServer != null) {
            testServer.close();
        }
    }

    @Test
    void testConnectToServer() throws IOException {
        SelectorClient client = new SelectorClient("localhost", PORT);
        try {
            client.connectToServer();
            // If no exception thrown, connection succeeded
        } finally {
            client.close();
        }
    }

    @Test
    void testConnectToServerThrowsWhenServerUnavailable() {
        int deadPort;
        try (ServerSocket temp = new ServerSocket(0)) {
            deadPort = temp.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        SelectorClient client = new SelectorClient("localhost", deadPort);
        try {
            Assertions.assertThrows(IOException.class, client::connectToServer,
                    "connectToServer() should fail fast when nothing is listening on the target port");
        } finally {
            client.close();
        }
    }

    @Test
    void testConstructorDoesNotStartListenerBeforeConnect() {
        RecordingNetworkManager networkManager = new RecordingNetworkManager();
        RecordingExecutorService executor = new RecordingExecutorService();

        new SelectorClient("localhost", 8077, networkManager, executor);

        Assertions.assertEquals(0, executor.submittedTaskCount);
    }

    @Test
    void testConnectToServerStartsListenerAfterSuccessfulOpen() throws IOException {
        RecordingNetworkManager networkManager = new RecordingNetworkManager();
        RecordingExecutorService executor = new RecordingExecutorService();
        SelectorClient client = new SelectorClient("localhost", 8077, networkManager, executor);

        client.connectToServer();

        Assertions.assertEquals(1, networkManager.openAttempts);
        Assertions.assertEquals(1, executor.submittedTaskCount);
    }

    @Test
    void testConnectFailureDoesNotStartListenerAndSuccessfulRetryDoes() throws IOException {
        RecordingNetworkManager networkManager = new RecordingNetworkManager();
        networkManager.failNextOpen(new IOException("connection refused"));
        RecordingExecutorService executor = new RecordingExecutorService();
        SelectorClient client = new SelectorClient("localhost", 8077, networkManager, executor);

        Assertions.assertThrows(IOException.class, client::connectToServer);
        Assertions.assertEquals(0, executor.submittedTaskCount);

        client.connectToServer();

        Assertions.assertEquals(2, networkManager.openAttempts);
        Assertions.assertEquals(1, executor.submittedTaskCount);
    }

    @Test
    void testSendAndReceiveEcho() throws IOException, InterruptedException {
        SelectorClient client = new SelectorClient("localhost", PORT);
        try {
            client.connectToServer();
            Thread.sleep(200);

            client.sendMessage("test message");
            Thread.sleep(200);
            String response = client.readMessage();
            Assertions.assertEquals("Did you say 'test message'?", response);
        } finally {
            client.close();
        }
    }

    @Test
    void testSendByeReceivesGoodbye() throws IOException, InterruptedException {
        SelectorClient client = new SelectorClient("localhost", PORT);
        try {
            client.connectToServer();
            Thread.sleep(200);

            client.sendMessage("bye");
            Thread.sleep(200);
            String response = client.readMessage();
            Assertions.assertEquals("Have a good day!", response);
        } finally {
            client.close();
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testRunWithInputThenEof() {
        System.setIn(new ByteArrayInputStream("hello\n".getBytes()));
        SelectorClient client = new SelectorClient("localhost", PORT);
        Assertions.assertDoesNotThrow(client::run);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testRunWithEmptyStdin() {
        // Empty stdin → readLine() returns null immediately → breaks out of loop
        System.setIn(new ByteArrayInputStream(new byte[0]));
        SelectorClient client = new SelectorClient("localhost", PORT);
        Assertions.assertDoesNotThrow(client::run);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testRunWithByeCommand() {
        System.setIn(new ByteArrayInputStream("bye\n".getBytes()));
        SelectorClient client = new SelectorClient("localhost", PORT);
        Assertions.assertDoesNotThrow(client::run);
    }

    @Test
    void testRunPreservesMeaningfulWhitespaceInConsoleInput() {
        // Verifies that run() forwards console lines exactly as typed, including leading, trailing, and all-space input.
        System.setIn(new ByteArrayInputStream("  padded message  \n   \n".getBytes()));
        RecordingNetworkManager networkManager = new RecordingNetworkManager();
        CapturingMessageSender sender = new CapturingMessageSender();
        networkManager.setSelectorMessageSender(sender);
        RecordingExecutorService executor = new RecordingExecutorService();
        SelectorClient client = new SelectorClient("localhost", PORT, networkManager, executor);

        Assertions.assertDoesNotThrow(client::run);

        Assertions.assertEquals(1, networkManager.openAttempts);
        Assertions.assertEquals(List.of("  padded message  ", "   "), sender.commands);
    }

    @Test
    void testRunStopsOnlyOnExactByeCommand() {
        // Verifies that only an exact bye line ends the loop, while whitespace-padded bye is sent as normal input.
        System.setIn(new ByteArrayInputStream(" bye \nbye\nignored\n".getBytes()));
        RecordingNetworkManager networkManager = new RecordingNetworkManager();
        CapturingMessageSender sender = new CapturingMessageSender();
        networkManager.setSelectorMessageSender(sender);
        RecordingExecutorService executor = new RecordingExecutorService();
        SelectorClient client = new SelectorClient("localhost", PORT, networkManager, executor);

        Assertions.assertDoesNotThrow(client::run);

        Assertions.assertEquals(List.of(" bye ", "bye"), sender.commands);
    }

    @Test
    void testRunWaitsForByeResponseBeforeClosing() {
        // Verifies that the CLI waits for one outstanding goodbye reply instead of closing immediately.
        System.setIn(new ByteArrayInputStream("bye\n".getBytes()));
        RecordingNetworkManager networkManager = new RecordingNetworkManager();
        CapturingMessageSender sender = new CapturingMessageSender();
        networkManager.setSelectorMessageSender(sender);
        RecordingExecutorService executor = new RecordingExecutorService();
        SelectorClient client = new SelectorClient("localhost", PORT, networkManager, executor);

        Assertions.assertDoesNotThrow(client::run);

        Assertions.assertEquals(List.of("bye"), sender.commands);
        Assertions.assertEquals(Collections.singletonList(1), networkManager.awaitedTargetCounts);
        Assertions.assertEquals(0, networkManager.getMessageCalls);
        Assertions.assertEquals(List.of("openSocket", "awaitReceivedMessageCount", "closeSocket"),
                networkManager.lifecycleEvents);
    }

    @Test
    void testRunWaitsForPendingResponsesBeforeClosingOnEof() {
        // Verifies that EOF waits only once for the full outstanding reply count instead of polling per command.
        System.setIn(new ByteArrayInputStream("first\nsecond\n".getBytes()));
        RecordingNetworkManager networkManager = new RecordingNetworkManager();
        CapturingMessageSender sender = new CapturingMessageSender();
        networkManager.setSelectorMessageSender(sender);
        RecordingExecutorService executor = new RecordingExecutorService();
        SelectorClient client = new SelectorClient("localhost", PORT, networkManager, executor);

        Assertions.assertDoesNotThrow(client::run);

        Assertions.assertEquals(List.of("first", "second"), sender.commands);
        Assertions.assertEquals(Collections.singletonList(2), networkManager.awaitedTargetCounts);
        Assertions.assertEquals(0, networkManager.getMessageCalls);
        Assertions.assertEquals(List.of("openSocket", "awaitReceivedMessageCount", "closeSocket"),
                networkManager.lifecycleEvents);
    }

    @Test
    void testRunWithEmptyStdinDoesNotWaitForResponsesBeforeClosing() {
        // Verifies that the final-response wait is skipped when no command was ever sent.
        System.setIn(new ByteArrayInputStream(new byte[0]));
        RecordingNetworkManager networkManager = new RecordingNetworkManager();
        CapturingMessageSender sender = new CapturingMessageSender();
        networkManager.setSelectorMessageSender(sender);
        RecordingExecutorService executor = new RecordingExecutorService();
        SelectorClient client = new SelectorClient("localhost", PORT, networkManager, executor);

        Assertions.assertDoesNotThrow(client::run);

        Assertions.assertTrue(sender.commands.isEmpty());
        Assertions.assertEquals(0, networkManager.getMessageCalls);
        Assertions.assertTrue(networkManager.awaitedTargetCounts.isEmpty());
        Assertions.assertEquals(List.of("openSocket", "closeSocket"), networkManager.lifecycleEvents);
    }

    @Test
    void testRunDoesNotWaitForResponsesAlreadyReceivedDuringSession() {
        // Verifies that shutdown skips waiting when every sent command already has a received reply count.
        System.setIn(new ByteArrayInputStream("one\ntwo\nthree\n".getBytes()));
        RecordingNetworkManager networkManager = new RecordingNetworkManager();
        CapturingMessageSender sender = new CapturingMessageSender(networkManager::recordReceivedResponse);
        networkManager.setSelectorMessageSender(sender);
        RecordingExecutorService executor = new RecordingExecutorService();
        SelectorClient client = new SelectorClient("localhost", PORT, networkManager, executor);

        Assertions.assertDoesNotThrow(client::run);

        Assertions.assertEquals(List.of("one", "two", "three"), sender.commands);
        Assertions.assertTrue(networkManager.awaitedTargetCounts.isEmpty());
        Assertions.assertEquals(0, networkManager.getMessageCalls);
        Assertions.assertEquals(List.of("openSocket", "closeSocket"), networkManager.lifecycleEvents);
    }

    @Test
    void testReadMessageReturnsNullWhenNoMessage() throws IOException {
        SelectorClient client = new SelectorClient("localhost", PORT);
        try {
            client.connectToServer();
            // No messages sent → queue is empty → returns null after poll timeout
            String msg = client.readMessage();
            Assertions.assertNull(msg);
        } finally {
            client.close();
        }
    }

    @Test
    void testMultipleRoundTrips() throws IOException, InterruptedException {
        SelectorClient client = new SelectorClient("localhost", PORT);
        try {
            client.connectToServer();
            Thread.sleep(200);

            client.sendMessage("first");
            Thread.sleep(200);
            String r1 = client.readMessage();
            Assertions.assertEquals("Did you say 'first'?", r1);

            client.sendMessage("second");
            Thread.sleep(200);
            String r2 = client.readMessage();
            Assertions.assertEquals("Did you say 'second'?", r2);
        } finally {
            client.close();
        }
    }

    @Test
    void testCloseIsIdempotent() throws IOException {
        SelectorClient client = new SelectorClient("localhost", PORT);
        client.connectToServer();

        // Calling close() twice should not throw
        Assertions.assertDoesNotThrow(() -> {
            client.close();
            client.close();
        });
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testRunWithMultipleMessages() {
        // Two messages then EOF — exercises the run() while loop multiple iterations
        System.setIn(new ByteArrayInputStream("first\nsecond\n".getBytes()));
        SelectorClient client = new SelectorClient("localhost", PORT);
        Assertions.assertDoesNotThrow(client::run);
    }

    @Test
    void testCloseWithoutConnect() {
        // Client created but never connected — close should not throw
        SelectorClient client = new SelectorClient("localhost", PORT);
        Assertions.assertDoesNotThrow(client::close);
    }

    @Test
    void testSendAndReadMultipleMessagesSameClient() throws IOException, InterruptedException {
        SelectorClient client = new SelectorClient("localhost", PORT);
        try {
            client.connectToServer();
            Thread.sleep(200);

            // Send 3 messages and verify all 3 responses
            for (int i = 1; i <= 3; i++) {
                client.sendMessage("msg" + i);
                Thread.sleep(200);
                String response = client.readMessage();
                Assertions.assertEquals("Did you say 'msg" + i + "'?", response);
            }
        } finally {
            client.close();
        }
    }

    // ── Tests for defensive catch blocks ─────────────────────────────

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testRunInnerCatchIOException() {
        // Covers run() inner catch(IOException) L75-77
        // Custom InputStream that throws IOException once, then returns EOF
        InputStream throwOnceStream = new InputStream() {
            private boolean thrown = false;
            @Override
            public int read() throws IOException {
                if (!thrown) {
                    thrown = true;
                    throw new IOException("stdin read failed");
                }
                return -1; // EOF to break the while loop
            }
        };
        System.setIn(throwOnceStream);

        SelectorClient client = new SelectorClient("localhost", PORT);
        // run() should catch the IOException, then readLine() returns null → breaks
        Assertions.assertDoesNotThrow(client::run);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testRunOuterCatchException() {
        System.setIn(new ByteArrayInputStream("hello\n".getBytes()));
        // Inject a network manager that fails during connectToServer() so
        // run() exercises its outer catch(Exception) path.
        SelectorClient client = new SelectorClient("localhost", PORT);
        try {
            java.lang.reflect.Field mgrField = SelectorClient.class.getDeclaredField("selectorNetworkManager");
            mgrField.setAccessible(true);
            SelectorNetworkManager mockMgr = mock(SelectorNetworkManager.class);
            doThrow(new IOException("connection refused")).when(mockMgr).openSocket(anyString(), anyInt());
            mgrField.set(client, mockMgr);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // run() calls connectToServer() which calls mockMgr.openSocket() → throws
        // The outer catch(Exception) should catch it
        Assertions.assertDoesNotThrow(client::run);
    }

    private static final class RecordingNetworkManager extends SelectorNetworkManager {
        private final Deque<IOException> failures = new ArrayDeque<>();
        private final Deque<String> queuedResponses = new ArrayDeque<>();
        private final List<String> lifecycleEvents = new ArrayList<>();
        private final List<Integer> awaitedTargetCounts = new ArrayList<>();
        private int openAttempts;
        private int getMessageCalls;
        private int receivedResponseCount;

        private void failNextOpen(IOException failure) {
            failures.addLast(failure);
        }

        /**
         * Advances the observed listener count so run() tests can model replies
         * that already arrived before shutdown begins.
         */
        private void recordReceivedResponse() {
            receivedResponseCount++;
        }

        @Override
        public void openSocket(String serverHost, int serverPort) throws IOException {
            lifecycleEvents.add("openSocket");
            openAttempts++;
            IOException failure = failures.pollFirst();
            if (failure != null) {
                throw failure;
            }
        }

        @Override
        public String getMessage() {
            lifecycleEvents.add("getMessage");
            getMessageCalls++;
            return queuedResponses.pollFirst();
        }

        @Override
        public int getReceivedMessageCount() {
            return receivedResponseCount;
        }

        @Override
        public boolean awaitReceivedMessageCount(int targetCount, long timeoutMs) {
            lifecycleEvents.add("awaitReceivedMessageCount");
            awaitedTargetCounts.add(targetCount);
            return receivedResponseCount >= targetCount;
        }

        @Override
        public void closeSocket() {
            lifecycleEvents.add("closeSocket");
        }
    }

    /**
     * Captures exactly what run() forwards to sendMessage() so console
     * whitespace handling can be asserted without real sockets.
     */
    private static final class CapturingMessageSender extends SelectorMessageSender {
        private final List<String> commands = new ArrayList<>();
        private final Runnable afterSend;

        private CapturingMessageSender() {
            this(() -> { });
        }

        private CapturingMessageSender(Runnable afterSend) {
            this.afterSend = afterSend;
        }

        @Override
        public void send(String command) {
            commands.add(command);
            afterSend.run();
        }
    }

    private static final class RecordingExecutorService extends AbstractExecutorService {
        private boolean shutdown;
        private int submittedTaskCount;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public @NonNull List<Runnable> shutdownNow() {
            shutdown = true;
            return Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, @NonNull TimeUnit unit) {
            return shutdown;
        }

        @Override
        public void execute(@NonNull Runnable command) {
            submittedTaskCount++;
        }
    }

}
