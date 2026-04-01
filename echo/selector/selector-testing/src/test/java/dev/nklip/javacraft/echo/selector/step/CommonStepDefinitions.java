package dev.nklip.javacraft.echo.selector.step;

import io.cucumber.java.After;
import io.cucumber.java.en.Given;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import dev.nklip.javacraft.echo.selector.client.SelectorClient;
import dev.nklip.javacraft.echo.selector.server.SelectorServer;

public class CommonStepDefinitions {

    private static final Map<String, SelectorClient> connections = new ConcurrentHashMap<>();
    private static final List<ExecutorService> serverExecutors = new ArrayList<>();

    @After
    public void cleanup() {
        connections.values().forEach(SelectorClient::close);
        connections.clear();
        serverExecutors.forEach(ExecutorService::shutdownNow);
        serverExecutors.clear();
    }

    @Given("socket server started up on port = '{int}'")
    @Given("the selector server is running on port {int}")
    public void startUpSocketServer(int port) {
        SelectorServer server = new SelectorServer(port);
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        executorService.execute(server);
        serverExecutors.add(executorService);
    }

    static Map<String, SelectorClient> connections() {
        return connections;
    }

}
