package dev.nklip.javacraft.echo.netty.server;

import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class NettyServerTest {

    @Test
    void testStartAndStop() throws InterruptedException {
        NettyServer server = new NettyServer(18076);
        server.start();
        server.stop();
    }

    @Test
    void testStartBindsToPort() throws InterruptedException, IOException {
        NettyServer server = new NettyServer(18077);
        server.start();
        try {
            // Verify the server is reachable on the expected port
            try (Socket socket = new Socket("localhost", 18077)) {
                Assertions.assertTrue(socket.isConnected());
            }
        } finally {
            server.stop();
        }
    }

    @Test
    void testServerAcceptsMultipleConnections() throws InterruptedException, IOException {
        NettyServer server = new NettyServer(18078);
        server.start();
        try {
            try (Socket s1 = new Socket("localhost", 18078);
                 Socket s2 = new Socket("localhost", 18078);
                 Socket s3 = new Socket("localhost", 18078)) {
                Assertions.assertTrue(s1.isConnected());
                Assertions.assertTrue(s2.isConnected());
                Assertions.assertTrue(s3.isConnected());
            }
        } finally {
            server.stop();
        }
    }

    @Test
    void testStopIsIdempotent() throws InterruptedException {
        NettyServer server = new NettyServer(18079);
        server.start();
        server.stop();
        // Calling stop again should not throw
        Assertions.assertDoesNotThrow(server::stop);
    }

    @Test
    void testStopBeforeStartDoesNotThrow() {
        NettyServer server = new NettyServer(18080);
        // stop() without start() should not throw
        Assertions.assertDoesNotThrow(server::stop);
    }

    @Test
    void testRunBlocksUntilServerChannelClosed() throws Exception {
        NettyServer server = new NettyServer(18090);
        CountDownLatch runStarted = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> {
                try {
                    runStarted.countDown();
                    server.run(); // blocks on closeFuture().sync()
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            // Wait for run() to start
            Assertions.assertTrue(runStarted.await(2, TimeUnit.SECONDS));
            // Give the server a moment to bind
            Thread.sleep(200);

            // Verify server is accepting connections while run() blocks
            try (Socket socket = new Socket("localhost", 18090)) {
                Assertions.assertTrue(socket.isConnected());
            }

            // Stopping the server closes the channel, which unblocks run()
            server.stop();

            executor.shutdown();
            Assertions.assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS),
                    "run() should have returned after stop() closed the server channel");
        }
    }

}
