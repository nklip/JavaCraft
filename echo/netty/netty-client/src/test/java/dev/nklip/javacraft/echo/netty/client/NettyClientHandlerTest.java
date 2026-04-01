package dev.nklip.javacraft.echo.netty.client;

import io.netty.channel.embedded.EmbeddedChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class NettyClientHandlerTest {

    @Test
    void testMessageIsQueuedOnChannelRead() {
        NettyClientHandler handler = new NettyClientHandler();
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        channel.writeInbound("Hello from server");

        String message = handler.getMessage();
        Assertions.assertEquals("Hello from server", message);

        channel.close();
    }

    @Test
    void testMultipleMessagesAreQueuedInOrder() {
        NettyClientHandler handler = new NettyClientHandler();
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        channel.writeInbound("first");
        channel.writeInbound("second");
        channel.writeInbound("third");

        Assertions.assertEquals("first", handler.getMessage());
        Assertions.assertEquals("second", handler.getMessage());
        Assertions.assertEquals("third", handler.getMessage());

        channel.close();
    }

    @Test
    void testGetMessageReturnsNullWhenQueueIsEmpty() {
        NettyClientHandler handler = new NettyClientHandler();
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        // No messages written — getMessage should return null after timeout
        String message = handler.getMessage();
        Assertions.assertNull(message);

        channel.close();
    }

    @Test
    void testQueueOverflowEvictsOldestMessage() {
        NettyClientHandler handler = new NettyClientHandler();
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        // Fill the queue to capacity (10)
        for (int i = 1; i <= 10; i++) {
            channel.writeInbound("message-" + i);
        }

        // Write one more — should evict the oldest (message-1)
        channel.writeInbound("message-11");

        // First available message should be "message-2" (message-1 was evicted)
        Assertions.assertEquals("message-2", handler.getMessage());

        // Remaining messages should follow in order
        for (int i = 3; i <= 11; i++) {
            Assertions.assertEquals("message-" + i, handler.getMessage());
        }

        channel.close();
    }

    @Test
    void testExceptionCaughtClosesChannel() {
        NettyClientHandler handler = new NettyClientHandler();
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        channel.pipeline().fireExceptionCaught(new RuntimeException("test error"));

        Assertions.assertFalse(channel.isOpen());
    }

    @Test
    void testGetMessageRestoresInterruptFlagOnInterruption() throws Exception {
        NettyClientHandler handler = new NettyClientHandler();
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> future = executor.submit(() -> {
                // Interrupt this thread — getMessage() will catch InterruptedException
                Thread.currentThread().interrupt();
                handler.getMessage();
                // After getMessage() returns, the interrupt flag should still be set
                return Thread.currentThread().isInterrupted();
            });

            boolean interruptFlagPreserved = future.get(2, TimeUnit.SECONDS);
            Assertions.assertTrue(interruptFlagPreserved,
                    "getMessage() should restore the interrupt flag after catching InterruptedException");
        } finally {
            executor.shutdownNow();
            channel.close();
        }
    }

}
