package dev.nklip.vfs.server.command;

import dev.nklip.vfs.core.network.protocol.Protocol.Response.ResponseType;
import dev.nklip.vfs.server.network.ClientWriter;

import static dev.nklip.vfs.core.network.protocol.ResponseFactory.newResponse;

/**
 * @author Lipatov Nikita
 */
public abstract class AbstractCommand {

    protected volatile ClientWriter clientWriter;

    public void send(ResponseType status, String message) {
        clientWriter.send(
                newResponse(
                        status,
                        message
                )
        );
    }

    public void sendOK(String message) {
        clientWriter.send(
                newResponse(
                        ResponseType.OK,
                        message
                )
        );
    }

    public void sendFail(String message) {
        clientWriter.send(
                newResponse(
                        ResponseType.FAIL,
                        message
                )
        );
    }
}
