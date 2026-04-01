package dev.nklip.javacraft.echo.netty.client;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class NettyClientInitializerTest {

    private static EventLoopGroup group;

    @BeforeAll
    static void setUp() {
        group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    }

    @AfterAll
    static void tearDown() throws InterruptedException {
        group.shutdownGracefully().sync();
    }

    private NioSocketChannel createRegisteredChannel(NettyClientInitializer initializer)
            throws InterruptedException {
        NioSocketChannel ch = new NioSocketChannel();
        ch.pipeline().addLast(initializer);
        group.register(ch).sync();
        return ch;
    }

    @Test
    void testPipelineContainsAllRequiredHandlers() throws InterruptedException {
        NettyClientInitializer initializer = new NettyClientInitializer();
        NioSocketChannel ch = createRegisteredChannel(initializer);

        Assertions.assertNotNull(ch.pipeline().get(DelimiterBasedFrameDecoder.class));
        Assertions.assertNotNull(ch.pipeline().get(StringDecoder.class));
        Assertions.assertNotNull(ch.pipeline().get(StringEncoder.class));
        Assertions.assertNotNull(ch.pipeline().get(NettyClientHandler.class));

        ch.close().sync();
    }

    @Test
    void testInitializerIsRemovedAfterInit() throws InterruptedException {
        NettyClientInitializer initializer = new NettyClientInitializer();
        NioSocketChannel ch = createRegisteredChannel(initializer);

        // ChannelInitializer removes itself from the pipeline after initChannel completes
        Assertions.assertNull(ch.pipeline().get(NettyClientInitializer.class));

        ch.close().sync();
    }

    @Test
    void testPipelineHandlerOrder() throws InterruptedException {
        NettyClientInitializer initializer = new NettyClientInitializer();
        NioSocketChannel ch = createRegisteredChannel(initializer);

        List<Class<?>> handlerTypes = new ArrayList<>();
        ch.pipeline().forEach(entry -> handlerTypes.add(entry.getValue().getClass()));

        int frameDecoderIdx = indexOfType(handlerTypes, DelimiterBasedFrameDecoder.class);
        int decoderIdx = indexOfType(handlerTypes, StringDecoder.class);
        int encoderIdx = indexOfType(handlerTypes, StringEncoder.class);
        int handlerIdx = indexOfType(handlerTypes, NettyClientHandler.class);

        Assertions.assertTrue(frameDecoderIdx < decoderIdx,
                "DelimiterBasedFrameDecoder should be before StringDecoder");
        Assertions.assertTrue(decoderIdx < encoderIdx,
                "StringDecoder should be before StringEncoder");
        Assertions.assertTrue(encoderIdx < handlerIdx,
                "StringEncoder should be before NettyClientHandler");

        ch.close().sync();
    }

    @Test
    void testGetClientHandlerReturnsHandlerFromPipeline() throws InterruptedException {
        NettyClientInitializer initializer = new NettyClientInitializer();
        NioSocketChannel ch = createRegisteredChannel(initializer);

        NettyClientHandler fromGetter = initializer.getClientHandler();
        NettyClientHandler fromPipeline = ch.pipeline().get(NettyClientHandler.class);

        Assertions.assertNotNull(fromGetter);
        Assertions.assertSame(fromPipeline, fromGetter,
                "getClientHandler() should return the same instance added to the pipeline");

        ch.close().sync();
    }

    @Test
    void testStringCodecsAreSharedAcrossChannels() throws InterruptedException {
        NettyClientInitializer init1 = new NettyClientInitializer();
        NettyClientInitializer init2 = new NettyClientInitializer();
        NioSocketChannel ch1 = createRegisteredChannel(init1);
        NioSocketChannel ch2 = createRegisteredChannel(init2);

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

    @Test
    void testGetClientHandlerRestoresInterruptFlagOnInterruption() throws Exception {
        // initChannel() never called — latch never counted down, so await() will block
        NettyClientInitializer initializer = new NettyClientInitializer();

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<Boolean> future = executor.submit(() -> {
                Thread.currentThread().interrupt();
                try {
                    initializer.getClientHandler();
                    return false; // should not reach here
                } catch (RuntimeException e) {
                    // getClientHandler() should restore the flag before throwing
                    return Thread.currentThread().isInterrupted();
                }
            });

            boolean interruptFlagPreserved = future.get(2, TimeUnit.SECONDS);
            Assertions.assertTrue(interruptFlagPreserved,
                    "getClientHandler() should restore the interrupt flag before throwing RuntimeException");
        }
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
