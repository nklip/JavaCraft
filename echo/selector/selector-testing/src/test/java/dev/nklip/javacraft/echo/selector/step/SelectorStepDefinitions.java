package dev.nklip.javacraft.echo.selector.step;

import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import dev.nklip.javacraft.echo.selector.client.SelectorClient;
import dev.nklip.javacraft.echo.selector.client.SelectorNetworkManager;
import org.junit.jupiter.api.Assertions;

public class SelectorStepDefinitions {

    @When("create a new client {string} for the server with the port = '{int}'")
    @When("client {string} connects on port {int}")
    public void createANewClientForTheServerWithThePort(String client, int port) throws IOException {
        connectClient(client, port);
    }

    @When("use the client {string} to send {string} message and get {string} response")
    @Then("client {string} sends {string} and receives {string}")
    public void sendMessage(String client, String message, String expectedResponse) {
        assertResponse(client, message, expectedResponse);
    }

    @When("use the client {string} to send escaped message {string} and get escaped response {string}")
    @Then("client {string} sends escaped message {string} and receives escaped response {string}")
    public void sendEscapedMessage(String client, String message, String expectedResponse) {
        assertResponse(client, decodeEscapedText(message), decodeEscapedText(expectedResponse));
    }

    @Then("close the connection to the client {string}")
    @Then("client {string} disconnects with goodbye")
    public void closeClientConnection(String client) {
        disconnectClientWithGoodbye(client);
    }

    @Then("client {string} socket is closed")
    public void clientSocketIsClosed(String client) throws Exception {
        assertClientSocketClosed(client);
    }

    @When("{int} clients with prefix {string} connect on port {int}")
    public void connectClientsWithPrefix(int clientCount, String prefix, int port) throws IOException {
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
                    .name("selector-load-" + currentClientName)
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

    private void assertResponse(String client, String message, String expectedResponse) {
        SelectorClient selectorClient = getClient(client);

        selectorClient.sendMessage(message);
        String actualResponse = selectorClient.readMessage();

        Assertions.assertEquals(expectedResponse, actualResponse,
                "Client '%s' sent '%s' but got unexpected response".formatted(client, message));
    }

    /**
     * Runs the load-work loop for one client thread so the high-load scenario
     * matches the requirement that every client uses its own system thread.
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
     * Keeps the random pacing in one place so the high-load step stays easy to
     * read and the delay rules are applied consistently for every client.
     */
    private void pauseRandomlyBetweenMessages(int minDelayMs, int maxDelayMs) throws InterruptedException {
        long delay = ThreadLocalRandom.current().nextLong(minDelayMs, maxDelayMs + 1L);
        Thread.sleep(delay);
    }

    /**
     * Waits for every client thread to finish and records timeouts or
     * interruptions as test failures instead of losing them in background work.
     */
    private void waitForClientThreads(List<Thread> clientThreads, ConcurrentLinkedQueue<Throwable> failures) {
        for (Thread clientThread : clientThreads) {
            try {
                clientThread.join(TimeUnit.SECONDS.toMillis(30));
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
     * Surfaces the first threaded load failure after all workers finish, while
     * keeping the remaining failures attached for easier debugging.
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
     * Creates and registers one named client so feature steps can reuse the
     * same connection setup logic for both small and large scenarios.
     */
    private void connectClient(String clientName, int port) throws IOException {
        SelectorClient selectorClient = new SelectorClient("localhost", port);
        selectorClient.connectToServer();

        SelectorClient previousClient = connections().putIfAbsent(clientName, selectorClient);
        if (previousClient != null) {
            selectorClient.close();
            Assertions.fail("Client '%s' already exists in this scenario".formatted(clientName));
        }
    }

    /**
     * Keeps goodbye handling in one place so every scenario verifies the final
     * response with the same behavior and error message.
     */
    private void disconnectClientWithGoodbye(String clientName) {
        SelectorClient selectorClient = getClient(clientName);

        selectorClient.sendMessage("bye");
        String actualResponse = selectorClient.readMessage();
        Assertions.assertEquals("Have a good day!", actualResponse,
                "Client '%s' did not receive expected goodbye response".formatted(clientName));
    }

    /**
     * Provides a predictable client name pattern for bulk scenarios so the
     * feature file stays readable while still covering many connections.
     */
    private String clientName(String prefix, int clientIndex) {
        return "%s-%03d".formatted(prefix, clientIndex);
    }

    /**
     * Fails fast when a scenario refers to a client name that was never created.
     */
    private SelectorClient getClient(String clientName) {
        SelectorClient selectorClient = connections().get(clientName);
        Assertions.assertNotNull(selectorClient, "Client '%s' was not created in this scenario".formatted(clientName));
        return selectorClient;
    }

    private Map<String, SelectorClient> connections() {
        return CommonStepDefinitions.connections();
    }

    /**
     * Polls the client connection state for a short time because the listener
     * closes the socket asynchronously after the goodbye response is read.
     */
    private void assertClientSocketClosed(String clientName) throws Exception {
        SelectorClient selectorClient = getClient(clientName);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            SocketChannel channel = getClientChannel(selectorClient);
            if (channel == null || !channel.isOpen()) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }

        SocketChannel channel = getClientChannel(selectorClient);
        Assertions.assertTrue(channel == null || !channel.isOpen(),
                "Client '%s' socket should be closed after goodbye".formatted(clientName));
    }

    /**
     * Reads the current client socket from SelectorClient so Cucumber can assert
     * connection shutdown without changing the production API for tests.
     */
    private SocketChannel getClientChannel(SelectorClient selectorClient) throws Exception {
        Field networkManagerField = SelectorClient.class.getDeclaredField("selectorNetworkManager");
        networkManagerField.setAccessible(true);
        SelectorNetworkManager networkManager = (SelectorNetworkManager) networkManagerField.get(selectorClient);

        Field clientField = SelectorNetworkManager.class.getDeclaredField("client");
        clientField.setAccessible(true);
        return (SocketChannel) clientField.get(networkManager);
    }

    private String decodeEscapedText(String text) {
        StringBuilder result = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (current != '\\' || i + 1 >= text.length()) {
                result.append(current);
                continue;
            }

            char escaped = text.charAt(++i);
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
