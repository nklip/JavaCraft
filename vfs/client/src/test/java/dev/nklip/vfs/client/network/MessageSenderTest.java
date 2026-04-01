package dev.nklip.vfs.client.network;

import io.netty.channel.embedded.EmbeddedChannel;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import dev.nklip.vfs.core.network.protocol.Protocol;

class MessageSenderTest {
    private static final long WAITING_STATE_TIMEOUT_SECONDS = 2;

    @Test
    void testSendReturnsFalseWhenUserIsNull() {
        MessageSender messageSender = new MessageSender();

        boolean result = messageSender.send(null, "connect nikita");

        Assertions.assertFalse(result);
    }

    @Test
    void testSendWritesExpectedRequestToChannel() {
        MessageSender messageSender = new MessageSender();
        EmbeddedChannel channel = new EmbeddedChannel();
        messageSender.setChannel(channel);
        Protocol.User user = Protocol.User.newBuilder()
                .setId("42")
                .setLogin("nikita")
                .build();

        boolean result = messageSender.send(user, "stats");

        Assertions.assertTrue(result);
        Protocol.Request request = channel.readOutbound();
        Assertions.assertNotNull(request);
        Assertions.assertEquals("stats", request.getCommand());
        Assertions.assertEquals("42", request.getUser().getId());
        Assertions.assertEquals("nikita", request.getUser().getLogin());

        channel.close();
    }

    @Test
    void testSendWaitsForChannelAndThenSends() throws Exception {
        MessageSender messageSender = new MessageSender();
        Protocol.User user = Protocol.User.newBuilder()
                .setId("100")
                .setLogin("delayed")
                .build();
        EmbeddedChannel channel = new EmbeddedChannel();

        try (ExecutorService executorService = Executors.newSingleThreadExecutor()) {
            Future<Boolean> resultFuture = executorService.submit(() -> messageSender.send(user, "pwd"));

            Thread.sleep(100);
            Assertions.assertFalse(resultFuture.isDone(), "send() should wait until channel is set");

            messageSender.setChannel(channel);

            Assertions.assertTrue(resultFuture.get(2, TimeUnit.SECONDS));
            Protocol.Request request = channel.readOutbound();
            Assertions.assertNotNull(request);
            Assertions.assertEquals("pwd", request.getCommand());
            Assertions.assertEquals("100", request.getUser().getId());
            Assertions.assertEquals("delayed", request.getUser().getLogin());
        }

        channel.close();
    }

    @Test
    void testSendReturnsTrueWhenInterruptedWhileWaitingForChannel() throws Exception {
        MessageSender messageSender = new MessageSender();
        Protocol.User user = Protocol.User.newBuilder()
                .setId("101")
                .setLogin("interrupted")
                .build();
        AtomicReference<Boolean> sendResult = new AtomicReference<>();
        AtomicReference<Throwable> unexpectedFailure = new AtomicReference<>();

        PrintStream originalOut = System.out;
        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outContent, true, StandardCharsets.UTF_8));
        try {
            Thread senderThread = Thread.ofPlatform()
                    .name("message-sender-interrupt-test")
                    .unstarted(() -> {
                        try {
                            sendResult.set(messageSender.send(user, "pwd"));
                        } catch (Throwable throwable) {
                            unexpectedFailure.set(throwable);
                        }
                    });

            senderThread.start();
            waitUntilWaiting(senderThread);

            senderThread.interrupt();
            senderThread.join(TimeUnit.SECONDS.toMillis(2));

            Assertions.assertFalse(senderThread.isAlive(), "Sender thread should finish after interruption");
            Assertions.assertNull(unexpectedFailure.get(), "send() should swallow InterruptedException");
            Assertions.assertEquals(Boolean.TRUE, sendResult.get());
            Assertions.assertTrue(
                    outContent.toString(StandardCharsets.UTF_8).contains("MessageSender = java.lang.InterruptedException"),
                    "Interrupted path should log InterruptedException");
        } finally {
            System.setOut(originalOut);
        }
    }

    private void waitUntilWaiting(Thread thread) {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAITING_STATE_TIMEOUT_SECONDS);
        while (System.nanoTime() < deadlineNanos) {
            if (thread.getState() == Thread.State.WAITING) {
                return;
            }
            if (!thread.isAlive()) {
                break;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }

        Assertions.fail("Thread did not enter WAITING state in time");
    }
}
