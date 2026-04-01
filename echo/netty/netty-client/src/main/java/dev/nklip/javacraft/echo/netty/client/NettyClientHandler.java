package dev.nklip.javacraft.echo.netty.client;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/**
 * @author Lipatov Nikita
 */
@Slf4j
public class NettyClientHandler extends SimpleChannelInboundHandler<String> {

    private static final int QUEUE_CAPACITY = 10;
    private static final long POLL_TIMEOUT_MS = 500;
    private final ArrayBlockingQueue<String> messageQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, String msg) {
        if (messageQueue.size() >= QUEUE_CAPACITY) {
            log.debug("Message was removed from the queue = '{}'", messageQueue.poll());
        }
        messageQueue.add(msg);
        log.info(msg);
    }

    public String getMessage() {
        try {
            return messageQueue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // Without Thread.currentThread().interrupt(), the interrupt signal is silently swallowed.
            // The calling code has no way to detect it happened and may continue looping indefinitely, ignoring the shutdown request.
            //
            // With the flag restored, the next blocking operation up the call stack (e.g., another poll(), sleep(), sync())
            // will throw InterruptedException, allowing the interruption to properly propagate.
            //
            // Rule of thumb: restore the interrupt flag in methods that catch and return instead of rethrowing.
            // Don't bother at the top of the call stack (like main() or run()) where the thread is about to terminate anyway.
            Thread.currentThread().interrupt();
            log.error("Interrupted while waiting for message", e);
            return null;
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Client error", cause);
        ctx.close();
    }
}
