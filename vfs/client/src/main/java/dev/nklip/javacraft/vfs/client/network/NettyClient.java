package dev.nklip.javacraft.vfs.client.network;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioSocketChannel;
import lombok.extern.slf4j.Slf4j;

/**
 * @author Lipatov Nikita
 */
@Slf4j
public class NettyClient {

    private final UserManager userManager;
    private final NetworkManager networkManager;
    private final MessageSender messageSender;

    public NettyClient(
            UserManager userManager,
            NetworkManager networkManager,
            MessageSender messageSender) {
        this.userManager = userManager;
        this.networkManager = networkManager;
        this.messageSender = messageSender;
    }

    public Channel createChannel(String host, int port) {
        Channel channel = null;
        EventLoopGroup group = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
        try {
            Bootstrap b = new Bootstrap();
            NettyClientInitializer initializer = new NettyClientInitializer(
                    this.userManager,
                    this.networkManager,
                    this.messageSender
            );
            b.group(group)
                    .channel(NioSocketChannel.class)
                    .handler(initializer);

            // Start the connection attempt.
            channel = b.connect(host, port).sync().channel();
        } catch (InterruptedException ie) {
            log.error("InterruptedException: ", ie);
        }
        return channel;
    }

}
