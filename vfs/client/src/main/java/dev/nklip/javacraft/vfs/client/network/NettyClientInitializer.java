package dev.nklip.javacraft.vfs.client.network;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.protobuf.ProtobufDecoder;
import io.netty.handler.codec.protobuf.ProtobufEncoder;
import io.netty.handler.codec.protobuf.ProtobufVarint32FrameDecoder;
import io.netty.handler.codec.protobuf.ProtobufVarint32LengthFieldPrepender;
import dev.nklip.javacraft.vfs.core.network.protocol.Protocol.Response;

/**
 * @author Lipatov Nikita
 */
public class NettyClientInitializer extends ChannelInitializer<SocketChannel> {

    private final UserManager userManager;
    private final NetworkManager networkManager;
    private final MessageSender messageSender;

    public NettyClientInitializer(UserManager userManager, NetworkManager networkManager, MessageSender messageSender) {
        super();
        this.userManager = userManager;
        this.networkManager = networkManager;
        this.messageSender = messageSender;
    }

    @Override
    public void initChannel(SocketChannel ch) {
        ChannelPipeline pipeline = ch.pipeline();

        pipeline.addLast(new ProtobufVarint32FrameDecoder());
        pipeline.addLast(new ProtobufDecoder(Response.getDefaultInstance()));

        pipeline.addLast(new ProtobufVarint32LengthFieldPrepender());
        pipeline.addLast(new ProtobufEncoder());

        // and then business logic.
        NettyClientHandler handler = new NettyClientHandler(this.userManager, this.networkManager, this.messageSender);
        pipeline.addLast(handler);
    }
}
