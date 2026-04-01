package dev.nklip.vfs.client.network;

import io.netty.channel.Channel;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class NettyClientTest {

    @Test
    void testConstructorStoresDependencies() throws Exception {
        UserManager userManager = new UserManager();
        MessageSender messageSender = new MessageSender();
        NetworkManager networkManager = new NetworkManager(userManager, messageSender);

        NettyClient nettyClient = new NettyClient(userManager, networkManager, messageSender);

        Assertions.assertSame(userManager, readField(nettyClient, "userManager"));
        Assertions.assertSame(networkManager, readField(nettyClient, "networkManager"));
        Assertions.assertSame(messageSender, readField(nettyClient, "messageSender"));
    }

    @Test
    void testCreateChannelReturnsNullWhenInterrupted() throws Exception {
        UserManager userManager = new UserManager();
        MessageSender messageSender = new MessageSender();
        NetworkManager networkManager = new NetworkManager(userManager, messageSender);
        NettyClient nettyClient = new NettyClient(userManager, networkManager, messageSender);

        AtomicReference<Channel> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread thread = Thread.ofPlatform()
                .name("netty-client-interrupt-test")
                .unstarted(() -> {
                    try {
                        Thread.currentThread().interrupt();
                        result.set(nettyClient.createChannel("localhost", 65535));
                    } catch (Throwable throwable) {
                        failure.set(throwable);
                    }
                });

        thread.start();
        thread.join(5000);

        Assertions.assertFalse(thread.isAlive(), "Interrupted createChannel thread should finish");
        Assertions.assertNull(failure.get(), "createChannel should catch InterruptedException");
        Assertions.assertNull(result.get(), "Interrupted createChannel should return null");
    }

    private Object readField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }
}
