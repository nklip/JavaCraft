package dev.nklip.vfs.server.network;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.protobuf.ProtobufDecoder;
import io.netty.handler.codec.protobuf.ProtobufEncoder;
import io.netty.handler.codec.protobuf.ProtobufVarint32FrameDecoder;
import io.netty.handler.codec.protobuf.ProtobufVarint32LengthFieldPrepender;
import dev.nklip.vfs.core.network.protocol.Protocol.Request;
import dev.nklip.vfs.server.CommandLine;
import dev.nklip.vfs.server.service.UserSessionService;

/**
 * @author Lipatov Nikita
 */
public class NettyServerInitializer extends ChannelInitializer<SocketChannel> {
    private final UserSessionService userSessionService;
    private final CommandLine commandLine;

    public NettyServerInitializer(UserSessionService userSessionService, CommandLine commandLine) {
        super();
        this.userSessionService = userSessionService;
        this.commandLine = commandLine;
    }

    public void initChannel(SocketChannel ch) {
        ChannelPipeline pipeline = ch.pipeline();

        pipeline.addLast(new ProtobufVarint32FrameDecoder());
        pipeline.addLast(new ProtobufDecoder(Request.getDefaultInstance()));

        pipeline.addLast(new ProtobufVarint32LengthFieldPrepender());
        pipeline.addLast(new ProtobufEncoder());

        NettyServerHandler handler = new NettyServerHandler(userSessionService, commandLine);

        pipeline.addLast(handler);
    }
}
