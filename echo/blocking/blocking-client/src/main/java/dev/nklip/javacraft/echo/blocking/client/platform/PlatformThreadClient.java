package dev.nklip.javacraft.echo.blocking.client.platform;

import lombok.extern.slf4j.Slf4j;
import dev.nklip.javacraft.echo.blocking.client.common.UserClient;

/**
 * PlatformThreadClient.
 * <p>
 * @author Lipatov Nikita
 */
@Slf4j
public class PlatformThreadClient extends UserClient implements Runnable, AutoCloseable {

    public PlatformThreadClient(
            final String threadName,
            final String host,
            final int port) {

        super(host, port);
        initializeConnection(threadName, "Platform", log);
    }

    @Override
    protected void startResponseListenerThread(String listenerThreadName, Runnable listenerTask) {
        Thread.ofPlatform()
                .name(listenerThreadName)
                .daemon(true)
                .start(listenerTask);
    }

    @Override
    public void run() {
        readUserMessages(log);
    }
}
