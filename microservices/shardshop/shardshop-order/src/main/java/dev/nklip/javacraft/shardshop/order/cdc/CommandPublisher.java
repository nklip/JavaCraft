package dev.nklip.javacraft.shardshop.order.cdc;

/** Publishes one stored command envelope and waits for broker acceptance. */
public interface CommandPublisher extends AutoCloseable {
    void publish(String messageId, byte[] envelope) throws Exception;

    @Override
    void close();
}
