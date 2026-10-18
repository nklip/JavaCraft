package dev.nklip.javacraft.shardshop.order.cdc;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class OrderCdcRelayTest {
    /** An operator sees why a reader restarts without reading a stack trace. */
    @Test
    void retryLogNamesTheCauseChainOnOneBoundedLine() {
        var failure = new IllegalStateException("CDC batch stopped before checkpoint",
                new IOException("RabbitMQ returned unroutable command publication"));
        assertEquals("IllegalStateException: CDC batch stopped before checkpoint <- "
                + "IOException: RabbitMQ returned unroutable command publication", OrderCdcRelay.describe(failure));

        assertEquals("IllegalStateException: first line second line",
                OrderCdcRelay.describe(new IllegalStateException("first line\n  second line")));
        assertEquals("IllegalStateException", OrderCdcRelay.describe(new IllegalStateException()));

        String described = OrderCdcRelay.describe(new IllegalStateException("x".repeat(1_000)));
        assertEquals("IllegalStateException: " + "x".repeat(300) + "...", described);

        var cycle = new IllegalStateException("outer");
        var inner = new IllegalArgumentException("inner", cycle);
        cycle.initCause(inner);
        assertEquals("IllegalStateException: outer <- IllegalArgumentException: inner", OrderCdcRelay.describe(cycle));
    }
}
