package dev.nklip.javacraft.echo.netty.server;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.GlobalEventExecutor;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class NettyServerHandlerTest {

    private EmbeddedChannel createChannel() {
        return createChannel(createGroup());
    }

    private EmbeddedChannel createChannel(ChannelGroup channels) {
        // Test the handler directly without codecs —
        // EmbeddedChannel passes Strings as-is without encoding to ByteBuf
        return new EmbeddedChannel(new NettyServerHandler(channels));
    }

    private ChannelGroup createGroup() {
        return new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    }

    private void drainGreeting(EmbeddedChannel channel) {
        channel.readOutbound(); // Welcome message
        channel.readOutbound(); // Date message
    }

    /**
     * Close channel and run pending tasks to ensure the ChannelGroup
     * close-listener fires and removes the channel from the test group.
     */
    private void closeAndCleanup(EmbeddedChannel channel) {
        channel.close();
        channel.runPendingTasks();
    }

    @Test
    void testChannelActiveWritesGreeting() {
        EmbeddedChannel channel = createChannel();

        String welcome = channel.readOutbound();
        Assertions.assertNotNull(welcome);
        Assertions.assertTrue(welcome.startsWith("Welcome to "));

        String dateMsg = channel.readOutbound();
        Assertions.assertNotNull(dateMsg);
        Assertions.assertTrue(dateMsg.startsWith("It is "));

        closeAndCleanup(channel);
    }

    @Test
    void testChannelActiveUsesUnknownOnHostnameFailure() {
        try (MockedStatic<InetAddress> mocked = Mockito.mockStatic(InetAddress.class)) {
            mocked.when(InetAddress::getLocalHost).thenThrow(new UnknownHostException("mocked"));

            EmbeddedChannel channel = new EmbeddedChannel(new NettyServerHandler(createGroup()));

            String welcome = channel.readOutbound();
            Assertions.assertNotNull(welcome);
            Assertions.assertEquals("Welcome to unknown!\r\n", welcome);

            String dateMsg = channel.readOutbound();
            Assertions.assertNotNull(dateMsg);
            Assertions.assertTrue(dateMsg.startsWith("It is "));

            closeAndCleanup(channel);
        }
    }

    @Test
    void testChannelInactiveOnClose() {
        EmbeddedChannel channel = createChannel();
        drainGreeting(channel);

        // Closing the channel should trigger channelInactive without errors
        Assertions.assertDoesNotThrow(() -> closeAndCleanup(channel));
    }

    @Test
    void testChannelInactivePropagatesEvent() {
        // Verify super.channelInactive(ctx) is called, propagating the event
        // to the next handler in the pipeline
        AtomicBoolean downstreamNotified = new AtomicBoolean(false);

        EmbeddedChannel channel = new EmbeddedChannel(
                new NettyServerHandler(createGroup()),
                new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelInactive(ChannelHandlerContext ctx) {
                        downstreamNotified.set(true);
                    }
                }
        );
        drainGreeting(channel);

        channel.close();

        Assertions.assertTrue(downstreamNotified.get(),
                "channelInactive event should propagate to downstream handlers via super.channelInactive(ctx)");
    }

    @Test
    void testEmptyInputReturnsPleasTypeSomething() {
        EmbeddedChannel channel = createChannel();
        drainGreeting(channel);

        channel.writeInbound("");

        String response = channel.readOutbound();
        Assertions.assertEquals("Please type something.\r\n", response);

        closeAndCleanup(channel);
    }

    @Test
    void testEchoResponse() {
        EmbeddedChannel channel = createChannel();
        drainGreeting(channel);

        channel.writeInbound("hello world");

        String response = channel.readOutbound();
        Assertions.assertEquals("Did you say 'hello world'?\r\n", response);

        closeAndCleanup(channel);
    }

    @Test
    void testByeClosesChannel() {
        EmbeddedChannel channel = createChannel();
        drainGreeting(channel);

        channel.writeInbound("bye");

        String response = channel.readOutbound();
        Assertions.assertEquals("Have a good day!\r\n", response);

        closeAndCleanup(channel);
    }

    @Test
    void testByeIsCaseInsensitive() {
        EmbeddedChannel channel = createChannel();
        drainGreeting(channel);

        channel.writeInbound("BYE");

        String response = channel.readOutbound();
        Assertions.assertEquals("Have a good day!\r\n", response);

        closeAndCleanup(channel);
    }

    @Test
    void testStatsReturnsClientCount() {
        EmbeddedChannel channel = createChannel();
        drainGreeting(channel);

        channel.writeInbound("stats");

        String response = channel.readOutbound();
        Assertions.assertNotNull(response);
        Assertions.assertTrue(response.startsWith("Simultaneously connected clients:"));
        Assertions.assertTrue(response.endsWith("\r\n"));

        closeAndCleanup(channel);
    }

    @Test
    void testHelloCallsSendToAll() {
        EmbeddedChannel channel = createChannel();
        drainGreeting(channel);

        // "hello" triggers sendToAll — verify no exception and no regular response
        Assertions.assertDoesNotThrow(() -> channel.writeInbound("hello"));
        channel.runPendingTasks();

        closeAndCleanup(channel);
    }

    @Test
    void testSendToAllBroadcastsToOtherChannel() {
        // EmbeddedChannels share the default ID (0xembedded), so DefaultChannelGroup
        // treats them as duplicates. Use unique DefaultChannelId to allow both in the group.
        ChannelGroup channels = createGroup();
        NettyServerHandler handler1 = new NettyServerHandler(channels);
        EmbeddedChannel ch1 = new EmbeddedChannel(
                io.netty.channel.DefaultChannelId.newInstance(), handler1);
        drainGreeting(ch1);

        EmbeddedChannel ch2 = new EmbeddedChannel(
                io.netty.channel.DefaultChannelId.newInstance(), new NettyServerHandler(channels));
        drainGreeting(ch2);

        // Call sendToAll directly — exercises both branches:
        // ch1 (sender) → else branch: "[you] ..."
        // ch2 (other)  → if branch:   "[<address>] ..."
        ChannelHandlerContext ctx1 = ch1.pipeline().context(handler1);
        handler1.sendToAll(ctx1, "test message");

        // Sender channel gets "[you] ..."
        ch1.flushOutbound();
        String selfMsg = ch1.readOutbound();
        Assertions.assertNotNull(selfMsg, "Sender should receive [you] message");
        Assertions.assertEquals("[you] test message\r\n", selfMsg);

        // Other channel gets "[<address>] ..."
        ch2.runPendingTasks();
        ch2.flushOutbound();
        String otherMsg = ch2.readOutbound();
        Assertions.assertNotNull(otherMsg, "Other channel should receive broadcast message");
        Assertions.assertTrue(otherMsg.endsWith("test message\r\n"),
                "Other channel message should contain the broadcast text");
        Assertions.assertFalse(otherMsg.startsWith("[you]"),
                "Non-sender channel should receive address-prefixed message, not [you]");

        closeAndCleanup(ch1);
        closeAndCleanup(ch2);
    }

    @Test
    void testStatsAndBroadcastAreIsolatedPerChannelGroup() {
        ChannelGroup serverOneChannels = createGroup();
        ChannelGroup serverTwoChannels = createGroup();

        NettyServerHandler serverOnePrimaryHandler = new NettyServerHandler(serverOneChannels);
        EmbeddedChannel serverOnePrimary = new EmbeddedChannel(
                io.netty.channel.DefaultChannelId.newInstance(), serverOnePrimaryHandler);
        drainGreeting(serverOnePrimary);

        EmbeddedChannel serverOneSecondary = new EmbeddedChannel(
                io.netty.channel.DefaultChannelId.newInstance(), new NettyServerHandler(serverOneChannels));
        drainGreeting(serverOneSecondary);

        NettyServerHandler serverTwoHandler = new NettyServerHandler(serverTwoChannels);
        EmbeddedChannel serverTwoClient = new EmbeddedChannel(
                io.netty.channel.DefaultChannelId.newInstance(), serverTwoHandler);
        drainGreeting(serverTwoClient);

        serverOnePrimary.writeInbound("stats");
        Assertions.assertEquals("Simultaneously connected clients: 2\r\n", serverOnePrimary.readOutbound());

        serverTwoClient.writeInbound("stats");
        Assertions.assertEquals("Simultaneously connected clients: 1\r\n", serverTwoClient.readOutbound());

        ChannelHandlerContext serverOneContext = serverOnePrimary.pipeline().context(serverOnePrimaryHandler);
        serverOnePrimaryHandler.sendToAll(serverOneContext, "hello everybody!");

        serverOnePrimary.flushOutbound();
        serverOneSecondary.flushOutbound();
        serverTwoClient.flushOutbound();

        String senderMessage = serverOnePrimary.readOutbound();
        Assertions.assertEquals("[you] hello everybody!\r\n", senderMessage);

        String sameServerMessage = serverOneSecondary.readOutbound();
        Assertions.assertNotNull(sameServerMessage);
        Assertions.assertTrue(sameServerMessage.endsWith("hello everybody!\r\n"));
        Assertions.assertFalse(sameServerMessage.startsWith("[you]"));

        Assertions.assertNull(serverTwoClient.readOutbound(),
                "Broadcast should stay inside one server's channel group");

        closeAndCleanup(serverOnePrimary);
        closeAndCleanup(serverOneSecondary);
        closeAndCleanup(serverTwoClient);
    }

    @Test
    void testChannelReadCompleteFlushes() {
        EmbeddedChannel channel = createChannel();
        drainGreeting(channel);

        // Write to context without flushing — data is buffered
        channel.pipeline().fireChannelReadComplete();

        // channelReadComplete calls ctx.flush() — verify no error
        Assertions.assertDoesNotThrow(channel::checkException);

        closeAndCleanup(channel);
    }

    @Test
    void testExceptionCaughtClosesChannel() {
        EmbeddedChannel channel = createChannel();

        channel.pipeline().fireExceptionCaught(new RuntimeException("test error"));

        Assertions.assertFalse(channel.isOpen());
    }

}
