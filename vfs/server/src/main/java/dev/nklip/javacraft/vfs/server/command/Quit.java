package dev.nklip.javacraft.vfs.server.command;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import dev.nklip.javacraft.vfs.core.command.CommandValues;
import dev.nklip.javacraft.vfs.core.exceptions.QuitException;
import dev.nklip.javacraft.vfs.core.network.protocol.Protocol.Response;
import dev.nklip.javacraft.vfs.server.model.UserSession;
import dev.nklip.javacraft.vfs.server.service.UserSessionService;

/**
 * @author Lipatov Nikita
 */
@Component("quit")
@RequiredArgsConstructor
public class Quit extends AbstractCommand implements Command {

    private final UserSessionService userSessionService;

    @Override
    public void apply(UserSession userSession, CommandValues values) {
        clientWriter = userSession.getClientWriter();
        String login = userSession.getUser().getLogin();

        userSessionService.stopSession(userSession.getUser().getId());

        send(Response.ResponseType.SUCCESS_QUIT, "You are disconnected from server!");

        userSessionService.notifyUsers(
                userSession.getUser().getId(),
                String.format("User '%s' has been disconnected", login)
        );

        throw new QuitException(
                String.format("User '%s' has been disconnected", login)
        );
    }

}
