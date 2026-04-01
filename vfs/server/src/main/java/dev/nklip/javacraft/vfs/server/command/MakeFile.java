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
@Component("mkfile")
@RequiredArgsConstructor
public class MakeFile extends AbstractCommand implements Command {

    private final NodeService nodeService;
    private final UserSessionService userSessionService;

    @Override
    public void apply(UserSession userSession, CommandValues values) {
        clientWriter = userSession.getClientWriter();
        Node directory = userSession.getNode();
        String createNode = values.getNextParam();

        Node node = nodeService.getNode(directory, createNode);
        if (node == null) {
            node = nodeService.createNode(directory, createNode, NodeTypes.FILE);

            sendOK(String.format("New file '%s' was created!", nodeService.getFullPath(node)));
            userSessionService.notifyUsers(
                    userSession.getUser().getId(),
                    String.format("New file '%s' was created by user '%s'", nodeService.getFullPath(node), userSession.getUser().getLogin())
            );
        } else {
            sendFail("New file could not be created!");
        }
    }
}
