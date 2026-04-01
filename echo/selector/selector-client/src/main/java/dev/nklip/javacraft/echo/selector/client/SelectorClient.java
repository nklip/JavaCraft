package dev.nklip.javacraft.echo.selector.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lombok.extern.slf4j.Slf4j;

/**
 * @author Lipatov Nikita
 */
@Slf4j
public class SelectorClient {
    private static final long SHUTDOWN_RESPONSE_WAIT_MS = 2_000L;

    private final SelectorNetworkManager selectorNetworkManager;
    private final ExecutorService listenerExecutor;
    private Future<?> listenerTask;

    private final String host;
    private final int port;

    public SelectorClient(String host, int port) {
        this(host, port, new SelectorNetworkManager(), Executors.newSingleThreadExecutor());
    }

    SelectorClient(String host, int port, SelectorNetworkManager selectorNetworkManager, ExecutorService listenerExecutor) {
        this.host = host;
        this.port = port;
        this.selectorNetworkManager = selectorNetworkManager;
        this.listenerExecutor = listenerExecutor;
        ensureMessageSenderConfigured();
    }

    public void connectToServer() throws IOException {
        selectorNetworkManager.openSocket(host, port);
        startListenerIfNeeded();
        log.info("You connected to the server.");
    }

    public void sendMessage(String message) {
        selectorNetworkManager.getSelectorMessageSender().send(message);
    }

    public String readMessage() {
        return selectorNetworkManager.getMessage();
    }

    public void close() {
        listenerExecutor.shutdownNow();
        selectorNetworkManager.closeSocket();
    }

    private void ensureMessageSenderConfigured() {
        if (selectorNetworkManager.getSelectorMessageSender() == null) {
            selectorNetworkManager.setSelectorMessageSender(new SelectorMessageSender());
        }
    }

    private void startListenerIfNeeded() {
        synchronized (this) {
            if (listenerTask == null || listenerTask.isDone()) {
                listenerTask = listenerExecutor.submit(new SelectorMessageListener(selectorNetworkManager));
            }
        }
    }

    public void run() {
        try
        (
            BufferedReader keyboard = new BufferedReader(new InputStreamReader(System.in))
        ) {
            connectToServer();

            boolean working = true;
            int sentCommands = 0;
            int receivedMessagesAtConnect = selectorNetworkManager.getReceivedMessageCount();
            while (working) {
                try {
                    String inputCommand = keyboard.readLine();
                    if (inputCommand == null) {
                        awaitPendingResponses(sentCommands, receivedMessagesAtConnect);
                        break;
                    }

                    sendMessage(inputCommand);
                    sentCommands++;

                    if ("bye".equalsIgnoreCase(inputCommand)) {
                        awaitPendingResponses(sentCommands, receivedMessagesAtConnect);
                        working = false;
                    }
                } catch (IOException e) {
                    log.error(e.getMessage(), e);
                }
            }
        } catch (Exception error) {
            log.error(error.getMessage(), error);
        } finally {
            close();
        }
    }

    /**
     * Waits only for replies the listener has not seen yet, so shutdown time is
     * based on outstanding work instead of the total number of commands ever sent.
     */
    private void awaitPendingResponses(int sentCommands, int receivedMessagesAtConnect) {
        if (sentCommands == 0) {
            return;
        }

        int targetReceivedCount = receivedMessagesAtConnect + sentCommands;
        if (selectorNetworkManager.getReceivedMessageCount() >= targetReceivedCount) {
            return;
        }

        selectorNetworkManager.awaitReceivedMessageCount(targetReceivedCount, SHUTDOWN_RESPONSE_WAIT_MS);
    }
}
