package dev.nklip.javacraft.echo.netty.step;

import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.netty.channel.Channel;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import dev.nklip.javacraft.echo.netty.client.NettyClient;
import org.junit.jupiter.api.Assertions;

public class NettyStepDefinitions {

    private static final long READ_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5);

    @When("create a new client {string} for the server with the port = '{int}'")
    @When("client {string} connects to the Netty server on port {int}")
    public void createANewClientForTheServerWithThePort(String client, int port) throws InterruptedException {
        connectClient(client, port);
    }

    @When("use the client {string} to send {string} message and get {string} response")
    @Then("client {string} sends {string} and receives {string}")
    public void sendMessage(String client, String message, String expectedResponse) {
        assertResponse(client, message, expectedResponse);
    }

    @Then("client {string} sends escaped message {string} and receives escaped response {string}")
    public void sendEscapedMessage(String client, String message, String expectedResponse) {
        assertResponse(client, decodeEscapedText(message), decodeEscapedText(expectedResponse));
    }

    @Then("close the connection to the client {string}")
    @Then("client {string} disconnects with goodbye")
    public void closeConnection(String client) {
        disconnectClientWithGoodbye(client);
    }

    @Then("client {string} socket is closed")
    public void clientSocketIsClosed(String client) throws Exception {
        assertClientSocketClosed(client);
    }

    @When("{int} clients with prefix {string} connect to the Netty server on port {int}")
    public void connectClientsWithPrefix(int clientCount, String prefix, int port) throws InterruptedException {
        for (int clientIndex = 1; clientIndex <= clientCount; clientIndex++) {
            connectClient(clientName(prefix, clientIndex), port);
        }
    }

    @When("{int} clients with prefix {string} each send {int} echo messages from their own thread with a random delay between {int} and {int} milliseconds")
    public void clientsWithPrefixEachSendEchoMessagesFromTheirOwnThreadWithRandomDelay(
            int clientCount,
            String prefix,
            int messagesPerClient,
            int minDelayMs,
            int maxDelayMs) {
        List<Thread> clientThreads = new ArrayList<>(clientCount);
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();

        for (int clientIndex = 1; clientIndex <= clientCount; clientIndex++) {
            String currentClientName = clientName(prefix, clientIndex);
            Thread clientThread = Thread.ofPlatform()
                    .name("netty-load-" + currentClientName)
                    .unstarted(() -> sendMessagesWithRandomDelay(
                            currentClientName,
                            messagesPerClient,
                            minDelayMs,
                            maxDelayMs,
                            failures));
            clientThreads.add(clientThread);
        }

        clientThreads.forEach(Thread::start);
        waitForClientThreads(clientThreads, failures);
        failIfAnyClientThreadFailed(failures);
    }

    @Then("{int} clients with prefix {string} disconnect with goodbye")
    public void clientsWithPrefixDisconnectWithGoodbye(int clientCount, String prefix) {
        for (int clientIndex = 1; clientIndex <= clientCount; clientIndex++) {
            disconnectClientWithGoodbye(clientName(prefix, clientIndex));
        }
    }

    /**
     * Creates one named Netty client and drains the standard greeting messages
     * so later assertions only observe business responses.
     */
    private void connectClient(String clientName, int port) throws InterruptedException {
        NettyClient nettyClient = new NettyClient("localhost", port);
        nettyClient.openConnection();
        drainGreetingMessages(clientName, nettyClient);

        NettyClient previousClient = connections().putIfAbsent(clientName, nettyClient);
        if (previousClient != null) {
            nettyClient.close();
            Assertions.fail("Client '%s' already exists in this scenario".formatted(clientName));
        }
    }

    /**
     * Keeps greeting handling in one place so connection steps stay short and
     * all tests wait for the same two startup messages.
     */
    private void drainGreetingMessages(String clientName, NettyClient nettyClient) {
        String welcomeMessage = awaitMessage(clientName, nettyClient, "welcome message");
        String dateMessage = awaitMessage(clientName, nettyClient, "server time message");

        Assertions.assertNotNull(welcomeMessage,
                "Client '%s' did not receive the expected welcome message".formatted(clientName));
        Assertions.assertNotNull(dateMessage,
                "Client '%s' did not receive the expected server time message".formatted(clientName));
        String safeWelcomeMessage = Objects.requireNonNull(welcomeMessage);
        String safeDateMessage = Objects.requireNonNull(dateMessage);

        Assertions.assertTrue(safeWelcomeMessage.startsWith("Welcome to "),
                "Client '%s' did not receive the expected welcome message".formatted(clientName));
        Assertions.assertTrue(safeDateMessage.startsWith("It is "),
                "Client '%s' did not receive the expected server time message".formatted(clientName));
    }

    /**
     * Sends one request and verifies the next queued message matches the
     * expected business response for that client.
     */
    private void assertResponse(String clientName, String message, String expectedResponse) {
        NettyClient nettyClient = getClient(clientName);
        nettyClient.sendMessage(message);

        String actualResponse = awaitMessage(clientName, nettyClient, "response to '%s'".formatted(message));
        Assertions.assertEquals(expectedResponse, actualResponse,
                "Client '%s' sent '%s' but got unexpected response".formatted(clientName, message));
    }

    /**
     * Reuses the same goodbye flow for every scenario so disconnect behavior is
     * verified consistently across simple and high-load tests.
     */
    private void disconnectClientWithGoodbye(String clientName) {
        NettyClient nettyClient = getClient(clientName);
        nettyClient.sendMessage("bye");

        String actualResponse = awaitMessage(clientName, nettyClient, "goodbye response");
        Assertions.assertEquals("Have a good day!", actualResponse,
                "Client '%s' did not receive expected goodbye response".formatted(clientName));
    }

    /**
     * Waits for one queued client message and fails with context if the Netty
     * client does not receive anything within its polling window.
     */
    private String awaitMessage(String clientName, NettyClient nettyClient, String expectedEvent) {
        long deadline = System.nanoTime() + READ_TIMEOUT_NANOS;
        while (System.nanoTime() < deadline) {
            String actualMessage = nettyClient.readMessage();
            if (actualMessage != null) {
                return actualMessage;
            }
        }

        Assertions.fail("Client '%s' did not receive the expected %s".formatted(clientName, expectedEvent));
        throw new IllegalStateException("Unreachable: awaitMessage failed for " + clientName);
    }

    /**
     * Provides stable bulk-client names so feature files stay easy to scan and
     * still identify exactly which client failed under load.
     */
    private String clientName(String prefix, int clientIndex) {
        return "%s-%03d".formatted(prefix, clientIndex);
    }

    /**
     * Fails fast when a scenario refers to a Netty client that was never created.
     */
    private NettyClient getClient(String clientName) {
        NettyClient nettyClient = connections().get(clientName);
        Assertions.assertNotNull(nettyClient, "Client '%s' was not created in this scenario".formatted(clientName));
        return nettyClient;
    }

    private Map<String, NettyClient> connections() {
        return CommonStepDefinitions.connections();
    }

    /**
     * Polls the underlying Netty channel because the server closes it
     * asynchronously after the goodbye response is flushed.
     */
    private void assertClientSocketClosed(String clientName) throws Exception {
        NettyClient nettyClient = getClient(clientName);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            Channel channel = getClientChannel(nettyClient);
            if (channel == null || !channel.isOpen()) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }

        Channel channel = getClientChannel(nettyClient);
        Assertions.assertTrue(channel == null || !channel.isOpen(),
                "Client '%s' socket should be closed after goodbye".formatted(clientName));
    }

    /**
     * Reads the current Netty channel so tests can verify connection closure
     * without changing the production API just for Cucumber assertions.
     */
    private Channel getClientChannel(NettyClient nettyClient) throws Exception {
        Field channelField = NettyClient.class.getDeclaredField("ch");
        channelField.setAccessible(true);
        return (Channel) channelField.get(nettyClient);
    }

    /**
     * Runs the load-work loop for one client thread so the high-load scenario
     * really uses one system thread per connected client.
     */
    private void sendMessagesWithRandomDelay(
            String clientName,
            int messagesPerClient,
            int minDelayMs,
            int maxDelayMs,
            ConcurrentLinkedQueue<Throwable> failures) {
        try {
            for (int messageIndex = 1; messageIndex <= messagesPerClient; messageIndex++) {
                String message = "%s message %03d".formatted(clientName, messageIndex);
                assertResponse(clientName, message, "Did you say '%s'?".formatted(message));
                if (messageIndex < messagesPerClient) {
                    pauseRandomlyBetweenMessages(minDelayMs, maxDelayMs);
                }
            }
        } catch (Throwable failure) {
            failures.add(failure);
        }
    }

    /**
     * Applies the random think time between sends so load clients behave more
     * like independent users instead of one synchronized burst.
     */
    private void pauseRandomlyBetweenMessages(int minDelayMs, int maxDelayMs) throws InterruptedException {
        long delay = ThreadLocalRandom.current().nextLong(minDelayMs, maxDelayMs + 1L);
        Thread.sleep(delay);
    }

    /**
     * Waits for every load client thread to finish and records stuck or
     * interrupted workers as explicit test failures.
     */
    private void waitForClientThreads(List<Thread> clientThreads, ConcurrentLinkedQueue<Throwable> failures) {
        for (Thread clientThread : clientThreads) {
            try {
                clientThread.join(TimeUnit.SECONDS.toMillis(60));
                if (clientThread.isAlive()) {
                    failures.add(new AssertionError(
                            "Load client thread '%s' did not finish in time".formatted(clientThread.getName())));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failures.add(e);
                return;
            }
        }
    }

    /**
     * Reports threaded load failures after all workers finish so the scenario
     * keeps the full failure context instead of hiding it in background threads.
     */
    private void failIfAnyClientThreadFailed(ConcurrentLinkedQueue<Throwable> failures) {
        if (failures.isEmpty()) {
            return;
        }

        AssertionError combinedFailure = new AssertionError("High-load client threads reported failures");
        failures.forEach(combinedFailure::addSuppressed);
        throw combinedFailure;
    }

    /**
     * Decodes escaped test text so feature files can describe tabs and
     * backslashes without depending on literal control characters.
     */
    private String decodeEscapedText(String text) {
        StringBuilder result = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current != '\\' || index + 1 >= text.length()) {
                result.append(current);
                continue;
            }

            char escaped = text.charAt(++index);
            switch (escaped) {
                case 'n' -> result.append('\n');
                case 'r' -> result.append('\r');
                case 't' -> result.append('\t');
                case '\\' -> result.append('\\');
                case '\'' -> result.append('\'');
                case '"' -> result.append('"');
                default -> {
                    result.append('\\');
                    result.append(escaped);
                }
            }
        }
        return result.toString();
    }
}
