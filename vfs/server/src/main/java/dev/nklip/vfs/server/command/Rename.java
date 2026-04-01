package dev.nklip.vfs.server.command;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import dev.nklip.vfs.core.command.CommandValues;
import dev.nklip.vfs.server.model.Node;
import dev.nklip.vfs.server.model.UserSession;
import dev.nklip.vfs.server.service.LockService;
import dev.nklip.vfs.server.service.NodeService;
import dev.nklip.vfs.server.service.UserSessionService;

/**
 * @author Lipatov Nikita
 */
@Component("rename")
@RequiredArgsConstructor
public class Rename extends AbstractCommand implements Command {

    private final NodeService nodeService;
    private final LockService lockService;
    private final UserSessionService userSessionService;

    @Override
    public void apply(UserSession userSession, CommandValues values) {
        clientWriter = userSession.getClientWriter();
        Node directory = userSession.getNode();
        String renameNode = values.getNextParam();
        String newName = values.getNextParam();

        Node node = nodeService.getNode(directory, renameNode);

        if (node != null) {
            String oldName = node.getName();
            if (lockService.isLocked(node, false)) {
                sendFail("Node is locked!");
                return;
            }

            node.setName(newName);

            sendOK(String.format("Node '%s' was renamed to '%s'", oldName, newName));

            userSessionService.notifyUsers(
                    userSession.getUser().getId(),
                    String.format("Node '%s' was renamed to '%s' by user '%s'", oldName, newName, userSession.getUser().getLogin())
            );
        } else {
            sendFail("Node is not found!");
        }
    }
}
