package dev.nklip.javacraft.echo.netty.client;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioSocketChannel;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;

/**
 * @author Lipatov Nikita
 */
@Slf4j
public class NettyClient {
    private static final int CLIENT_EVENT_LOOP_THREADS = 1;
    private static final Object GROUP_LOCK = new Object();
    private static EventLoopGroup sharedGroup;
    private static int sharedGroupUsers;

    private final String host;
    private final int port;
    private final EventLoopGroup group;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile Channel ch;
    private volatile NettyClientInitializer nettyClientInitializer;

    public NettyClient(String host, int port) {
        this.host = host;
        this.port = port;
        this.group = acquireSharedGroup();
    }

    // prevent NettyClient to create a new Netty event-loop group per client, which would exhaust thread creation
    // under 100-client benchmark load.
    private static EventLoopGroup acquireSharedGroup() {
        synchronized (GROUP_LOCK) {
            if (sharedGroup == null
                    || sharedGroup.isShuttingDown()
                    || sharedGroup.isShutdown()
                    || sharedGroup.isTerminated()) {
                sharedGroup = new MultiThreadIoEventLoopGroup(CLIENT_EVENT_LOOP_THREADS, NioIoHandler.newFactory());
                sharedGroupUsers = 0;
            }

            sharedGroupUsers++;
            return sharedGroup;
        }
    }

    private static void releaseSharedGroup() {
        EventLoopGroup groupToShutdown = null;
        synchronized (GROUP_LOCK) {
            if (sharedGroupUsers > 0) {
                sharedGroupUsers--;
            }

            if (sharedGroupUsers == 0 && sharedGroup != null) {
                groupToShutdown = sharedGroup;
                sharedGroup = null;
            }
        }

        if (groupToShutdown != null) {
            groupToShutdown.shutdownGracefully().syncUninterruptibly();
        }
    }

    public void openConnection() throws InterruptedException {
        this.nettyClientInitializer = new NettyClientInitializer();
        Bootstrap b = new Bootstrap();
        b.group(group)
                .channel(NioSocketChannel.class)
                .handler(nettyClientInitializer);

        // Start the connection attempt & return opened channel.
        this.ch = b.connect(host, port).sync().channel();
    }

    public void sendMessage(String message) {
        ch.writeAndFlush(message + "\r\n");
    }

    public String readMessage() {
        return nettyClientInitializer.getClientHandler().getMessage();
    }

    /**
     * Closes the connection and releases all resources.
     */
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        if (ch != null) {
            ch.close().syncUninterruptibly();
        }

        releaseSharedGroup();
    }

    public void run() {
        try {
            openConnection();

            // Read commands from the stdin.
            ChannelFuture lastWriteFuture = null;
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
            for (;;) {
                String line = in.readLine();
                if (line == null) {
                    break;
                }

                // Sends the received line to the server.
                lastWriteFuture = ch.writeAndFlush(line + "\r\n");

                // If user typed the 'bye' command, wait until the server closes
                // the connection.
                if ("bye".equalsIgnoreCase(line)) {
                    ch.closeFuture().sync();
                    break;
                }
            }

            // Wait until all messages are flushed before closing the channel.
            if (lastWriteFuture != null) {
                lastWriteFuture.sync();
            }
        } catch (IOException | InterruptedException e) {
            // no need for Thread.currentThread().interrupt();
            log.error(e.getMessage(), e);
        } finally {
            close();
        }
    }
}
