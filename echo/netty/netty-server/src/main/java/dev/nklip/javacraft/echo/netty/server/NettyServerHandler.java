package dev.nklip.javacraft.echo.netty.server;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;

/**
 * @author Lipatov Nikita
 */
@Slf4j
public class NettyServerHandler extends SimpleChannelInboundHandler<String> {

    private final ChannelGroup channels;

    public NettyServerHandler(ChannelGroup channels) {
        this.channels = Objects.requireNonNull(channels, "channels");
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        // Send greeting for a new connection.
        String hostName;
        try {
            hostName = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            log.warn("Unable to resolve local hostname", e);
            hostName = "unknown";
        }
        ctx.write("Welcome to " + hostName + "!\r\n");
        ctx.write("It is " + LocalDateTime.now() + " now.\r\n");
        ctx.flush();

        channels.add(ctx.channel());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        log.info("Client disconnected: {}", ctx.channel().remoteAddress());
        // ChannelGroup automatically removes closed channels,
        // so no manual removal is needed here.
        super.channelInactive(ctx);
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, String request) {
        // Generate and write a response.
        String response;
        boolean close = false;
        if (request.isEmpty()) {
            response = "Please type something.\r\n";
        } else if ("bye".equalsIgnoreCase(request)) {
            response = "Have a good day!\r\n";
            close = true;
        } else if ("hello".equalsIgnoreCase(request)) {
            sendToAll(ctx, "hello everybody!");
            return;
        } else if ("stats".equalsIgnoreCase(request)) {
            response = "Simultaneously connected clients: %s\r\n".formatted(channels.size());
        } else {
            response = "Did you say '" + request + "'?\r\n";
        }
        // We do not need to write a ChannelBuffer here.
        // We know the encoder inserted at NettyServerInitializer will do the conversion.
        ChannelFuture future = ctx.writeAndFlush(response);
        // Close the connection after sending 'Have a good day!'
        // if the client has sent 'bye'.
        if (close) {
            future.addListener(ChannelFutureListener.CLOSE);
        }
    }

    public void sendToAll(ChannelHandlerContext ctx, String msg) {
        String senderAddress = String.valueOf(ctx.channel().remoteAddress());
        for (Channel c : channels) {
            if (c != ctx.channel()) {
                c.writeAndFlush("[" + senderAddress + "] " + msg + "\r\n");
            } else {
                c.writeAndFlush("[you] " + msg + "\r\n");
            }
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Unexpected error on channel {}", ctx.channel().remoteAddress(), cause);
        ctx.close();
    }
}
