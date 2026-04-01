package dev.nklip.javacraft.vfs.server.command;

import dev.nklip.javacraft.vfs.core.command.CommandValues;
import dev.nklip.javacraft.vfs.server.model.UserSession;

/**
 * @author Lipatov Nikita
 */
public interface Command {

    void apply(UserSession userSession, CommandValues values);
}
