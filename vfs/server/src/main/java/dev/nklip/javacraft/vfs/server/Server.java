package dev.nklip.javacraft.vfs.server;

import dev.nklip.javacraft.vfs.server.network.NettyServerInitializer;
import dev.nklip.javacraft.vfs.server.network.NetworkConfig;
import java.util.Map;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import dev.nklip.javacraft.vfs.server.command.Command;
import dev.nklip.javacraft.vfs.server.service.UserSessionService;

/**
 * Server class.
 *
 * @author Lipatov Nikita
 */
@Slf4j
@Component
public class Server implements Runnable {

    private final NetworkConfig networkConfig;
    private final UserSessionService userSessionService;
    private final CommandLine commandLine;

    @Autowired
    public Server(
            Environment environment,
            NetworkConfig networkConfig,
            UserSessionService userSessionService,
            Map<String, Command> commands) {
        this.networkConfig = networkConfig;
        this.userSessionService = userSessionService;
        this.commandLine = new CommandLine(commands);

        // W/A: do not start up netty if it's test profile
        if (Optional.of(environment.getActiveProfiles())
                .filter(ps -> ps.length > 0)
                .map(ps -> ps[0])
                .filter(p -> p.equalsIgnoreCase("test"))
                .isEmpty()) {
            run();
        }
    }

    public void run() {
        log.info("Server started.");

        String address = networkConfig.getAddress();
        int port = networkConfig.getPort();

        EventLoopGroup bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        EventLoopGroup workerGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());

        NettyServerInitializer initializer = new NettyServerInitializer(userSessionService, commandLine);

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .handler(new LoggingHandler(LogLevel.INFO))
                    .childHandler(initializer)
                    .bind(address, port)
                    .sync()
                    .channel()
                    .closeFuture()
                    .sync();
        } catch (Throwable e) {
            log.error("Server failure", e);
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }
}
