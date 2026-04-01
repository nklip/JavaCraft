package dev.nklip.javacraft.vfs.client.network;

import dev.nklip.javacraft.vfs.core.network.protocol.Protocol.User;
import lombok.Setter;

/**
 *
 * @author Lipatov Nikita
 */
@Setter
public class UserManager {
    private volatile User user;

    public boolean isAuthorized() {
        return (user != null);
    }

    public User getUser() {
        if(user == null) {
            synchronized (this) {
                while(user == null) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        System.err.println("NetworkManager.getSocket().IOException.Message=" + e.getMessage());
                        throw new RuntimeException(e);
                    }
                }
            }
        }
        return user;
    }

}
