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
@Component("rm")
@RequiredArgsConstructor
public class Remove extends AbstractCommand implements Command {

    private final NodeService nodeService;
    private final LockService lockService;
    private final UserSessionService userSessionService;

    @Override
    public void apply(UserSession userSession, CommandValues values) {
        clientWriter = userSession.getClientWriter();
        Node directory = userSession.getNode();
        String nodeName = values.getNextParam();

        Node node = nodeService.getNode(directory, nodeName);

        if (node != null) {
            if (lockService.isLocked(node, true)) {
                sendFail("Node or children nodes is / are locked!");
                return;
            }

            boolean isRemoved = nodeService.removeNode(directory, nodeName);
            sendOK(String.format("Node '%s' was deleted! Removal status = '%s'", nodeName, isRemoved));

            userSessionService.notifyUsers(
                    userSession.getUser().getId(),
                    String.format("Node '%s' was deleted by user '%s'", nodeName, userSession.getUser().getLogin())
            );
        } else {
            sendFail("Node is not found!");
        }
    }
}
