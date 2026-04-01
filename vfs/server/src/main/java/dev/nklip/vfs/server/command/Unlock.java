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
@Component("unlock")
@RequiredArgsConstructor
public class Unlock extends AbstractCommand implements Command {

    private final NodeService nodeService;
    private final LockService lockService;
    private final UserSessionService userSessionService;

    @Override
    public void apply(UserSession userSession, CommandValues values) {
        clientWriter = userSession.getClientWriter();
        User user = userSession.getUser();
        Node directory = userSession.getNode();
        String key = values.getNextKey();
        String unlockDirectory = values.getNextParam();

        Node node = nodeService.getNode(directory, unlockDirectory);
        if (node != null) {
            boolean recursive = key != null && key.equalsIgnoreCase("r");

            if (!lockService.isLocked(node, recursive)) {
                sendFail("Node is already unlocked!");
                return;
            }
            if (lockService.unlock(user, node, recursive)) {
                sendOK(String.format("Node '%s' was unlocked!", nodeService.getFullPath(node)));

                userSessionService.notifyUsers(
                        userSession.getUser().getId(),
                        String.format("Node '%s' was unlocked by user '%s'", nodeService.getFullPath(node), user.getLogin())
                );
            } else {
                sendOK("Node is locked by different user!");
            }
        } else {
            sendOK("Node is not found!");
        }
    }
}
