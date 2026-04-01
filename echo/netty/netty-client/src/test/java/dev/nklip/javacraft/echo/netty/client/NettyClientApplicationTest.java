package dev.nklip.javacraft.echo.netty.client;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class NettyClientApplicationTest {

    @Test
    void testGetPortWithValidArgument() {
        int port = NettyClientApplication.getPort(new String[]{"9090"});
        Assertions.assertEquals(9090, port);
    }

    @Test
    void testGetPortWithNoArgumentsFallsBackToDefault() {
        int port = NettyClientApplication.getPort(new String[0]);
        Assertions.assertEquals(NettyClientApplication.DEFAULT_PORT, port,
                "Missing port should fall back to DEFAULT_PORT");
    }

    @Test
    void testGetPortWithInvalidArgument() {
        int port = NettyClientApplication.getPort(new String[]{"notANumber"});
        Assertions.assertEquals(NettyClientApplication.DEFAULT_PORT, port,
                "Invalid port should fall back to DEFAULT_PORT");
    }

    @Test
    void testGetPortWithZero() {
        int port = NettyClientApplication.getPort(new String[]{"0"});
        Assertions.assertEquals(0, port);
    }

    @Test
    void testGetPortWithMaxPort() {
        int port = NettyClientApplication.getPort(new String[]{"65535"});
        Assertions.assertEquals(65535, port);
    }

    @Test
    void testGetPortWithNegativePortFallsBackToDefault() {
        int port = NettyClientApplication.getPort(new String[]{"-1"});
        Assertions.assertEquals(NettyClientApplication.DEFAULT_PORT, port,
                "Negative port should fall back to DEFAULT_PORT");
    }

    @Test
    void testGetPortWithPortAboveMaxFallsBackToDefault() {
        int port = NettyClientApplication.getPort(new String[]{"65536"});
        Assertions.assertEquals(NettyClientApplication.DEFAULT_PORT, port,
                "Port above 65535 should fall back to DEFAULT_PORT");
    }

}
