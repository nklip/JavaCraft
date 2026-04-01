package dev.nklip.vfs.server.command;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import dev.nklip.vfs.core.command.CommandValues;
import dev.nklip.vfs.server.model.Node;
import dev.nklip.vfs.server.model.UserSession;
import dev.nklip.vfs.server.util.NodePrinter;

/**
 * @author Lipatov Nikita
 */
@Component("print")
@RequiredArgsConstructor
public class Print extends AbstractCommand implements Command {

    private final NodePrinter nodePrinter;

    @Override
    public void apply(UserSession userSession, CommandValues values) {
        clientWriter = userSession.getClientWriter();
        Node directory = userSession.getNode();

        sendOK(nodePrinter.print(directory));
    }
}
