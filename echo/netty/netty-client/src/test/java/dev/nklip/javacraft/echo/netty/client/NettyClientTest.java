package dev.nklip.javacraft.echo.netty.client;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NettyClientTest {

    private static final int PORT = 18090;
    private static Channel serverChannel;
    private static EventLoopGroup bossGroup;
    private static EventLoopGroup workerGroup;
    private InputStream originalIn;

    @BeforeAll
    static void startTestServer() throws InterruptedException {
        bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        workerGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());

        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(
                                new DelimiterBasedFrameDecoder(8192, Delimiters.lineDelimiter()),
                                new StringDecoder(),
                                new StringEncoder(),
                                new SimpleChannelInboundHandler<String>() {
                                    @Override
                                    public void channelActive(ChannelHandlerContext ctx) {
                                        ctx.writeAndFlush("Welcome to test-server!\r\n");
                                        ctx.writeAndFlush("It is test-time now.\r\n");
                                    }

                                    @Override
                                    protected void channelRead0(ChannelHandlerContext ctx, String msg) {
                                        if ("bye".equalsIgnoreCase(msg)) {
                                            ctx.writeAndFlush("Have a good day!\r\n")
                                                    .addListener(ChannelFutureListener.CLOSE);
                                        } else {
                                            ctx.writeAndFlush("Did you say '" + msg + "'?\r\n");
                                        }
                                    }
                                }
                        );
                    }
                });

        serverChannel = b.bind(PORT).sync().channel();
    }

    @AfterAll
    static void stopTestServer() {
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
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

    @Test
    void testOpenConnectionAndClose() throws InterruptedException {
        NettyClient client = new NettyClient("localhost", PORT);
        client.openConnection();
        client.close();
    }

    @Test
    void testReadGreetingMessages() throws InterruptedException {
        NettyClient client = new NettyClient("localhost", PORT);
        client.openConnection();
        Thread.sleep(200);

        String welcome = client.readMessage();
        Assertions.assertNotNull(welcome);
        Assertions.assertEquals("Welcome to test-server!", welcome);

        String dateMsg = client.readMessage();
        Assertions.assertNotNull(dateMsg);
        Assertions.assertEquals("It is test-time now.", dateMsg);

        client.close();
    }

    @Test
    void testSendAndReceiveEcho() throws InterruptedException {
        NettyClient client = new NettyClient("localhost", PORT);
        client.openConnection();
        Thread.sleep(200);
        // Drain greetings
        client.readMessage();
        client.readMessage();

        client.sendMessage("test message");
        Thread.sleep(200);
        String response = client.readMessage();
        Assertions.assertEquals("Did you say 'test message'?", response);

        client.close();
    }

    @Test
    void testSendByeReceivesGoodbye() throws InterruptedException {
        NettyClient client = new NettyClient("localhost", PORT);
        client.openConnection();
        Thread.sleep(200);
        // Drain greetings
        client.readMessage();
        client.readMessage();

        client.sendMessage("bye");
        Thread.sleep(200);
        String response = client.readMessage();
        Assertions.assertEquals("Have a good day!", response);

        client.close();
    }

    @Test
    void testCloseWithoutConnectionDoesNotThrow() {
        NettyClient client = new NettyClient("localhost", PORT);
        // close() without openConnection() should not throw
        Assertions.assertDoesNotThrow(client::close);
    }

    @Test
    void testRunWithInputThenEof() {
        // Provide one line of input followed by EOF —
        // covers the stdin loop, writeAndFlush, null-break, and lastWriteFuture.sync()
        System.setIn(new ByteArrayInputStream("hello\n".getBytes()));
        NettyClient client = new NettyClient("localhost", PORT);

        Assertions.assertDoesNotThrow(client::run);
    }

    @Test
    void testRunWithEmptyStdin() {
        // Empty stdin → readLine() returns null immediately →
        // lastWriteFuture stays null, skipping lastWriteFuture.sync()
        System.setIn(new ByteArrayInputStream(new byte[0]));
        NettyClient client = new NettyClient("localhost", PORT);

        Assertions.assertDoesNotThrow(client::run);
    }

    @Test
    void testRunWithByeCommand() {
        // "bye" triggers ch.closeFuture().sync() — waits for server to close connection
        System.setIn(new ByteArrayInputStream("bye\n".getBytes()));
        NettyClient client = new NettyClient("localhost", PORT);

        Assertions.assertDoesNotThrow(client::run);
    }

    @Test
    void testRunWithConnectionFailure() {
        // Wrong port → openConnection() throws ConnectException (extends IOException)
        // → caught by catch block → close() in finally
        System.setIn(new ByteArrayInputStream("hello\n".getBytes()));
        NettyClient client = new NettyClient("localhost", 19999);

        Assertions.assertDoesNotThrow(client::run);
    }

}
