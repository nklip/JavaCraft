package dev.nklip.javacraft.echo.blocking.server.virtual;

import lombok.extern.slf4j.Slf4j;
import dev.nklip.javacraft.echo.blocking.server.common.PortValidator;

/**
 * @author Lipatov Nikita
 */
@Slf4j
public class VirtualServerApplication {

    // telnet localhost 8075
    public static void main(String[] args) {
        int port = PortValidator.getPort(args);

        try (VirtualServer server = new VirtualServer(port)) {
            server.run();
        }
    }

}
