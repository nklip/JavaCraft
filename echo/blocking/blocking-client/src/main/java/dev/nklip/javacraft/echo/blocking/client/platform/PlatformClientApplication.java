package dev.nklip.javacraft.echo.blocking.client.platform;

import lombok.extern.slf4j.Slf4j;
import dev.nklip.javacraft.echo.blocking.client.common.PortValidator;

/**
 * @author Lipatov Nikita
 */
@Slf4j
public class PlatformClientApplication {

    // telnet localhost 8075
    public static void main(String[] args) {
        int port = PortValidator.getPort(args);

        try (PlatformThreadClient asyncClient = new PlatformThreadClient(
                "Async-client-application",
                "localhost",
                port)) {
            asyncClient.run();
        }
    }
}
