package dev.nklip.javacraft.echo.blocking.step;

import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import dev.nklip.javacraft.echo.blocking.client.platform.PlatformThreadClient;
import dev.nklip.javacraft.echo.blocking.client.virtual.VirtualThreadClient;
import org.junit.jupiter.api.Assertions;

public class BlockingStepDefinitions {

    // ---------------------------------------------------------------------------
    // Client connect steps
    // ---------------------------------------------------------------------------

    @When("virtual client {string} connects on port {int}")
    public void connectVirtualClient(String client, int port) {
        VirtualThreadClient connectedClient = awaitVirtualClientConnected(port);
        VirtualThreadClient previous = virtualConnections().putIfAbsent(client, connectedClient);
        if (previous != null) {
            connectedClient.close();
            Assertions.fail("Client '%s' already exists in this scenario".formatted(client));
        }
    }

    @When("platform client {string} connects on port {int}")
    public void connectPlatformClient(String client, int port) {
        PlatformThreadClient connectedClient = awaitPlatformClientConnected(port);
        PlatformThreadClient previous = platformConnections().putIfAbsent(client, connectedClient);
        if (previous != null) {
            connectedClient.close();
            Assertions.fail("Client '%s' already exists in this scenario".formatted(client));
        }
    }

    // ---------------------------------------------------------------------------
    // Message steps
    // ---------------------------------------------------------------------------

    @Then("client {string} sends {string} and receives {string}")
    public void sendMessage(String client, String message, String expectedResponse) {
        assertResponse(client, message, expectedResponse);
    }

    @Then("client {string} sends escaped message {string} and receives escaped response {string}")
    public void sendEscapedMessage(String client, String message, String expectedResponse) {
        assertResponse(client, decodeEscapedText(message), decodeEscapedText(expectedResponse));
    }

    @Then("client {string} disconnects with goodbye")
    public void disconnectClientWithGoodbye(String client) {
        performGoodbye(client);
    }

    @Then("client {string} socket is closed")
    public void clientSocketIsClosed(String client) {
        resolveSocketClosed(client);
    }

    // ---------------------------------------------------------------------------
    // Bulk connect / disconnect steps for high-load scenarios
    // ---------------------------------------------------------------------------

    @When("{int} virtual clients with prefix {string} connect on port {int}")
    public void connectVirtualClientsWithPrefix(int clientCount, String prefix, int port) {
        for (int i = 1; i <= clientCount; i++) {
            connectVirtualClient(clientName(prefix, i), port);
        }
    }

    @When("{int} platform clients with prefix {string} connect on port {int}")
    public void connectPlatformClientsWithPrefix(int clientCount, String prefix, int port) {
        for (int i = 1; i <= clientCount; i++) {
            connectPlatformClient(clientName(prefix, i), port);
        }
    }

    @When("{int} clients with prefix {string} each send {int} echo messages from their own thread with a random delay between {int} and {int} milliseconds")
    public void clientsEachSendEchoMessagesWithRandomDelay(
            int clientCount,
            String prefix,
            int messagesPerClient,
            int minDelayMs,
            int maxDelayMs) {

        List<Thread> clientThreads = new ArrayList<>(clientCount);
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();

        for (int i = 1; i <= clientCount; i++) {
            String currentClientName = clientName(prefix, i);
            Thread clientThread = Thread.ofPlatform()
                    .name("load-" + currentClientName)
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
        for (int i = 1; i <= clientCount; i++) {
            performGoodbye(clientName(prefix, i));
        }
    }

    // ---------------------------------------------------------------------------
    // Private helpers — client resolution
    // ---------------------------------------------------------------------------

    /**
     * Dispatches send/read/isClosed operations to whichever map (virtual or platform)
     * contains the named client, then asserts the response.
     */
    @SuppressWarnings("resource") // client is owned by platformConnections and closed in cleanup()
    private void assertResponse(String clientName, String message, String expectedResponse) {
        VirtualThreadClient virtualClient = virtualConnections().get(clientName);
        if (virtualClient != null) {
            doAssertResponse(clientName, message, expectedResponse,
                    virtualClient::sendMessage, virtualClient::readMessage, virtualClient::isClosedByServer);
            return;
        }
        PlatformThreadClient platformClient = requirePlatformClient(clientName);
        doAssertResponse(clientName, message, expectedResponse,
                platformClient::sendMessage, platformClient::readMessage, () -> !platformClient.isConnected());
    }

    private void doAssertResponse(String clientName, String message, String expectedResponse,
            Consumer<String> send, Supplier<String> read, BooleanSupplier isClosed) {
        send.accept(message);
        String actual = read.get();
        Assertions.assertEquals(expectedResponse, actual,
                "Client '%s' sent '%s' but got unexpected response".formatted(clientName, message));
        if ("bye".equalsIgnoreCase(message)) {
            awaitSocketClosed(clientName, isClosed);
        }
    }

    /**
     * Sends "bye", asserts the farewell, waits for the server to close the
     * connection (which happens after the counter decrement), then returns.
     * The client socket itself is left open for {@code @After cleanup()} to close.
     */
    @SuppressWarnings("resource") // client is owned by platformConnections and closed in cleanup()
    private void performGoodbye(String clientName) {
        VirtualThreadClient virtualClient = virtualConnections().get(clientName);
        if (virtualClient != null) {
            doGoodbye(clientName, virtualClient::sendMessage, virtualClient::readMessage, virtualClient::isClosedByServer);
            return;
        }
        PlatformThreadClient platformClient = requirePlatformClient(clientName);
        doGoodbye(clientName, platformClient::sendMessage, platformClient::readMessage, () -> !platformClient.isConnected());
    }

    private void doGoodbye(String clientName, Consumer<String> send, Supplier<String> read,
            BooleanSupplier isClosed) {
        send.accept("bye");
        String actual = read.get();
        Assertions.assertEquals("Have a good day!", actual,
                "Client '%s' did not receive expected goodbye response".formatted(clientName));
        awaitSocketClosed(clientName, isClosed);
    }

    private void resolveSocketClosed(String clientName) {
        VirtualThreadClient virtualClient = virtualConnections().get(clientName);
        if (virtualClient != null) {
            awaitSocketClosed(clientName, virtualClient::isClosedByServer);
            return;
        }
        PlatformThreadClient platformClient = requirePlatformClient(clientName);
        awaitSocketClosed(clientName, () -> !platformClient.isConnected());
    }

    /**
     * Retries client construction until the server is ready or fails after five seconds.
     */
    private VirtualThreadClient awaitVirtualClientConnected(int port) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            try {
                VirtualThreadClient client = new VirtualThreadClient("virtual-client", "localhost", port);
                if (client.isConnected()) {
                    return client;
                }
                client.close();
            } catch (IllegalStateException ignored) {
                // fail-fast constructor can throw while server is still starting; keep retrying until deadline.
            }
            if (System.nanoTime() >= deadline) {
                Assertions.fail("Server not ready on port " + port + " within 5 seconds");
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
    }

    private PlatformThreadClient awaitPlatformClientConnected(int port) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            try {
                PlatformThreadClient client = new PlatformThreadClient("platform-client", "localhost", port);
                if (client.isConnected()) {
                    return client;
                }
                client.close();
            } catch (IllegalStateException ignored) {
                // fail-fast constructor can throw while server is still starting; keep retrying until deadline.
            }
            if (System.nanoTime() >= deadline) {
                Assertions.fail("Server not ready on port " + port + " within 5 seconds");
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
    }

    /**
     * Blocks until the client's listener thread detects the server-side EOF, or
     * fails after two seconds. After the server processes "bye" it closes its
     * streams (sending a TCP FIN), and the listener thread detects EOF and sets
     * the {@code socketClosed} flag. This flag is set only after the server's
     * counter decrement, so waiting here guarantees the shared counter is up to
     * date before the caller proceeds.
     */
    private void awaitSocketClosed(String clientName, BooleanSupplier isClosed) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (isClosed.getAsBoolean()) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        Assertions.assertTrue(isClosed.getAsBoolean(),
                "Client '%s' socket should be closed after goodbye".formatted(clientName));
    }

    private PlatformThreadClient requirePlatformClient(String clientName) {
        PlatformThreadClient client = platformConnections().get(clientName);
        Assertions.assertNotNull(client, "Client '%s' was not created in this scenario".formatted(clientName));
        return client;
    }

    /**
     * Runs the load-work loop for one client so the high-load scenario keeps
     * every client on its own platform thread.
     */
    private void sendMessagesWithRandomDelay(
            String clientName,
            int messagesPerClient,
            int minDelayMs,
            int maxDelayMs,
            ConcurrentLinkedQueue<Throwable> failures) {
        try {
            for (int i = 1; i <= messagesPerClient; i++) {
                String message = "%s message %03d".formatted(clientName, i);
                assertResponse(clientName, message, "Did you say '%s'?".formatted(message));
                if (i < messagesPerClient && maxDelayMs > 0) {
                    long delay = ThreadLocalRandom.current().nextLong(minDelayMs, maxDelayMs + 1L);
                    if (delay > 0) {
                        Thread.sleep(delay);
                    }
                }
            }
        } catch (Throwable t) {
            failures.add(t);
        }
    }

    private void waitForClientThreads(List<Thread> threads, ConcurrentLinkedQueue<Throwable> failures) {
        for (Thread thread : threads) {
            try {
                thread.join(TimeUnit.SECONDS.toMillis(30));
                if (thread.isAlive()) {
                    failures.add(new AssertionError(
                            "Load client thread '%s' did not finish in time".formatted(thread.getName())));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failures.add(e);
                return;
            }
        }
    }

    private void failIfAnyClientThreadFailed(ConcurrentLinkedQueue<Throwable> failures) {
        if (failures.isEmpty()) {
            return;
        }
        AssertionError combined = new AssertionError("High-load client threads reported failures");
        failures.forEach(combined::addSuppressed);
        throw combined;
    }

    private String clientName(String prefix, int index) {
        return "%s-%03d".formatted(prefix, index);
    }

    private Map<String, VirtualThreadClient> virtualConnections() {
        return CommonStepDefinitions.virtualConnections();
    }

    private Map<String, PlatformThreadClient> platformConnections() {
        return CommonStepDefinitions.platformConnections();
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
