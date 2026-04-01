package dev.nklip.javacraft.echo.blocking.step;

import io.cucumber.java.After;
import io.cucumber.java.en.Given;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import dev.nklip.javacraft.echo.blocking.client.platform.PlatformThreadClient;
import dev.nklip.javacraft.echo.blocking.client.virtual.VirtualThreadClient;
import dev.nklip.javacraft.echo.blocking.server.platform.PlatformServer;
import dev.nklip.javacraft.echo.blocking.server.virtual.VirtualServer;

public class CommonStepDefinitions {

    private static final Map<String, VirtualThreadClient> virtualConnections = new ConcurrentHashMap<>();
    private static final Map<String, PlatformThreadClient> platformConnections = new ConcurrentHashMap<>();
    private static final List<ExecutorService> serverExecutors = new ArrayList<>();

    @After
    public void cleanup() {
        virtualConnections.values().forEach(VirtualThreadClient::close);
        virtualConnections.clear();
        platformConnections.values().forEach(PlatformThreadClient::close);
        platformConnections.clear();
        serverExecutors.forEach(ExecutorService::shutdownNow);
        serverExecutors.clear();
    }

    @Given("the virtual server is running on port {int}")
    public void startVirtualServer(int port) {
        VirtualServer server = new VirtualServer(port);
        startServer(server);
    }

    @Given("the platform server is running on port {int}")
    public void startPlatformServer(int port) {
        PlatformServer server = new PlatformServer(port);
        startServer(server);
    }

    // Daemon threads let tests terminate without lingering server threads.
    private void startServer(Runnable server) {
        ExecutorService executorService = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r);
            thread.setDaemon(true);
            return thread;
        });
        executorService.execute(server);
        serverExecutors.add(executorService);
    }

    static Map<String, VirtualThreadClient> virtualConnections() {
        return virtualConnections;
    }

    static Map<String, PlatformThreadClient> platformConnections() {
        return platformConnections;
    }

}
