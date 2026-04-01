package dev.nklip.javacraft.echo.blocking.client.virtual;

import lombok.extern.slf4j.Slf4j;
import dev.nklip.javacraft.echo.blocking.client.common.PortValidator;

/**
 * @author Lipatov Nikita
 */
@Slf4j
public class VirtualClientApplication {

    // telnet localhost 8075
    public static void main(String[] args) {
        int port = PortValidator.getPort(args);

        try (VirtualThreadClient syncClient = new VirtualThreadClient(
                "Sync-client-application",
                "localhost",
                port)) {
            syncClient.run();
        }
    }
}
