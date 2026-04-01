package dev.nklip.javacraft.vfs.server.command;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import dev.nklip.javacraft.vfs.core.command.CommandValues;
import dev.nklip.javacraft.vfs.core.exceptions.QuitException;
import dev.nklip.javacraft.vfs.core.network.protocol.Protocol.Response;
import dev.nklip.javacraft.vfs.core.network.protocol.ResponseFactory;
import dev.nklip.javacraft.vfs.server.model.UserSession;
import dev.nklip.javacraft.vfs.server.service.NodeService;
import dev.nklip.javacraft.vfs.server.service.UserSessionService;

/**
 * @author Lipatov Nikita
 */
@Component("connect")
@RequiredArgsConstructor
public class Connect extends AbstractCommand implements Command {

    private final NodeService nodeService;
    private final UserSessionService userSessionService;

    @Override
    public void apply(UserSession userSession, CommandValues values) {
        clientWriter = userSession.getClientWriter();
        String login = values.getNextParam();
        if (userSessionService.isLogged(login)) {
            send(Response.ResponseType.FAIL_CONNECT, "Such user already exits. Please, change the login!");

            throw new QuitException("Such user already exist!");
        } else {
            userSessionService.attachUser(userSession.getUser().getId(), login);
            clientWriter.send( // send id from server to client
                    ResponseFactory.newResponse(
                            Response.ResponseType.SUCCESS_CONNECT,
                            nodeService.getFullPath(userSession.getNode()),
                            userSession.getUser().getId()
                    )
            );
            userSessionService.notifyUsers(
                    userSession.getUser().getId(),
                    "User '" + login + "' has connected to server!"
            );
        }
    }
}
