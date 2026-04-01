package dev.nklip.javacraft.vfs.server.model;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import lombok.Getter;
import dev.nklip.javacraft.vfs.core.network.protocol.Protocol.User;

/**
 * @author Lipatov Nikita
 */
public class NodeLock {

    @Getter
    private volatile User user;
    private final Lock lock;

    public NodeLock() {
        lock = new ReentrantLock();
    }

    public boolean isLocked() {
        return user != null;
    }

    public void lock(User user) {
        if (user != null) {
            boolean result = lock.tryLock();
            if (result) {
                this.user = user;
            }
        }
    }

    public void unlock() {
        user = null;
        lock.unlock();
    }

}
