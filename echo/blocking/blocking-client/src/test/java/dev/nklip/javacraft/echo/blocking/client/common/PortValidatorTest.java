package dev.nklip.javacraft.echo.blocking.client.common;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class PortValidatorTest {

    @Test
    void testGetPortWithValidArgument() {
        int port = PortValidator.getPort(new String[]{"9090"});
        Assertions.assertEquals(9090, port);
    }

    @Test
    void testGetPortWithTrimmedValidArgument() {
        int port = PortValidator.getPort(new String[]{" 9090 "});
        Assertions.assertEquals(9090, port);
    }

    @Test
    void testGetPortWithNoArgumentsFallsBackToDefault() {
        int port = PortValidator.getPort(new String[0]);
        Assertions.assertEquals(PortValidator.DEFAULT_PORT, port,
                "Missing port should fall back to DEFAULT_PORT");
    }

    @Test
    void testGetPortWithInvalidArgument() {
        int port = PortValidator.getPort(new String[]{"notANumber"});
        Assertions.assertEquals(PortValidator.DEFAULT_PORT, port,
                "Invalid port should fall back to DEFAULT_PORT");
    }

    @Test
    void testGetPortWithZeroFallsBackToDefault() {
        int port = PortValidator.getPort(new String[]{"0"});
        Assertions.assertEquals(PortValidator.DEFAULT_PORT, port,
                "Port 0 should fall back to DEFAULT_PORT");
    }

    @Test
    void testGetPortWithMaxPort() {
        int port = PortValidator.getPort(new String[]{"65535"});
        Assertions.assertEquals(65535, port);
    }

    @Test
    void testGetPortWithNegativePortFallsBackToDefault() {
        int port = PortValidator.getPort(new String[]{"-1"});
        Assertions.assertEquals(PortValidator.DEFAULT_PORT, port,
                "Negative port should fall back to DEFAULT_PORT");
    }

    @Test
    void testGetPortWithPortAboveMaxFallsBackToDefault() {
        int port = PortValidator.getPort(new String[]{"65536"});
        Assertions.assertEquals(PortValidator.DEFAULT_PORT, port,
                "Port above 65535 should fall back to DEFAULT_PORT");
    }

    @Test
    void testGetPortWithBlankArgumentFallsBackToDefault() {
        int port = PortValidator.getPort(new String[]{"   "});
        Assertions.assertEquals(PortValidator.DEFAULT_PORT, port,
                "Blank port should fall back to DEFAULT_PORT");
    }

    @Test
    void testGetPortWithNullArgumentFallsBackToDefault() {
        int port = PortValidator.getPort(new String[]{null});
        Assertions.assertEquals(PortValidator.DEFAULT_PORT, port,
                "Null port argument should fall back to DEFAULT_PORT");
    }

}
