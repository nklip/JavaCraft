package dev.nklip.vfs.client.network;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import dev.nklip.vfs.core.VFSConstants;
import dev.nklip.vfs.core.network.protocol.Protocol.User;

/**
 * @author Lipatov Nikita
 */
public class UserManagerTest {
    private static final long WAIT_TIMEOUT_SECONDS = 2;

    @Test
    public void testSetUser() {

        UserManager userManager = new UserManager();

        User user1 = User.newBuilder()
                .setId(VFSConstants.NEW_USER)
                .setLogin("nikita")
                .build();

        userManager.setUser(user1);

        Assertions.assertTrue(userManager.isAuthorized());
        Assertions.assertEquals("nikita", user1.getLogin());

        User user2 = User.newBuilder()
                .setId(VFSConstants.NEW_USER)
                .setLogin("r2d2")
                .build();

        userManager.setUser(user2);

        Assertions.assertTrue(userManager.isAuthorized());
        Assertions.assertEquals("r2d2", user2.getLogin());

        userManager.setUser(null);
        Assertions.assertFalse(userManager.isAuthorized());
    }

    @Test
    public void testGetUserReturnsImmediatelyWhenUserAlreadySet() {
        UserManager userManager = new UserManager();
        User expected = User.newBuilder()
                .setId(VFSConstants.NEW_USER)
                .setLogin("ready")
                .build();
        userManager.setUser(expected);

        User actual = userManager.getUser();

        Assertions.assertSame(expected, actual);
    }

    @Test
    public void testGetUserWaitsAndReturnsAfterNotification() throws Exception {
        UserManager userManager = new UserManager();
        User expected = User.newBuilder()
                .setId(VFSConstants.NEW_USER)
                .setLogin("waiting")
                .build();
        AtomicReference<User> actual = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread getterThread = Thread.ofPlatform()
                .name("user-manager-getter")
                .unstarted(() -> {
                    try {
                        actual.set(userManager.getUser());
                    } catch (Throwable throwable) {
                        failure.set(throwable);
                    }
                });

        getterThread.start();
        waitUntilWaiting(getterThread);

        synchronized (userManager) {
            userManager.setUser(expected);
            userManager.notifyAll();
        }

        getterThread.join(TimeUnit.SECONDS.toMillis(WAIT_TIMEOUT_SECONDS));

        Assertions.assertFalse(getterThread.isAlive(), "Getter thread should finish once user is available");
        Assertions.assertNull(failure.get());
        Assertions.assertSame(expected, actual.get());
    }

    @Test
    public void testGetUserThrowsRuntimeExceptionWhenInterruptedWhileWaiting() throws Exception {
        UserManager userManager = new UserManager();
        AtomicReference<RuntimeException> runtimeException = new AtomicReference<>();
        AtomicReference<Throwable> unexpectedFailure = new AtomicReference<>();

        Thread getterThread = Thread.ofPlatform()
                .name("user-manager-interrupted-getter")
                .unstarted(() -> {
                    try {
                        userManager.getUser();
                    } catch (RuntimeException ex) {
                        runtimeException.set(ex);
                    } catch (Throwable throwable) {
                        unexpectedFailure.set(throwable);
                    }
                });

        getterThread.start();
        waitUntilWaiting(getterThread);

        getterThread.interrupt();
        getterThread.join(TimeUnit.SECONDS.toMillis(WAIT_TIMEOUT_SECONDS));

        Assertions.assertFalse(getterThread.isAlive(), "Getter thread should finish after interruption");
        Assertions.assertNull(unexpectedFailure.get());
        Assertions.assertNotNull(runtimeException.get());
        Assertions.assertInstanceOf(InterruptedException.class, runtimeException.get().getCause());
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
}
