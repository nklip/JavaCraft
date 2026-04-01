package dev.nklip.javacraft.echo.selector.server;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/**
 * @author Lipatov Nikita
 */
@Slf4j
public class SelectorServerApplication {

    static final int DEFAULT_PORT = 8077;
    private static final int MIN_PORT = 0;
    private static final int MAX_PORT = 65_535;

    // telnet localhost 8077
    public static void main(String[] args) throws Exception {
        int port = getPort(args);

        SelectorServer server = new SelectorServer(port);

        // Without that hook, shutdown signals can leave SelectorServer stuck in selector.select() and not exit cleanly.
        //
        // server.stop() does two important things:
        //
        // 1) sets running=false
        // 2) calls selector.wakeup()
        //
        // That lets the event loop in SelectorServer.java break promptly instead of waiting forever on I/O readiness.
        //
        // So the hook in SelectorServerApplication.java ensures graceful stop on Ctrl+C, JVM termination, or container stop, and avoids hanging shutdown.
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));

        try (ExecutorService es = Executors.newSingleThreadExecutor()) {
            es.submit(server);

            es.shutdown();
            log.info("awaitTermination = '{}'",
                    es.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS)
            );
        }
    }

    static int getPort(String[] args) {
        if (args == null || args.length == 0) {
            return DEFAULT_PORT;
        }

        try {
            String rawPort = args[0];
            int port = Integer.parseInt(rawPort);
            if (port < MIN_PORT || port > MAX_PORT) {
                log.warn("Port '{}' is out of range, using default {}", rawPort, DEFAULT_PORT);
                return DEFAULT_PORT;
            }
            return port;
        } catch (NumberFormatException e) {
            log.warn("Invalid port '{}', using default {}", args[0], DEFAULT_PORT);
            return DEFAULT_PORT;
        }
    }

}
