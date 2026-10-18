package dev.nklip.javacraft.shardshop.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class FailuresTest {
    /** An operator sees why a background worker retries without reading a stack trace. */
    @Test
    void describeNamesTheCauseChainOnOneBoundedLine() {
        var failure = new IllegalStateException("CDC batch stopped before checkpoint",
                new IOException("RabbitMQ returned unroutable command publication"));
        assertEquals("IllegalStateException: CDC batch stopped before checkpoint <- "
                + "IOException: RabbitMQ returned unroutable command publication", Failures.describe(failure));

        assertEquals("IllegalStateException: first line second line",
                Failures.describe(new IllegalStateException("first line\n  second line")));
        assertEquals("IllegalStateException", Failures.describe(new IllegalStateException()));

        String described = Failures.describe(new IllegalStateException("x".repeat(1_000)));
        assertEquals("IllegalStateException: " + "x".repeat(300) + "...", described);

        var cycle = new IllegalStateException("outer");
        var inner = new IllegalArgumentException("inner", cycle);
        cycle.initCause(inner);
        assertEquals("IllegalStateException: outer <- IllegalArgumentException: inner", Failures.describe(cycle));
    }
}
