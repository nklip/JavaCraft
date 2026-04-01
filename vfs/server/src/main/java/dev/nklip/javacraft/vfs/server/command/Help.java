package dev.nklip.javacraft.vfs.server.command;

import org.springframework.stereotype.Component;
import dev.nklip.javacraft.vfs.core.command.CommandValues;
import dev.nklip.javacraft.vfs.server.model.UserSession;

/**
 * @author Lipatov Nikita
 */
@Component("help")
public class Help extends AbstractCommand implements Command {

    @Override
    public void apply(UserSession userSession, CommandValues values) {
        clientWriter = userSession.getClientWriter();
        sendOK("""
                You can use next commands:
                    * - cd directory\s
                    * - connect server_name:port login\s
                    * - copy node directory\s
                    * - help\s
                    * - lock [-r] node\s
                        -r - enable recursive mode\s
                    * - mkdir directory\s
                    * - mkfile file
                    * - move node directory\s
                    * - print\s
                    * - quit\s
                    * - rename node name\s
                    * - rm node\s
                    * - unlock [-r] node\s
                        -r - enable recursive mode\s
                """
        );
    }
}
