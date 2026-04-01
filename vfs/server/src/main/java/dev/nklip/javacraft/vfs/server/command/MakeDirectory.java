package dev.nklip.javacraft.vfs.server.command;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import dev.nklip.javacraft.vfs.core.command.CommandValues;
import dev.nklip.javacraft.vfs.server.model.Node;
import dev.nklip.javacraft.vfs.server.model.NodeTypes;
import dev.nklip.javacraft.vfs.server.model.UserSession;
import dev.nklip.javacraft.vfs.server.service.NodeService;
import dev.nklip.javacraft.vfs.server.service.UserSessionService;

/**
 * @author Lipatov Nikita
 */
@Component("mkdir")
@RequiredArgsConstructor
public class MakeDirectory extends AbstractCommand implements Command {

    private final NodeService nodeService;
    private final UserSessionService userSessionService;

    @Override
    public void apply(UserSession userSession, CommandValues values) {
        clientWriter = userSession.getClientWriter();
        Node directory = userSession.getNode();
        String newNodeName = values.getNextParam();

        Node node = nodeService.getNode(directory, newNodeName);
        if (node == null) {
            node = nodeService.createNode(directory, newNodeName, NodeTypes.DIR);
            sendOK(String.format("New directory '%s' was created!", nodeService.getFullPath(node)));
            userSessionService.notifyUsers(
                    userSession.getUser().getId(),
                    String.format("New directory '%s' was created by user '%s'", nodeService.getFullPath(node), userSession.getUser().getLogin())
            );
        } else {
            sendFail("New directory could not be created!");
        }
    }
}
