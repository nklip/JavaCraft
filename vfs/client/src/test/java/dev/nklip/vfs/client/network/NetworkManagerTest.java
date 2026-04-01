package dev.nklip.vfs.client.network;

import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class NetworkManagerTest {
    private static final long WAIT_TIMEOUT_SECONDS = 2;

    @Test
    void testGetMessageSenderReturnsInjectedSender() {
        UserManager userManager = new UserManager();
        TrackingMessageSender messageSender = new TrackingMessageSender();
        NetworkManager manager = new NetworkManager(userManager, messageSender);

        Assertions.assertSame(messageSender, manager.getMessageSender());
    }

    @Test
    void testOpenSocketCreatesChannelAndPublishesItToMessageSender() throws Exception {
        UserManager userManager = new UserManager();
        TrackingMessageSender messageSender = new TrackingMessageSender();
        NetworkManager manager = new NetworkManager(userManager, messageSender);
        EmbeddedChannel expectedChannel = new EmbeddedChannel();
        StubNettyClient stubClient = new StubNettyClient(expectedChannel);
        setField(manager, "nettyClient", stubClient);
        try {
            manager.openSocket("localhost", "4499");

            Assertions.assertSame(expectedChannel, manager.getChannel());
            Assertions.assertSame(expectedChannel, messageSender.getLastSetChannel());
            Assertions.assertEquals(1, messageSender.getSetChannelCalls());
            Assertions.assertEquals(1, stubClient.getCreateChannelCalls());
            Assertions.assertEquals("localhost", stubClient.getLastHost());
            Assertions.assertEquals(4499, stubClient.getLastPort());
        } finally {
            expectedChannel.close();
        }
    }

    @Test
    void testOpenSocketSkipsCreationWhenChannelAlreadySet() throws Exception {
        UserManager userManager = new UserManager();
        TrackingMessageSender messageSender = new TrackingMessageSender();
        NetworkManager manager = new NetworkManager(userManager, messageSender);
        EmbeddedChannel existingChannel = new EmbeddedChannel();
        EmbeddedChannel replacementChannel = new EmbeddedChannel();
        StubNettyClient stubClient = new StubNettyClient(replacementChannel);
        setField(manager, "nettyClient", stubClient);
        setField(manager, "channel", existingChannel);
        try {
            manager.openSocket("localhost", "4499");

            Assertions.assertSame(existingChannel, manager.getChannel());
            Assertions.assertEquals(0, stubClient.getCreateChannelCalls());
            Assertions.assertEquals(0, messageSender.getSetChannelCalls());
        } finally {
            existingChannel.close();
            replacementChannel.close();
        }
    }

    @Test
    void testGetChannelWaitsUntilChannelIsSetAndNotified() throws Exception {
        UserManager userManager = new UserManager();
        TrackingMessageSender messageSender = new TrackingMessageSender();
        NetworkManager manager = new NetworkManager(userManager, messageSender);
        EmbeddedChannel expectedChannel = new EmbeddedChannel();
        AtomicReference<Channel> actualChannel = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread getterThread = Thread.ofPlatform()
                .name("network-manager-get-channel")
                .unstarted(() -> {
                    try {
                        actualChannel.set(manager.getChannel());
                    } catch (Throwable throwable) {
                        failure.set(throwable);
                    }
                });

        getterThread.start();
        waitUntilWaiting(getterThread);

        synchronized (manager) {
            setField(manager, "channel", expectedChannel);
            manager.notifyAll();
        }

        getterThread.join(TimeUnit.SECONDS.toMillis(WAIT_TIMEOUT_SECONDS));
        Assertions.assertFalse(getterThread.isAlive(), "getChannel thread should finish after notify");
        Assertions.assertNull(failure.get());
        Assertions.assertSame(expectedChannel, actualChannel.get());

        expectedChannel.close();
    }

    @Test
    void testGetChannelThrowsRuntimeExceptionWhenInterruptedWhileWaiting() throws Exception {
        UserManager userManager = new UserManager();
        TrackingMessageSender messageSender = new TrackingMessageSender();
        NetworkManager manager = new NetworkManager(userManager, messageSender);
        AtomicReference<RuntimeException> runtimeException = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread getterThread = Thread.ofPlatform()
                .name("network-manager-get-channel-interrupted")
                .unstarted(() -> {
                    try {
                        manager.getChannel();
                    } catch (RuntimeException ex) {
                        runtimeException.set(ex);
                    } catch (Throwable throwable) {
                        failure.set(throwable);
                    }
                });

        getterThread.start();
        waitUntilWaiting(getterThread);

        getterThread.interrupt();
        getterThread.join(TimeUnit.SECONDS.toMillis(WAIT_TIMEOUT_SECONDS));

        Assertions.assertFalse(getterThread.isAlive(), "Interrupted getChannel thread should finish");
        Assertions.assertNull(failure.get());
        Assertions.assertNotNull(runtimeException.get());
        Assertions.assertInstanceOf(InterruptedException.class, runtimeException.get().getCause());
    }

    @Test
    void testCloseSocketClearsExistingChannel() throws Exception {
        UserManager userManager = new UserManager();
        TrackingMessageSender messageSender = new TrackingMessageSender();
        NetworkManager manager = new NetworkManager(userManager, messageSender);
        EmbeddedChannel channel = new EmbeddedChannel();
        setField(manager, "channel", channel);
        try {
            manager.closeSocket();

            Assertions.assertNull(readChannelField(manager));
        } finally {
            channel.close();
        }
    }

    @Test
    void testCloseSocketDoesNothingWhenChannelIsAlreadyNull() throws Exception {
        UserManager userManager = new UserManager();
        TrackingMessageSender messageSender = new TrackingMessageSender();
        NetworkManager manager = new NetworkManager(userManager, messageSender);

        manager.closeSocket();

        Assertions.assertNull(readChannelField(manager));
    }

    private void waitUntilWaiting(Thread thread) {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_TIMEOUT_SECONDS);
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

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private Object readChannelField(Object target) throws Exception {
        Field field = target.getClass().getDeclaredField("channel");
        field.setAccessible(true);
        return field.get(target);
    }

    private static final class StubNettyClient extends NettyClient {
        private final Channel channelToReturn;
        private final AtomicInteger createChannelCalls = new AtomicInteger();
        private volatile String lastHost;
        private volatile int lastPort;

        private StubNettyClient(Channel channelToReturn) {
            super(null, null, null);
            this.channelToReturn = channelToReturn;
        }

        @Override
        public Channel createChannel(String host, int port) {
            createChannelCalls.incrementAndGet();
            lastHost = host;
            lastPort = port;
            return channelToReturn;
        }

        private int getCreateChannelCalls() {
            return createChannelCalls.get();
        }

        private String getLastHost() {
            return lastHost;
        }

        private int getLastPort() {
            return lastPort;
        }
    }

    private static final class TrackingMessageSender extends MessageSender {
        private final AtomicInteger setChannelCalls = new AtomicInteger();
        private volatile Channel lastSetChannel;

        @Override
        public void setChannel(Channel channel) {
            setChannelCalls.incrementAndGet();
            lastSetChannel = channel;
            super.setChannel(channel);
        }

        private int getSetChannelCalls() {
            return setChannelCalls.get();
        }

        private Channel getLastSetChannel() {
            return lastSetChannel;
        }
    }
}
