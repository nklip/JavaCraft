package dev.nklip.javacraft.echo.netty.client;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import java.util.concurrent.CountDownLatch;

/**
 * @author Lipatov Nikita
 */
public class NettyClientInitializer extends ChannelInitializer<SocketChannel> {
    private static final int MAX_FRAME_LENGTH = 8192;
    private static final StringDecoder DECODER = new StringDecoder();
    private static final StringEncoder ENCODER = new StringEncoder();

    private volatile NettyClientHandler clientHandler;
    private final CountDownLatch handlerReady = new CountDownLatch(1);

    @Override
    public void initChannel(SocketChannel ch) {
        clientHandler = new NettyClientHandler();
        ChannelPipeline pipeline = ch.pipeline();

        // Add the text line codec combination first,
        pipeline.addLast(new DelimiterBasedFrameDecoder(MAX_FRAME_LENGTH, Delimiters.lineDelimiter()));
        pipeline.addLast(DECODER);
        pipeline.addLast(ENCODER);

        // and then business logic.
        pipeline.addLast(clientHandler);
        handlerReady.countDown();
    }

    public NettyClientHandler getClientHandler() {
        try {
            handlerReady.await();
        } catch (InterruptedException e) {
            // getClientHandler() is a mid-level method — it catches InterruptedException, restores the flag, and rethrows as RuntimeException.
            //
            // Even though the caller gets notified via the exception, the interrupt flag should still be set because:
            //
            // The caller might catch the RuntimeException and then check the flag
            // Code in finally blocks up the stack may need to know the thread was interrupted
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted waiting for client handler", e);
        }
        return clientHandler;
    }
}
