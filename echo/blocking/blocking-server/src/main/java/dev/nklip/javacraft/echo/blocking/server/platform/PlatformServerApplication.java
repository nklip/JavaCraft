package dev.nklip.javacraft.echo.blocking.server.platform;

import dev.nklip.javacraft.echo.blocking.server.common.PortValidator;

/**
 * @author Lipatov Nikita
 */
public class PlatformServerApplication {
    // telnet localhost 8075
    public static void main(String[] args) {
        int port = PortValidator.getPort(args);

        try (PlatformServer server = new PlatformServer(port)) {
            server.run();
        }
    }
}
