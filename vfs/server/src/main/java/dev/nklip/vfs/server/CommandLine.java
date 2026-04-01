package dev.nklip.vfs.server;

import dev.nklip.vfs.core.command.CommandParser;
import dev.nklip.vfs.core.command.CommandValues;
import dev.nklip.vfs.core.network.protocol.Protocol.Request;
import dev.nklip.vfs.core.network.protocol.Protocol.Response;
import dev.nklip.vfs.core.network.protocol.ResponseFactory;
import dev.nklip.vfs.server.command.Command;
import dev.nklip.vfs.server.network.ClientWriter;
import dev.nklip.vfs.server.model.UserSession;

import java.util.Map;

/**
 * @author Lipatov Nikita
 */
public class CommandLine {

    private final Map<String, Command> commands;
    private volatile ClientWriter clientWriter;

    private final CommandParser parser;

    public CommandLine(Map<String, Command> commands) {
        this.commands = commands;

        parser = new CommandParser();
    }

    public void onUserInput(UserSession userSession, Request request) {
        clientWriter = userSession.getClientWriter();

        String fullCommand = request.getCommand();
        parser.parse(fullCommand);
        CommandValues commandValues = parser.getCommandValues();

        String command = commandValues.getCommand();

        try {
            if (commands.containsKey(command)) {
                commands.get(command).apply(userSession, commandValues);
            } else {
                clientWriter.send(
                        ResponseFactory.newResponse(
                                Response.ResponseType.OK,
                                "No such command! Please check you syntax or type 'help'!"
                        )
                );
            }
        } catch(IllegalArgumentException | IllegalAccessError | NullPointerException e) {
            clientWriter.send(
                    ResponseFactory.newResponse(
                            Response.ResponseType.FAIL,
                            e.getMessage()
                    )
            );
        }
    }

}

