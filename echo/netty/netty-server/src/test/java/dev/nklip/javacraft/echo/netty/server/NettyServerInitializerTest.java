package dev.nklip.javacraft.echo.netty.server;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class NettyServerInitializerTest {

    private static EventLoopGroup group;

    @BeforeAll
    static void setUp() {
        group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    }

    @AfterAll
    static void tearDown() throws InterruptedException {
        group.shutdownGracefully().sync();
    }

    private NioSocketChannel createRegisteredChannel() throws InterruptedException {
        NioSocketChannel ch = new NioSocketChannel();
        ch.pipeline().addLast(new NettyServerInitializer());
        group.register(ch).sync();
        return ch;
    }

    @Test
    void testPipelineContainsAllRequiredHandlers() throws InterruptedException {
        NioSocketChannel ch = createRegisteredChannel();

        Assertions.assertNotNull(ch.pipeline().get(DelimiterBasedFrameDecoder.class));
        Assertions.assertNotNull(ch.pipeline().get(StringDecoder.class));
        Assertions.assertNotNull(ch.pipeline().get(StringEncoder.class));
        Assertions.assertNotNull(ch.pipeline().get(NettyServerHandler.class));

        ch.close().sync();
    }

    @Test
    void testInitializerIsRemovedAfterInit() throws InterruptedException {
        NioSocketChannel ch = createRegisteredChannel();

        // ChannelInitializer removes itself from the pipeline after initChannel completes
        Assertions.assertNull(ch.pipeline().get(NettyServerInitializer.class));

        ch.close().sync();
    }

    @Test
    void testPipelineHandlerOrder() throws InterruptedException {
        NioSocketChannel ch = createRegisteredChannel();

        List<Class<?>> handlerTypes = new ArrayList<>();
        ch.pipeline().forEach(entry -> handlerTypes.add(entry.getValue().getClass()));

        int frameDecoderIdx = indexOfType(handlerTypes, DelimiterBasedFrameDecoder.class);
        int decoderIdx = indexOfType(handlerTypes, StringDecoder.class);
        int encoderIdx = indexOfType(handlerTypes, StringEncoder.class);
        int handlerIdx = indexOfType(handlerTypes, NettyServerHandler.class);

        Assertions.assertTrue(frameDecoderIdx < decoderIdx,
                "DelimiterBasedFrameDecoder should be before StringDecoder");
        Assertions.assertTrue(decoderIdx < encoderIdx,
                "StringDecoder should be before StringEncoder");
        Assertions.assertTrue(encoderIdx < handlerIdx,
                "StringEncoder should be before NettyServerHandler");

        ch.close().sync();
    }

    @Test
    void testStringCodecsAreSharedAcrossChannels() throws InterruptedException {
        NioSocketChannel ch1 = createRegisteredChannel();
        NioSocketChannel ch2 = createRegisteredChannel();

        // Static DECODER and ENCODER instances should be shared across channels
        Assertions.assertSame(
                ch1.pipeline().get(StringDecoder.class),
                ch2.pipeline().get(StringDecoder.class)
        );
        Assertions.assertSame(
                ch1.pipeline().get(StringEncoder.class),
                ch2.pipeline().get(StringEncoder.class)
        );

        ch1.close().sync();
        ch2.close().sync();
    }

    private int indexOfType(List<Class<?>> types, Class<?> target) {
        for (int i = 0; i < types.size(); i++) {
            if (target.isAssignableFrom(types.get(i))) {
                return i;
            }
        }
        return -1;
    }

}
