package dev.nklip.javacraft.vfs.client.network;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import dev.nklip.javacraft.vfs.core.exceptions.QuitException;
import dev.nklip.javacraft.vfs.core.network.protocol.Protocol.Response;
import dev.nklip.javacraft.vfs.core.network.protocol.Protocol.Response.ResponseType;
import dev.nklip.javacraft.vfs.core.network.protocol.Protocol.User;

class NettyClientHandlerTest {

    @Test
    void testChannelReadSuccessConnectUpdatesUserWithServerId() {
        TrackingUserManager userManager = new TrackingUserManager();
        TrackingMessageSender messageSender = new TrackingMessageSender();
        TrackingNetworkManager networkManager = new TrackingNetworkManager(userManager, messageSender);
        userManager.setUser(User.newBuilder().setId("new").setLogin("nikita").build());
        NettyClientHandler handler = new NettyClientHandler(userManager, networkManager, messageSender);
        Response response = Response.newBuilder()
                .setCode(ResponseType.SUCCESS_CONNECT)
                .setMessage("connected")
                .setSpecificCode("42")
                .build();

        handler.channelRead0(null, response);

        User actualUser = userManager.getUser();
        Assertions.assertEquals("42", actualUser.getId());
        Assertions.assertEquals("nikita", actualUser.getLogin());
    }

    @Test
    void testChannelReadFailConnectThrowsQuitException() {
        TrackingUserManager userManager = new TrackingUserManager();
        TrackingMessageSender messageSender = new TrackingMessageSender();
        TrackingNetworkManager networkManager = new TrackingNetworkManager(userManager, messageSender);
        userManager.setUser(User.newBuilder().setId("new").setLogin("nikita").build());
        NettyClientHandler handler = new NettyClientHandler(userManager, networkManager, messageSender);
        Response response = Response.newBuilder()
                .setCode(ResponseType.FAIL_CONNECT)
                .setMessage("already exists")
                .build();

        QuitException exception = Assertions.assertThrows(QuitException.class, () -> handler.channelRead0(null, response));

        Assertions.assertEquals("Such user already exist!", exception.getMessage());
        Assertions.assertEquals("new", userManager.getUser().getId());
    }

    @Test
    void testChannelReadSuccessQuitThrowsQuitException() {
        TrackingUserManager userManager = new TrackingUserManager();
        TrackingMessageSender messageSender = new TrackingMessageSender();
        TrackingNetworkManager networkManager = new TrackingNetworkManager(userManager, messageSender);
        userManager.setUser(User.newBuilder().setId("new").setLogin("nikita").build());
        NettyClientHandler handler = new NettyClientHandler(userManager, networkManager, messageSender);
        Response response = Response.newBuilder()
                .setCode(ResponseType.SUCCESS_QUIT)
                .setMessage("bye")
                .build();

        QuitException exception = Assertions.assertThrows(QuitException.class, () -> handler.channelRead0(null, response));

        Assertions.assertEquals("Closing connection by client request", exception.getMessage());
        Assertions.assertEquals("new", userManager.getUser().getId());
    }

    @Test
    void testChannelReadFailDoesNotUpdateUser() {
        TrackingUserManager userManager = new TrackingUserManager();
        TrackingMessageSender messageSender = new TrackingMessageSender();
        TrackingNetworkManager networkManager = new TrackingNetworkManager(userManager, messageSender);
        userManager.setUser(User.newBuilder().setId("new").setLogin("nikita").build());
        NettyClientHandler handler = new NettyClientHandler(userManager, networkManager, messageSender);
        Response response = Response.newBuilder()
                .setCode(ResponseType.FAIL)
                .setMessage("fail")
                .build();

        handler.channelRead0(null, response);

        Assertions.assertEquals("new", userManager.getUser().getId());
    }

    @Test
    void testChannelReadDefaultBranchPrintsMessage() {
        TrackingUserManager userManager = new TrackingUserManager();
        TrackingMessageSender messageSender = new TrackingMessageSender();
        TrackingNetworkManager networkManager = new TrackingNetworkManager(userManager, messageSender);
        userManager.setUser(User.newBuilder().setId("new").setLogin("nikita").build());
        NettyClientHandler handler = new NettyClientHandler(userManager, networkManager, messageSender);
        Response response = Response.newBuilder()
                .setCode(ResponseType.FAIL_QUIT)
                .setMessage("default-branch")
                .build();

        PrintStream originalOut = System.out;
        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outContent, true, StandardCharsets.UTF_8));
        try {
            handler.channelRead0(null, response);
        } finally {
            System.setOut(originalOut);
        }

        String output = outContent.toString(StandardCharsets.UTF_8);
        Assertions.assertTrue(output.contains("default-branch"));
    }

    @Test
    void testExceptionCaughtAlwaysCleansUpClientState() {
        TrackingUserManager userManager = new TrackingUserManager();
        TrackingMessageSender messageSender = new TrackingMessageSender();
        TrackingNetworkManager networkManager = new TrackingNetworkManager(userManager, messageSender);
        NettyClientHandler handler = new NettyClientHandler(userManager, networkManager, messageSender);
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        ChannelHandlerContext context = channel.pipeline().context(handler);

        handler.exceptionCaught(context, new QuitException("closing"));

        Assertions.assertNull(userManager.getLastSetUser());
        Assertions.assertEquals(1, networkManager.getCloseSocketCalls());
        Assertions.assertEquals(1, messageSender.getSetChannelCalls());
        Assertions.assertNull(messageSender.getLastChannel());
        Assertions.assertFalse(channel.isOpen());
    }

    @Test
    void testExceptionCaughtSuppressesStackTraceForRemoteForcedClose() {
        TrackingUserManager userManager = new TrackingUserManager();
        TrackingMessageSender messageSender = new TrackingMessageSender();
        TrackingNetworkManager networkManager = new TrackingNetworkManager(userManager, messageSender);
        NettyClientHandler handler = new NettyClientHandler(userManager, networkManager, messageSender);
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        ChannelHandlerContext context = channel.pipeline().context(handler);

        PrintStream originalErr = System.err;
        ByteArrayOutputStream errContent = new ByteArrayOutputStream();
        System.setErr(new PrintStream(errContent, true, StandardCharsets.UTF_8));
        try {
            handler.exceptionCaught(context,
                    new RuntimeException("An existing connection was forcibly closed by the remote host"));
        } finally {
            System.setErr(originalErr);
        }

        String output = errContent.toString(StandardCharsets.UTF_8);
        Assertions.assertTrue(output.contains("An existing connection was forcibly closed by the remote host"));
        Assertions.assertFalse(output.contains("java.lang.RuntimeException"));
        Assertions.assertFalse(channel.isOpen());
    }

    @Test
    void testExceptionCaughtPrintsStackTraceForUnexpectedError() {
        TrackingUserManager userManager = new TrackingUserManager();
        TrackingMessageSender messageSender = new TrackingMessageSender();
        TrackingNetworkManager networkManager = new TrackingNetworkManager(userManager, messageSender);
        NettyClientHandler handler = new NettyClientHandler(userManager, networkManager, messageSender);
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        ChannelHandlerContext context = channel.pipeline().context(handler);

        PrintStream originalErr = System.err;
        ByteArrayOutputStream errContent = new ByteArrayOutputStream();
        System.setErr(new PrintStream(errContent, true, StandardCharsets.UTF_8));
        try {
            handler.exceptionCaught(context, new RuntimeException("unexpected-failure"));
        } finally {
            System.setErr(originalErr);
        }

        String output = errContent.toString(StandardCharsets.UTF_8);
        Assertions.assertTrue(output.contains("java.lang.RuntimeException: unexpected-failure"));
        Assertions.assertTrue(output.contains("unexpected-failure"));
        Assertions.assertFalse(channel.isOpen());
    }

    private static final class TrackingUserManager extends UserManager {
        private volatile User lastSetUser;

        @Override
        public void setUser(User user) {
            lastSetUser = user;
            super.setUser(user);
        }

        private User getLastSetUser() {
            return lastSetUser;
        }
    }

    private static final class TrackingMessageSender extends MessageSender {
        private final AtomicInteger setChannelCalls = new AtomicInteger();
        private volatile Channel lastChannel;

        @Override
        public void setChannel(Channel channel) {
            setChannelCalls.incrementAndGet();
            lastChannel = channel;
            super.setChannel(channel);
        }

        private int getSetChannelCalls() {
            return setChannelCalls.get();
        }

        private Channel getLastChannel() {
            return lastChannel;
        }
    }

    private static final class TrackingNetworkManager extends NetworkManager {
        private final AtomicInteger closeSocketCalls = new AtomicInteger();

        private TrackingNetworkManager(UserManager userManager, MessageSender messageSender) {
            super(userManager, messageSender);
        }

        @Override
        public void closeSocket() {
            closeSocketCalls.incrementAndGet();
        }

        private int getCloseSocketCalls() {
            return closeSocketCalls.get();
        }
    }
}
