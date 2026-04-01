package dev.nklip.javacraft.echo.netty.step;

import io.cucumber.java.After;
import io.cucumber.java.en.Given;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import dev.nklip.javacraft.echo.netty.client.NettyClient;
import dev.nklip.javacraft.echo.netty.server.NettyServer;

public class CommonStepDefinitions {

    private static final Map<String, NettyClient> connections = new ConcurrentHashMap<>();
    private static final List<NettyServer> servers = new ArrayList<>();

    @After
    public void cleanup() {
        connections.values().forEach(NettyClient::close);
        connections.clear();

        servers.forEach(NettyServer::stop);
        servers.clear();
    }

    @Given("socket server started up on port = '{int}'")
    @Given("the Netty server is running on port {int}")
    public void startUpSocketServer(int port) throws InterruptedException {
        NettyServer server = new NettyServer(port);
        server.start();
        servers.add(server);
    }

    static Map<String, NettyClient> connections() {
        return connections;
    }
}
