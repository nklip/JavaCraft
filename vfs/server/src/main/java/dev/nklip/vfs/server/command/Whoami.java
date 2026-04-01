package dev.nklip.vfs.server.command;

import org.springframework.stereotype.Component;
import dev.nklip.vfs.core.command.CommandValues;
import dev.nklip.vfs.core.network.protocol.Protocol.User;
import dev.nklip.vfs.server.model.UserSession;

/**
 * @author Lipatov Nikita
 */
@Component("whoami")
public class Whoami extends AbstractCommand implements Command {

    @Override
    public void apply(UserSession userSession, CommandValues values) {
        clientWriter = userSession.getClientWriter();
        User user = userSession.getUser();

        sendOK(user.getLogin());
    }
}
