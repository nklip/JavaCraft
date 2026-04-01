package dev.nklip.javacraft.echo.selector.client;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.nio.channels.Selector;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class SelectorNetworkManagerTest {

    private SelectorNetworkManager manager;

    @BeforeEach
    void setUp() {
        manager = new SelectorNetworkManager();
    }

    // ── Message queue tests ──────────────────────────────────────────

    @Test
    void testAddAndGetMessage() {
        manager.addMessage("hello");
        String msg = manager.getMessage();
        Assertions.assertEquals("hello", msg);
    }

    @Test
    void testGetMessageReturnsNullWhenEmpty() {
        String msg = manager.getMessage();
        Assertions.assertNull(msg);
    }

    @Test
    void testMessagesReturnedInFifoOrder() {
        manager.addMessage("first");
        manager.addMessage("second");
        manager.addMessage("third");

        Assertions.assertEquals("first", manager.getMessage());
        Assertions.assertEquals("second", manager.getMessage());
        Assertions.assertEquals("third", manager.getMessage());
        Assertions.assertNull(manager.getMessage());
    }

    @Test
    void testAddMessageEvictsOldestOnOverflow() {
        // Queue capacity is 10
        for (int i = 0; i < 10; i++) {
            manager.addMessage("msg-" + i);
        }
        // Adding 11th should evict msg-0
        manager.addMessage("msg-10");

        String first = manager.getMessage();
        Assertions.assertEquals("msg-1", first);
    }

    @Test
    void testAddMessageMultipleOverflows() {
        // Add 15 messages to a capacity-10 queue → first 5 should be evicted
        for (int i = 0; i < 15; i++) {
            manager.addMessage("msg-" + i);
        }

        // Queue should contain msg-5 through msg-14
        for (int i = 5; i < 15; i++) {
            Assertions.assertEquals("msg-" + i, manager.getMessage());
        }
        Assertions.assertNull(manager.getMessage());
    }

    @Test
    void testGetMessagePollTimeout() {
        long start = System.currentTimeMillis();
        String result = manager.getMessage();
        long elapsed = System.currentTimeMillis() - start;

        Assertions.assertNull(result);
        // POLL_TIMEOUT_MS is 100 — getMessage should wait ~100ms before returning null
        Assertions.assertTrue(elapsed >= 80, "Expected poll to wait ~100ms but took " + elapsed + "ms");
    }

    @Test
    void testAwaitReceivedMessageCountReturnsTrueImmediatelyWhenTargetAlreadyReached() {
        // Verifies the fast path when the listener has already observed enough replies before shutdown starts waiting.
        manager.addMessage("hello");

        Assertions.assertTrue(manager.awaitReceivedMessageCount(1, 100));
    }

    @Test
    void testAwaitReceivedMessageCountReturnsFalseWhenTimeoutExpires() {
        // Verifies the timeout branch when no additional reply arrives before the deadline.
        Assertions.assertFalse(manager.awaitReceivedMessageCount(1, 0));
    }

    @Test
    void testAwaitReceivedMessageCountReturnsFalseWhenInterruptedWhileWaiting() {
        // Verifies that shutdown waiting stops and restores the interrupt flag when the waiting thread is interrupted.
        Thread.currentThread().interrupt();

        Assertions.assertFalse(manager.awaitReceivedMessageCount(1, 100));
        Assertions.assertTrue(Thread.interrupted());
    }

    // ── Socket open / close tests ────────────────────────────────────

    @Test
    void testOpenAndCloseSocket() throws IOException {
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            acceptAsync(server);

            manager.openSocket("localhost", port);

            SocketChannel channel = manager.getSocketChannel();
            Assertions.assertNotNull(channel);
            Assertions.assertTrue(channel.isOpen());

            Selector selector = manager.getSelector();
            Assertions.assertNotNull(selector);
            Assertions.assertTrue(selector.isOpen());

            manager.closeSocket();
        }
    }

    @Test
    void testOpenSocketIsIdempotent() throws IOException {
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            acceptAsync(server);

            manager.openSocket("localhost", port);
            SocketChannel first = manager.getSocketChannel();

            // Second call should be no-op (client already set)
            manager.openSocket("localhost", port);
            SocketChannel second = manager.getSocketChannel();

            Assertions.assertSame(first, second);
            manager.closeSocket();
        }
    }

    @Test
    void testOpenSocketThrowsWhenServerUnavailable() {
        int deadPort;
        try (ServerSocket temp = new ServerSocket(0)) {
            deadPort = temp.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        Assertions.assertThrows(IOException.class, () -> manager.openSocket("localhost", deadPort),
                "openSocket() should surface connection failures immediately");
    }

    @Test
    void testOpenSocketCanRecoverAfterFailedConnect() throws IOException {
        int deadPort;
        try (ServerSocket temp = new ServerSocket(0)) {
            deadPort = temp.getLocalPort();
        }

        Assertions.assertThrows(IOException.class, () -> manager.openSocket("localhost", deadPort));

        try (ServerSocket server = new ServerSocket(0)) {
            int livePort = server.getLocalPort();
            acceptAsync(server);

            manager.openSocket("localhost", livePort);

            SocketChannel channel = manager.getSocketChannel();
            Selector selector = manager.getSelector();

            Assertions.assertNotNull(channel);
            Assertions.assertTrue(channel.isOpen());
            Assertions.assertNotNull(selector);
            Assertions.assertTrue(selector.isOpen());
        } finally {
            manager.closeSocket();
        }
    }

    @Test
    void testOpenSocketAddsSuppressedCloseFailureWhenCleanupFails() {
        SocketChannel channel = new ScriptedSocketChannel(
                new IOException("connect failed"),
                new IOException("channel close failed"));
        SelectorNetworkManager failingManager = new TestableNetworkManager(channel, null);

        IOException thrown = Assertions.assertThrows(
                IOException.class,
                () -> failingManager.openSocket("localhost", 8080));

        Assertions.assertEquals("connect failed", thrown.getMessage());
        Assertions.assertEquals(1, thrown.getSuppressed().length);
        Assertions.assertEquals("channel close failed", thrown.getSuppressed()[0].getMessage());
    }

    @Test
    void testOpenSocketConfiguresNonBlockingBeforeConnect() {
        ScriptedSocketChannel channel = new ScriptedSocketChannel(
                new IOException("connect order verified"),
                null,
                true,
                0);
        SelectorNetworkManager orderCheckingManager = new TestableNetworkManager(channel, null);

        IOException thrown = Assertions.assertThrows(
                IOException.class,
                () -> orderCheckingManager.openSocket("localhost", 8080));

        Assertions.assertEquals("connect order verified", thrown.getMessage());
        Assertions.assertEquals(1, channel.getConnectCalls());
    }

    @Test
    void testConnectNonBlockingReturnsImmediatelyWhenConnectCompletes() throws Exception {
        try (ScriptedSocketChannel channel = new ScriptedSocketChannel(null, null)) {
            channel.configureBlocking(false);

            Assertions.assertDoesNotThrow(() -> invokeConnectNonBlocking(channel));

            Assertions.assertEquals(1, channel.getConnectCalls());
            Assertions.assertEquals(0, channel.getFinishConnectCalls());
        }
    }

    @Test
    void testConnectNonBlockingWaitsForPendingConnectionToFinish() throws Exception {
        try (ScriptedSocketChannel channel = new ScriptedSocketChannel(null, null, false, 1)) {
            channel.configureBlocking(false);

            Assertions.assertDoesNotThrow(() -> invokeConnectNonBlocking(channel));

            Assertions.assertEquals(1, channel.getConnectCalls());
            Assertions.assertEquals(2, channel.getFinishConnectCalls());
        }
    }

    @Test
    void testConnectNonBlockingTimesOutWhenConnectionStaysPending() throws Exception {
        try (ScriptedSocketChannel channel = new ScriptedSocketChannel(null, null, false, Integer.MAX_VALUE)) {
            channel.configureBlocking(false);

            IOException thrown = Assertions.assertThrows(IOException.class, () -> invokeConnectNonBlocking(channel));

            Assertions.assertEquals("Timed out waiting for socket connection", thrown.getMessage());
            Assertions.assertTrue(channel.getFinishConnectCalls() > 0);
        }
    }

    @Test
    void testConcurrentOpenSocketUsesInnerGuardAndPublishesConnectionOnce() throws Exception {
        assumeSocketBindingAvailable();
        try (ServerSocket server = new ServerSocket(0);
             ExecutorService executor = Executors.newFixedThreadPool(2)) {
            int port = server.getLocalPort();
            acceptAsync(server);
            SelectorMessageSender sender = mock(SelectorMessageSender.class);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            SelectorNetworkManager lockedManager = manager;
            manager.setSelectorMessageSender(sender);

            Future<?> first;
            Future<?> second;
            synchronized (lockedManager) {
                first = submitOpenSocket(lockedManager, executor, ready, go, port);
                second = submitOpenSocket(lockedManager, executor, ready, go, port);

                Assertions.assertTrue(ready.await(1, TimeUnit.SECONDS));
                go.countDown();
            }

            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);

            verify(sender, times(1)).setKey(any(SelectionKey.class), any(Selector.class));
            Assertions.assertNotNull(manager.getSocketChannel());
            Assertions.assertNotNull(manager.getSelector());
            manager.closeSocket();
        }
    }

    @Test
    void testCloseSocketWhenNotOpened() {
        // Should not throw when nothing was opened
        Assertions.assertDoesNotThrow(() -> manager.closeSocket());
    }

    @Test
    void testGetSelectorFastPathWhenAlreadyOpen() throws IOException {
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            acceptAsync(server);

            manager.openSocket("localhost", port);

            // First call — selector is set, enters fast path (no wait)
            Selector sel1 = manager.getSelector();
            // Second call — still fast path (if selector != null check in getSelector())
            Selector sel2 = manager.getSelector();

            Assertions.assertSame(sel1, sel2);
            manager.closeSocket();
        }
    }

    @Test
    void testOpenSocketPublishesReadyKeyToSenderWhenSenderPresent() throws IOException {
        assumeSocketBindingAvailable();
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            acceptAsync(server);
            RecordingMessageSender sender = new RecordingMessageSender();
            manager.setSelectorMessageSender(sender);

            manager.openSocket("localhost", port);

            Assertions.assertEquals(1, sender.setKeyCalls);
            Assertions.assertNotNull(sender.lastKey);
            Assertions.assertSame(manager.getSelector(), sender.lastSelector);
            Assertions.assertSame(manager.getSocketChannel(), sender.lastKey.channel());
            manager.closeSocket();
        }
    }

    @Test
    void testGetSocketChannelFastPathWhenAlreadyOpen() throws IOException {
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            acceptAsync(server);

            manager.openSocket("localhost", port);

            // First call — client is set, enters fast path (no wait)
            SocketChannel ch1 = manager.getSocketChannel();
            // Second call — still fast path
            SocketChannel ch2 = manager.getSocketChannel();

            Assertions.assertSame(ch1, ch2);
            manager.closeSocket();
        }
    }

    @Test
    void testGetSelectorThrowsAfterTimeoutWhenSocketNeverOpens() {
        RuntimeException thrown = Assertions.assertThrows(RuntimeException.class, () -> manager.getSelector());

        Assertions.assertEquals("Timed out waiting for selector", thrown.getMessage());
    }

    @Test
    void testGetSocketChannelThrowsAfterTimeoutWhenSocketNeverOpens() {
        RuntimeException thrown = Assertions.assertThrows(RuntimeException.class, () -> manager.getSocketChannel());

        Assertions.assertEquals("Timed out waiting for socket channel", thrown.getMessage());
    }

    // ── Blocking wait/notify tests ───────────────────────────────────

    @Test
    void testGetSelectorBlocksUntilSocketOpened() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            acceptAsync(server);

            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<Selector> future = executor.submit(() -> manager.getSelector());

                // Give the thread time to enter wait()
                Thread.sleep(100);
                Assertions.assertFalse(future.isDone(), "getSelector() should block until openSocket() is called");

                // Open socket — this calls notifyAll() and should unblock getSelector()
                manager.openSocket("localhost", port);

                Selector sel = future.get(2, TimeUnit.SECONDS);
                Assertions.assertNotNull(sel);
                Assertions.assertTrue(sel.isOpen());
            } finally {
                manager.closeSocket();
                executor.shutdownNow();
            }
        }
    }

    @Test
    void testGetSocketChannelBlocksUntilSocketOpened() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            acceptAsync(server);

            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<SocketChannel> future = executor.submit(() -> manager.getSocketChannel());

                // Give the thread time to enter wait()
                Thread.sleep(100);
                Assertions.assertFalse(future.isDone(), "getSocketChannel() should block until openSocket() is called");

                // Open socket — this calls notifyAll() and should unblock getSocketChannel()
                manager.openSocket("localhost", port);

                SocketChannel ch = future.get(2, TimeUnit.SECONDS);
                Assertions.assertNotNull(ch);
                Assertions.assertTrue(ch.isOpen());
            } finally {
                manager.closeSocket();
                executor.shutdownNow();
            }
        }
    }

    // ── Interrupt handling tests ─────────────────────────────────────

    @Test
    void testGetSelectorReturnsNullOnInterrupt() {
        Thread.currentThread().interrupt();

        Assertions.assertNull(manager.getSelector());

        // Clear interrupt flag (getSelector re-sets it before throwing)
        Assertions.assertTrue(Thread.interrupted());
    }

    @Test
    void testGetSocketChannelThrowsRuntimeExceptionOnInterrupt() {
        Thread.currentThread().interrupt();

        Assertions.assertThrows(RuntimeException.class, () -> manager.getSocketChannel());

        // Clear interrupt flag
        Assertions.assertTrue(Thread.interrupted());
    }

    @Test
    void testGetMessageReturnsNullOnInterrupt() {
        Thread.currentThread().interrupt();

        String result = manager.getMessage();
        Assertions.assertNull(result);

        // Clear interrupt flag (getMessage re-sets it)
        Assertions.assertTrue(Thread.interrupted());
    }

    @Test
    void testCloseSocketTwiceIsNoOp() throws IOException {
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            acceptAsync(server);

            manager.openSocket("localhost", port);

            // First close
            Assertions.assertDoesNotThrow(() -> manager.closeSocket());
            // Second close — should be no-op (client is already null)
            Assertions.assertDoesNotThrow(() -> manager.closeSocket());
        }
    }

    // ── Setter / getter test ─────────────────────────────────────────

    @Test
    void testSetAndGetSelectorMessageSender() {
        Assertions.assertNull(manager.getSelectorMessageSender());

        SelectorMessageSender sender = new SelectorMessageSender();
        manager.setSelectorMessageSender(sender);

        Assertions.assertSame(sender, manager.getSelectorMessageSender());
    }

    // ── Mockito-based test for closeSocket() catch(IOException) ─────

    @Test
    void testCloseSocketCatchesIOExceptionFromSelectorClose() throws Exception {
        // Covers closeSocket() catch(IOException) L112-113
        Selector mockSelector = mock(Selector.class);
        ScriptedSocketChannel client = new ScriptedSocketChannel(null, null);
        doThrow(new IOException("selector close failed")).when(mockSelector).close();

        // Use reflection to inject mock objects into private volatile fields
        Field clientField = SelectorNetworkManager.class.getDeclaredField("client");
        clientField.setAccessible(true);
        clientField.set(manager, client);

        Field selectorField = SelectorNetworkManager.class.getDeclaredField("selector");
        selectorField.setAccessible(true);
        selectorField.set(manager, mockSelector);

        // closeSocket() should catch IOException and not propagate it
        Assertions.assertDoesNotThrow(() -> manager.closeSocket());
        Assertions.assertEquals(1, client.getCloseCalls());
    }

    @Test
    void testCloseSocketCatchesIOExceptionFromClientClose() throws Exception {
        // Covers closeSocket() catch(IOException) when client.close() throws
        Selector selector = Selector.open();
        ScriptedSocketChannel client = new ScriptedSocketChannel(null, new IOException("client close failed"));

        Field clientField = SelectorNetworkManager.class.getDeclaredField("client");
        clientField.setAccessible(true);
        clientField.set(manager, client);

        Field selectorField = SelectorNetworkManager.class.getDeclaredField("selector");
        selectorField.setAccessible(true);
        selectorField.set(manager, selector);

        Assertions.assertDoesNotThrow(() -> manager.closeSocket());
        Assertions.assertEquals(1, client.getCloseCalls());
    }

    @Test
    void testCloseSocketStillAttemptsClientCloseWhenSelectorCloseFails() throws Exception {
        Selector mockSelector = mock(Selector.class);
        ScriptedSocketChannel client = new ScriptedSocketChannel(null, new IOException("client close failed"));
        doThrow(new IOException("selector close failed")).when(mockSelector).close();

        Field clientField = SelectorNetworkManager.class.getDeclaredField("client");
        clientField.setAccessible(true);
        clientField.set(manager, client);

        Field selectorField = SelectorNetworkManager.class.getDeclaredField("selector");
        selectorField.setAccessible(true);
        selectorField.set(manager, mockSelector);

        Assertions.assertDoesNotThrow(() -> manager.closeSocket());
        verify(mockSelector).close();
        Assertions.assertEquals(1, client.getCloseCalls());
    }

    @Test
    void testCloseSocketClearsSenderKeyWhenSenderPresent() throws Exception {
        assumeSocketBindingAvailable();
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            acceptAsync(server);
            SelectorMessageSender sender = mock(SelectorMessageSender.class);
            ArgumentCaptor<SelectionKey> keyCaptor = ArgumentCaptor.forClass(SelectionKey.class);
            ArgumentCaptor<Selector> selectorCaptor = ArgumentCaptor.forClass(Selector.class);
            manager.setSelectorMessageSender(sender);
            manager.openSocket("localhost", port);

            manager.closeSocket();

            verify(sender, times(2)).setKey(keyCaptor.capture(), selectorCaptor.capture());
            Assertions.assertNotNull(keyCaptor.getAllValues().get(0));
            Assertions.assertNotNull(selectorCaptor.getAllValues().get(0));
            Assertions.assertNull(keyCaptor.getAllValues().get(1));
            Assertions.assertNull(selectorCaptor.getAllValues().get(1));
        }
    }

    @Test
    void testCloseSocketClearsPublishedSelectorAndClient() throws Exception {
        try (Selector selector = Selector.open();
             SocketChannel client = SocketChannel.open()) {
            setConnectionState(client, selector);

            manager.closeSocket();

            Assertions.assertNull(readField("client"));
            Assertions.assertNull(readField("selector"));
            Assertions.assertFalse(selector.isOpen());
            Assertions.assertFalse(client.isOpen());
        }
    }

    @Test
    void testCloseSocketSkipsInnerCloseWhenClientClearedBeforeLock() throws Exception {
        try (Selector selector = Selector.open();
             SocketChannel client = SocketChannel.open()) {
            SelectorMessageSender sender = mock(SelectorMessageSender.class);
            CountDownLatch started = new CountDownLatch(1);
            SelectorNetworkManager lockedManager = manager;
            Thread closer = new Thread(() -> {
                started.countDown();
                lockedManager.closeSocket();
            });
            manager.setSelectorMessageSender(sender);
            setConnectionState(client, selector);

            synchronized (lockedManager) {
                closer.start();
                Assertions.assertTrue(started.await(1, TimeUnit.SECONDS));
                waitForBlockedState(closer);
                setConnectionState(null, null);
            }

            closer.join(TimeUnit.SECONDS.toMillis(2));

            Assertions.assertFalse(closer.isAlive());
            verifyNoInteractions(sender);
            Assertions.assertNull(readField("client"));
            Assertions.assertNull(readField("selector"));
            Assertions.assertTrue(selector.isOpen());
            Assertions.assertTrue(client.isOpen());
        }
    }

    @Test
    void testConcurrentCloseSocketUsesInnerGuardAndClosesOnce() throws Exception {
        try (Selector selector = Selector.open();
             SocketChannel client = SocketChannel.open();
             ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            SelectorMessageSender sender = mock(SelectorMessageSender.class);
            SelectorNetworkManager lockedManager = manager;
            manager.setSelectorMessageSender(sender);
            setConnectionState(client, selector);

            Future<?> first;
            Future<?> second;
            synchronized (lockedManager) {
                first = submitCloseSocket(lockedManager, executor, ready, go);
                second = submitCloseSocket(lockedManager, executor, ready, go);

                Assertions.assertTrue(ready.await(1, TimeUnit.SECONDS));
                go.countDown();
            }

            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);

            verify(sender, times(1)).setKey(isNull(), isNull());
            Assertions.assertNull(readField("client"));
            Assertions.assertNull(readField("selector"));
            Assertions.assertFalse(selector.isOpen());
            Assertions.assertFalse(client.isOpen());
        }
    }

    @Test
    void testPublishReadyKeyDelegatesWhenSenderPresent() throws Exception {
        SelectorMessageSender sender = mock(SelectorMessageSender.class);
        Selector selector = mock(Selector.class);
        manager.setSelectorMessageSender(sender);

        invokePublishReadyKey(selector);

        verify(sender).setKey(isNull(), same(selector));
    }

    @Test
    void testCloseResourcesReturnsNullWhenNothingNeedsClosing() throws Exception {
        IOException result = invokeCloseResources(null, null);

        Assertions.assertNull(result);
    }

    @Test
    void testCloseResourcesSuppressesSecondCloseFailure() throws Exception {
        Selector selector = mock(Selector.class);
        ScriptedSocketChannel channel = new ScriptedSocketChannel(null, new IOException("channel close failed"));
        doThrow(new IOException("selector close failed")).when(selector).close();

        IOException result = invokeCloseResources(selector, channel);

        Assertions.assertNotNull(result);
        Assertions.assertEquals("selector close failed", result.getMessage());
        Assertions.assertEquals(1, result.getSuppressed().length);
        Assertions.assertEquals("channel close failed", result.getSuppressed()[0].getMessage());
        Assertions.assertEquals(1, channel.getCloseCalls());
    }

    // ── Helper ───────────────────────────────────────────────────────

    /**
     * Centralizes reflective connection-state setup so close tests do not
     * duplicate field access details.
     */
    private void setConnectionState(SocketChannel client, Selector selector) throws Exception {
        Field clientField = SelectorNetworkManager.class.getDeclaredField("client");
        clientField.setAccessible(true);
        clientField.set(manager, client);

        Field selectorField = SelectorNetworkManager.class.getDeclaredField("selector");
        selectorField.setAccessible(true);
        selectorField.set(manager, selector);
    }

    /**
     * Starts one openSocket() call behind a barrier so concurrency tests can
     * force both callers through the outer null-check before either gets the lock.
     */
    private Future<?> submitOpenSocket(
            SelectorNetworkManager networkManager,
            ExecutorService executor,
            CountDownLatch ready,
            CountDownLatch go,
            int port) {
        return executor.submit(() -> {
            ready.countDown();
            go.await();
            networkManager.openSocket("localhost", port);
            return null;
        });
    }

    /**
     * Starts one closeSocket() call behind a barrier so concurrency tests can
     * force both callers through the outer null-check before the first clears state.
     */
    private Future<?> submitCloseSocket(
            SelectorNetworkManager networkManager,
            ExecutorService executor,
            CountDownLatch ready,
            CountDownLatch go) {
        return executor.submit(() -> {
            ready.countDown();
            go.await();
            networkManager.closeSocket();
            return null;
        });
    }

    /**
     * Exercises the sender-publication helper directly so tests can cover the
     * branch without depending on a live socket connection.
     */
    private void invokePublishReadyKey(Selector selector) throws Exception {
        java.lang.reflect.Method publishReadyKey = SelectorNetworkManager.class
                .getDeclaredMethod("publishReadyKey", SelectionKey.class, Selector.class);
        publishReadyKey.setAccessible(true);
        publishReadyKey.invoke(manager, null, selector);
    }

    /**
     * Invokes the cleanup helper directly so failure-suppression behavior can
     * be verified with deterministic close-failing test doubles.
     */
    private IOException invokeCloseResources(Selector selector, SocketChannel channel) throws Exception {
        java.lang.reflect.Method closeResources = SelectorNetworkManager.class
                .getDeclaredMethod("closeResources", Selector.class, SocketChannel.class);
        closeResources.setAccessible(true);
        return (IOException) closeResources.invoke(manager, selector, channel);
    }

    /**
     * Invokes the connect helper directly so timeout and pending-connect
     * behavior can be verified without relying on flaky real network timing.
     */
    private void invokeConnectNonBlocking(SocketChannel channel) throws Exception {
        java.lang.reflect.Method connectNonBlocking = SelectorNetworkManager.class
                .getDeclaredMethod("connectNonBlocking", SocketChannel.class, InetSocketAddress.class);
        connectNonBlocking.setAccessible(true);
        try {
            connectNonBlocking.invoke(manager, channel, new InetSocketAddress("localhost", 8080));
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw e;
        }
    }

    /**
     * Reads one private field so state-reset tests can assert the manager no
     * longer publishes stale connection objects after close().
     */
    private Object readField(String fieldName) throws Exception {
        Field field = SelectorNetworkManager.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(manager);
    }

    /**
     * Skips socket-based tests when the environment forbids local bind/connect,
     * which happens in this sandbox but not in normal local runs.
     */
    private static void assumeSocketBindingAvailable() {
        try (ServerSocket server = new ServerSocket(0);
             SocketChannel client = SocketChannel.open()) {
            client.connect(new InetSocketAddress("localhost", server.getLocalPort()));
            server.accept().close();
        } catch (Exception blocked) {
            Assumptions.assumeTrue(false, "Local socket binding is unavailable in this environment");
        }
    }

    private static void acceptAsync(ServerSocket server) {
        Thread thread = new Thread(() -> {
            try {
                server.accept();
            } catch (IOException ignored) {
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Records published sender state for tests that need the actual key values
     * instead of only verifying that one interaction happened.
     */
    private static final class RecordingMessageSender extends SelectorMessageSender {
        private int setKeyCalls;
        private SelectionKey lastKey;
        private Selector lastSelector;

        @Override
        public void setKey(SelectionKey key, Selector selector) {
            setKeyCalls++;
            lastKey = key;
            lastSelector = selector;
        }
    }

    /**
     * Waits until a worker is blocked on the manager monitor so tests can force
     * the inner closeSocket() guard down its false branch deterministically.
     */
    private void waitForBlockedState(Thread thread) {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(1);
        while (thread.getState() != Thread.State.BLOCKED && System.currentTimeMillis() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        Assertions.assertEquals(Thread.State.BLOCKED, thread.getState());
    }

    /**
     * Overrides resource creation so openSocket() cleanup can be verified in
     * the current JVM and counted by the coverage report.
     */
    private static final class TestableNetworkManager extends SelectorNetworkManager {
        private final SocketChannel socketChannel;
        private final Selector selector;

        private TestableNetworkManager(SocketChannel socketChannel, Selector selector) {
            this.socketChannel = socketChannel;
            this.selector = selector;
        }

        @Override
        SocketChannel openSocketChannel() {
            return socketChannel;
        }

        @Override
        Selector openSelector() {
            return selector;
        }
    }

    /**
     * Keeps the few SocketChannel failure paths that Mockito cannot stub
     * reliably on final JDK methods under one configurable test double.
     */
    private static final class ScriptedSocketChannel extends SocketChannel {
        private final IOException connectFailure;
        private final IOException closeFailure;
        private final boolean requireNonBlockingAtConnect;
        private final int pendingFinishConnectAttempts;
        private int closeCalls;
        private int connectCalls;
        private int finishConnectCalls;

        private ScriptedSocketChannel(IOException connectFailure, IOException closeFailure) {
            this(connectFailure, closeFailure, false, 0);
        }

        private ScriptedSocketChannel(
                IOException connectFailure,
                IOException closeFailure,
                boolean requireNonBlockingAtConnect,
                int pendingFinishConnectAttempts) {
            super(SelectorProvider.provider());
            this.connectFailure = connectFailure;
            this.closeFailure = closeFailure;
            this.requireNonBlockingAtConnect = requireNonBlockingAtConnect;
            this.pendingFinishConnectAttempts = pendingFinishConnectAttempts;
        }

        @Override
        public int read(java.nio.ByteBuffer dst) {
            return 0;
        }

        @Override
        public long read(java.nio.ByteBuffer[] dsts, int offset, int length) {
            return 0;
        }

        @Override
        public int write(java.nio.ByteBuffer src) {
            return 0;
        }

        @Override
        public long write(java.nio.ByteBuffer[] srcs, int offset, int length) {
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
            return connectFailure == null && finishConnectCalls > pendingFinishConnectAttempts;
        }

        @Override
        public boolean isConnectionPending() {
            return false;
        }

        @Override
        public boolean connect(SocketAddress remote) throws IOException {
            connectCalls++;
            if (requireNonBlockingAtConnect && isBlocking()) {
                throw new IOException("connect called before non-blocking mode");
            }
            if (connectFailure != null) {
                throw connectFailure;
            }
            return pendingFinishConnectAttempts == 0;
        }

        @Override
        public boolean finishConnect() throws IOException {
            finishConnectCalls++;
            if (connectFailure != null) {
                throw connectFailure;
            }
            return finishConnectCalls > pendingFinishConnectAttempts;
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return InetSocketAddress.createUnresolved("localhost", 0);
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
        public java.util.Set<SocketOption<?>> supportedOptions() {
            return java.util.Collections.emptySet();
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

        int getCloseCalls() {
            return closeCalls;
        }

        int getConnectCalls() {
            return connectCalls;
        }

        int getFinishConnectCalls() {
            return finishConnectCalls;
        }
    }
}
