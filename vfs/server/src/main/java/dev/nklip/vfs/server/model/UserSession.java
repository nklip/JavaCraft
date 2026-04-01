package dev.nklip.vfs.server.model;

import lombok.Getter;
import lombok.Setter;
import dev.nklip.vfs.core.network.protocol.Protocol.User;
import dev.nklip.vfs.server.network.ClientWriter;

/**
 * @author Lipatov Nikita
 */
public class UserSession {

    private final Timer timer;
    private final ClientWriter clientWriter;

    @Setter
    @Getter
    private volatile User user;

    @Getter
    private volatile Node node;

    public UserSession(User user, Timer timer, ClientWriter clientWriter) {
        this.user = user;
        this.timer = timer;
        this.clientWriter = clientWriter;
    }

    public final Timer getTimer() {
        return timer;
    }

    public final ClientWriter getClientWriter() {
        return clientWriter;
    }

    public void setNode(Node node) {
        this.node = node;
        if (this.node.getType() != NodeTypes.DIR) {
            throw new IllegalArgumentException("UserSession: Node is not DIR!");
        }
    }

}
