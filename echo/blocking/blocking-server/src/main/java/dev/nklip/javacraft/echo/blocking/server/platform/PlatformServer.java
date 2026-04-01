package dev.nklip.javacraft.echo.blocking.server.platform;

import java.net.Socket;
import dev.nklip.javacraft.echo.blocking.server.common.MultithreadedServer;
import dev.nklip.javacraft.echo.blocking.server.common.ServerThread;

public class PlatformServer extends MultithreadedServer {

    public PlatformServer(int port) {
        super(port);
    }

    @Override
    public void startUpClient(Socket client) {
        // platform thread
        Thread.ofPlatform()
                .daemon(true)
                .start(new ServerThread(client, connectedClients));
    }
}
