package dev.nklip.vfs.server.network;

import io.netty.channel.ChannelHandlerContext;
import dev.nklip.vfs.core.network.protocol.Protocol.Response;

/**
 * @author Lipatov Nikita
 */
public class ClientWriter {

    private final ChannelHandlerContext ctx;
    private final NettyServerHandler handler;

    public ClientWriter(ChannelHandlerContext ctx, NettyServerHandler handler) {
        this.ctx = ctx;
        this.handler = handler;
    }

    public void send(Response response) {
        handler.sendBack(ctx, response);
    }
}
