package dev.nklip.javacraft.vfs.server.command;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import dev.nklip.javacraft.vfs.core.command.CommandValues;
import dev.nklip.javacraft.vfs.server.model.Node;
import dev.nklip.javacraft.vfs.server.model.NodeTypes;
import dev.nklip.javacraft.vfs.server.model.UserSession;
import dev.nklip.javacraft.vfs.server.service.LockService;
import dev.nklip.javacraft.vfs.server.service.NodeService;
import dev.nklip.javacraft.vfs.server.service.UserSessionService;

/**
 * @author Lipatov Nikita
 */
@Component("move")
@RequiredArgsConstructor
public class Move extends AbstractCommand implements Command {

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
            sendFail("Source path/node not found!");
            return;
        }

        if (destinationNode == null) {
            sendFail("Destination path/node not found!");
            return;
        }

        if (destinationNode.getType() == NodeTypes.DIR) {
            if (lockService.isLocked(destinationNode, true)) {
                sendFail("Node or children nodes is/are locked!");
                return;
            }

            Node parent = sourceNode.getParent();
            nodeService.getNodeManager().setParent(sourceNode, destinationNode);
            nodeService.getNodeManager().removeNode(parent, sourceNode);

            sendOK(getMessageToYou(sourceNode, destinationNode));

            userSessionService.notifyUsers(
                    userSession.getUser().getId(),
                    getMessageToAll(
                            userSession.getUser().getLogin(),
                            sourceNode,
                            destinationNode
                    )
            );
        } else {
            sendFail("Destination path is not directory");
        }
    }

    private String getMessageToYou(Node source, Node destination) {
        return String.format(
                "You has moved source node by path '%s' to destination node by path '%s'",
                nodeService.getFullPath(source),
                nodeService.getFullPath(destination)
        );
    }

    private String getMessageToAll(String login, Node source, Node destination) {
        return String.format(
                "User '%s' has moved source node by path '%s' to destination node by path '%s'",
                login,
                nodeService.getFullPath(source),
                nodeService.getFullPath(destination)
        );
    }
}
