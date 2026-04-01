package dev.nklip.javacraft.vfs.server.service;

import com.google.common.base.Strings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import dev.nklip.javacraft.vfs.core.network.protocol.Protocol.Response.ResponseType;
import dev.nklip.javacraft.vfs.core.network.protocol.Protocol.User;
import dev.nklip.javacraft.vfs.server.model.Node;
import dev.nklip.javacraft.vfs.server.model.Timer;
import dev.nklip.javacraft.vfs.server.model.UserSession;
import dev.nklip.javacraft.vfs.server.network.ClientWriter;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static dev.nklip.javacraft.vfs.core.network.protocol.ResponseFactory.newResponse;

/**
 * @author Lipatov Nikita
 */
@Component
public class UserSessionService {
    private final NodeService nodeService;
    private final LockService lockService;

    private final Map<String, UserSession> registry = new ConcurrentHashMap<>();

    @Autowired
    public UserSessionService(NodeService nodeService, LockService lockService) {
        this.nodeService = nodeService;
        this.lockService = lockService;
    }

    public UserSession startSession(ClientWriter clientWriter, Timer timer) {
        String sessionId = UUID.randomUUID().toString();
        User user = User.newBuilder()
                .setId(sessionId)
                .setLogin("")
                .build();

        UserSession userSession = new UserSession(user, timer, clientWriter);

        registry.put(sessionId, userSession);
        return userSession;
    }

    public void attachUser(String id, String login) {
        UserSession userSession = registry.get(id);

        Node loginHome = nodeService.createHomeDirectory(login);

        userSession.setNode(loginHome);
        User user = userSession.getUser();
        user = user.toBuilder().setLogin(login).build();
        userSession.setUser(user);
    }

    public boolean isLogged(String login) {
        Set<String> keySet = registry.keySet();
        for (String key : keySet) {
            UserSession userSession = registry.get(key);
            String userLogin = userSession.getUser().getLogin();
            if (userLogin.equals(login)) {
                return true;
            }
        }
        return false;
    }

    public UserSession getSession(String id) {
        return registry.get(id);
    }

    public void stopSession(String id) {
        UserSession userSession = registry.remove(id);
        if (userSession != null) { // can be null
            String login = userSession.getUser().getLogin();
            if (!Strings.isNullOrEmpty(login)) { // can be null or empty
                nodeService.removeHomeDirectory(login);
                lockService.unlockAll(userSession.getUser());
            }
        }
    }

    public final Map<String, UserSession> getRegistry() {
        return registry;
    }

    public void notifyUsers(String idMySession, String message) {
        Set<String> keySet = registry.keySet();
        for (String key : keySet) {
            UserSession userSession = registry.get(key);
            String login = userSession.getUser().getLogin();
            if (!userSession.getUser().getId().equals(idMySession) && !Strings.isNullOrEmpty(login)) { // to all users except mine and null sessions
                ClientWriter clientWriter = userSession.getClientWriter();
                clientWriter.send(
                        newResponse(
                                ResponseType.OK,
                                message
                        )
                );
            }
        }
    }


}
