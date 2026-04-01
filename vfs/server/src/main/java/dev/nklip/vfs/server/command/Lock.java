package dev.nklip.vfs.server.command;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import dev.nklip.vfs.core.command.CommandValues;
import dev.nklip.vfs.core.network.protocol.Protocol.User;
import dev.nklip.vfs.server.model.Node;
import dev.nklip.vfs.server.model.UserSession;
import dev.nklip.vfs.server.service.LockService;
import dev.nklip.vfs.server.service.NodeService;
import dev.nklip.vfs.server.service.UserSessionService;

/**
 * @author Lipatov Nikita
 */
@Component("lock")
@RequiredArgsConstructor
public class Lock extends AbstractCommand implements Command {

    private final NodeService nodeService;
    private final LockService lockService;
    private final UserSessionService userSessionService;

    @Override
    public void apply(UserSession userSession, CommandValues values) {
        clientWriter = userSession.getClientWriter();
        User user = userSession.getUser();
        Node directory = userSession.getNode();
        String key = values.getNextKey();
        String lockDirectory = values.getNextParam();

        Node node = nodeService.getNode(directory, lockDirectory);
        if (node != null) {
            boolean recursive = key != null && key.equalsIgnoreCase("r");
            if (lockService.isLocked(node, recursive)) {
                sendOK("Node or children nodes is/are locked!");
                return;
            }
            lockService.lock(user, node, recursive);

            sendOK(String.format("You has locked the node by path '%s'", nodeService.getFullPath(node)));

            userSessionService.notifyUsers(
                    userSession.getUser().getId(),
                    String.format("User %s has locked the node by path '%s'", user.getLogin(), nodeService.getFullPath(node))
            );
        } else {
            sendFail("Destination node is not found!");
        }
    }
}
