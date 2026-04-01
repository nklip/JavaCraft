package dev.nklip.javacraft.vfs.client.network;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.protobuf.ProtobufDecoder;
import io.netty.handler.codec.protobuf.ProtobufEncoder;
import io.netty.handler.codec.protobuf.ProtobufVarint32FrameDecoder;
import io.netty.handler.codec.protobuf.ProtobufVarint32LengthFieldPrepender;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class NettyClientInitializerTest {

    @Test
    void testInitChannelAddsExpectedHandlersInOrder() {
        UserManager userManager = new UserManager();
        MessageSender messageSender = new MessageSender();
        NetworkManager networkManager = new NetworkManager(userManager, messageSender);
        NettyClientInitializer initializer = new NettyClientInitializer(userManager, networkManager, messageSender);
        SocketChannel channel = new NioSocketChannel();
        try {
            initializer.initChannel(channel);

            ChannelPipeline pipeline = channel.pipeline();
            Assertions.assertNotNull(pipeline.get(ProtobufVarint32FrameDecoder.class));
            Assertions.assertNotNull(pipeline.get(ProtobufDecoder.class));
            Assertions.assertNotNull(pipeline.get(ProtobufVarint32LengthFieldPrepender.class));
            Assertions.assertNotNull(pipeline.get(ProtobufEncoder.class));
            Assertions.assertNotNull(pipeline.get(NettyClientHandler.class));

            int frameDecoderIndex = indexOfHandler(pipeline, ProtobufVarint32FrameDecoder.class);
            int protobufDecoderIndex = indexOfHandler(pipeline, ProtobufDecoder.class);
            int lengthPrependerIndex = indexOfHandler(pipeline, ProtobufVarint32LengthFieldPrepender.class);
            int protobufEncoderIndex = indexOfHandler(pipeline, ProtobufEncoder.class);
            int businessHandlerIndex = indexOfHandler(pipeline, NettyClientHandler.class);

            Assertions.assertTrue(frameDecoderIndex < protobufDecoderIndex);
            Assertions.assertTrue(protobufDecoderIndex < lengthPrependerIndex);
            Assertions.assertTrue(lengthPrependerIndex < protobufEncoderIndex);
            Assertions.assertTrue(protobufEncoderIndex < businessHandlerIndex);
        } finally {
            closeChannelForTest(channel);
        }
    }

    @Test
    void testInitChannelPassesDependenciesToBusinessHandler() throws Exception {
        UserManager userManager = new UserManager();
        MessageSender messageSender = new MessageSender();
        NetworkManager networkManager = new NetworkManager(userManager, messageSender);
        NettyClientInitializer initializer = new NettyClientInitializer(userManager, networkManager, messageSender);
        SocketChannel channel = new NioSocketChannel();
        try {
            initializer.initChannel(channel);

            NettyClientHandler handler = channel.pipeline().get(NettyClientHandler.class);
            Assertions.assertNotNull(handler);
            Assertions.assertSame(userManager, readField(handler, "userManager"));
            Assertions.assertSame(networkManager, readField(handler, "networkManager"));
            Assertions.assertSame(messageSender, readField(handler, "messageSender"));
        } finally {
            closeChannelForTest(channel);
        }
    }

    private int indexOfHandler(ChannelPipeline pipeline, Class<?> handlerType) {
        List<String> names = pipeline.names();
        for (int index = 0; index < names.size(); index++) {
            ChannelHandler handler = pipeline.get(names.get(index));
            if (handlerType.isInstance(handler)) {
                return index;
            }
        }
        return -1;
    }

    private Object readField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private void closeChannelForTest(SocketChannel channel) {
        if (channel.isOpen()) {
            channel.unsafe().closeForcibly();
        }
    }
}
