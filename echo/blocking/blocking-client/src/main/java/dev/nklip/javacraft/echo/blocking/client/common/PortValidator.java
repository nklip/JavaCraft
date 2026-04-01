package dev.nklip.javacraft.echo.blocking.client.common;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PortValidator {
    static final int DEFAULT_PORT = 8075;

    // CLI server should bind to an explicit user port, not an ephemeral OS-assigned one.
    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65_535;

    public static int getPort(String[] args) {
        if (args == null || args.length == 0) {
            return DEFAULT_PORT;
        }

        String rawPort = args[0];
        String normalizedPort = rawPort == null ? "" : rawPort.trim();
        if (normalizedPort.isEmpty()) {
            log.warn("Invalid port '{}', using default {}", rawPort, DEFAULT_PORT);
            return DEFAULT_PORT;
        }

        try {
            int port = Integer.parseInt(normalizedPort);
            if (port < MIN_PORT || port > MAX_PORT) {
                log.warn("Port '{}' is out of range [{}..{}], using default {}",
                        rawPort, MIN_PORT, MAX_PORT, DEFAULT_PORT);
                return DEFAULT_PORT;
            }
            return port;
        } catch (NumberFormatException e) {
            log.warn("Invalid port '{}', using default {}", rawPort, DEFAULT_PORT);
            return DEFAULT_PORT;
        }
    }
}
