package dev.nklip.vfs.server.command;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import dev.nklip.vfs.core.command.CommandValues;
import dev.nklip.vfs.server.model.Node;
import dev.nklip.vfs.server.model.NodeTypes;
import dev.nklip.vfs.server.model.UserSession;
import dev.nklip.vfs.server.service.NodeService;

/**
 * @author Lipatov Nikita
 */
@Component("cd")
@RequiredArgsConstructor
public class ChangeDirectory extends AbstractCommand implements Command {
    private final NodeService nodeService;

    public void apply(UserSession userSession, CommandValues values) {
        clientWriter = userSession.getClientWriter();
        Node directory = userSession.getNode();
        String source = values.getNextParam();

        if (source == null) {
            source = ".";
        }

        Node node = nodeService.getNode(directory, source);
        if (node != null) {
            if (node.getType() == NodeTypes.FILE) {
                sendFail("Destination node is file!");
            } else {
                userSession.setNode(node);
                sendOK(nodeService.getFullPath(node));
            }
        } else {
            sendFail("Destination node is not found!");
        }
    }
}
