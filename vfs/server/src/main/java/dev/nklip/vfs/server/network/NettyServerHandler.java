package dev.nklip.vfs.server.network;

import io.netty.channel.*;
import dev.nklip.vfs.core.VFSConstants;
import dev.nklip.vfs.core.exceptions.QuitException;
import dev.nklip.vfs.core.network.protocol.Protocol.Request;
import dev.nklip.vfs.core.network.protocol.Protocol.Response;
import dev.nklip.vfs.server.CommandLine;
import dev.nklip.vfs.server.model.Timer;
import dev.nklip.vfs.server.model.UserSession;
import dev.nklip.vfs.server.service.UserSessionService;

/**
 * @author Lipatov Nikita
 */
public class NettyServerHandler extends SimpleChannelInboundHandler<Request> {

    private final UserSessionService userSessionService;
    private final CommandLine commandLine;

    private String userId;

    public NettyServerHandler(UserSessionService userSessionService, CommandLine commandLine) {
        super();
        this.userSessionService = userSessionService;
        this.commandLine = commandLine;
    }

    // Generate and write a response.
    @Override
    public void channelRead0(ChannelHandlerContext ctx, Request request) {
        UserSession userSession;
        if(request.getUser().getId().equals(VFSConstants.NEW_USER)) {
            userSession = userSessionService.startSession(new ClientWriter(ctx, this), new Timer());

            userId = userSession.getUser().getId();
        } else {
            userSession = userSessionService.getSession(request.getUser().getId());
        }

        userSession.getTimer().updateTime();

        try {
            commandLine.onUserInput(userSession, request); // QuitException can be thrown here
        } catch(QuitException qe) {
            ctx.close();
        }
    }

    public void sendBack(ChannelHandlerContext ctx, Response response) {
        ctx.writeAndFlush(response);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
    }

    // Close the connection when an exception is raised.
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        //cause.printStackTrace();
        userSessionService.notifyUsers(
                userId,
                String.format(
                        "User '%s' has been disconnected",
                        userSessionService.getSession(userId).getUser().getLogin()
                )
        );
        userSessionService.stopSession(userId);
        ctx.close();
    }
}
