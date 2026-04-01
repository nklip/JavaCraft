package dev.nklip.vfs.server.command;

import dev.nklip.vfs.core.command.CommandValues;
import dev.nklip.vfs.server.model.UserSession;

/**
 * @author Lipatov Nikita
 */
public interface Command {

    void apply(UserSession userSession, CommandValues values);
}
