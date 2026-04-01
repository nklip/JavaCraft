package dev.nklip.vfs.server.command;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import dev.nklip.vfs.core.command.CommandValues;
import dev.nklip.vfs.server.model.Node;
import dev.nklip.vfs.server.model.NodeTypes;
import dev.nklip.vfs.server.model.UserSession;
import dev.nklip.vfs.server.service.LockService;
import dev.nklip.vfs.server.service.NodeService;
import dev.nklip.vfs.server.service.UserSessionService;

/**
 * @author Lipatov Nikita
 */
@Component("copy")
@RequiredArgsConstructor
public class Copy extends AbstractCommand implements Command {

    private final NodeService nodeService;
    private final LockService lockService;
    private final UserSessionService userSessionService;

    @Override
    public void apply(UserSession userSession, CommandValues values) {
        clientWriter = userSession.getClientWriter();
        Node directory = userSession.getNode();
        String source = values.getNextParam();
        String destination = values.getNextParam();

        Node sourceNode = nodeService.getNode(directory, source);
        Node destinationNode = nodeService.getNode(directory, destination);

        if (sourceNode == null) {
            sendFail("Source path/node is not found!");
            return;
        }
        if (destinationNode == null) {
            sendFail("Destination path/node is not found!");
            return;
        }

        if (destinationNode.getType() == NodeTypes.DIR) {

            if (lockService.isLocked(destinationNode, true)) {
                sendOK("Node or children nodes is/are locked!");
                return;
            }

            Node copyNode = nodeService.clone(sourceNode);
            nodeService.getNodeManager().setParent(copyNode, destinationNode);
            sendOK(getMessageToYou(sourceNode, destinationNode));

            userSessionService.notifyUsers(
                    userSession.getUser().getId(),
                    getMessageToAll(userSession.getUser().getLogin(), sourceNode, destinationNode)
            );
        } else {
            sendFail("Destination path is not directory");
        }
    }

    private String getMessageToYou(Node source, Node destination) {
        return String.format(
                "You has copied node by path '%s' to destination node by path '%s'",
                nodeService.getFullPath(source),
                nodeService.getFullPath(destination)
        );
    }

    private String getMessageToAll(String login, Node source, Node destination) {
        return String.format(
                "User '%s' has copied node by path '%s' to destination node by path '%s'",
                login,
                nodeService.getFullPath(source),
                nodeService.getFullPath(destination)
        );
    }
}
