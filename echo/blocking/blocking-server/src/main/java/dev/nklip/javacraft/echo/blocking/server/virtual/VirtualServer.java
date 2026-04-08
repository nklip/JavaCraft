package dev.nklip.javacraft.echo.blocking.server.virtual;

import java.net.Socket;
import dev.nklip.javacraft.echo.blocking.server.common.ServerThread;
import dev.nklip.javacraft.echo.blocking.server.common.MultithreadedServer;

public class VirtualServer extends MultithreadedServer {

    public VirtualServer(int port) {
        super(port);
    }

    @Override
    public void startUpClient(Socket client) {
        // we use virtual threads, available since Java 21
        Thread.startVirtualThread(new ServerThread(client, connectedClients));
    }

}
