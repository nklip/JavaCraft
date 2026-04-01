package dev.nklip.javacraft.echo.blocking.client.virtual;

import lombok.extern.slf4j.Slf4j;
import dev.nklip.javacraft.echo.blocking.client.common.UserClient;

/**
 * VirtualThreadClient.
 * <p>
 * @author Lipatov Nikita
 */
@Slf4j
public class VirtualThreadClient extends UserClient implements Runnable, AutoCloseable {

    public VirtualThreadClient(
            final String threadName,
            final String host,
            final int port) {

        super(host, port);
        initializeConnection(threadName, "Virtual", log);
    }

    @Override
    protected void startResponseListenerThread(String listenerThreadName, Runnable listenerTask) {
        Thread.ofVirtual()
                .name(listenerThreadName)
                .start(listenerTask);
    }

    @Override
    public void run() {
        readUserMessages(log);
    }
}
