package dev.nklip.javacraft.shardshop.workload.reader.config;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The lab sets only the product URL of the reader, thus the deployed reader uses these defaults. They must be the time
 * limits of the HTTP connection policy in README.md. The client tests give their own durations to
 * ProductRequests, and the reader scenario of shardshop-testing checks the connections and the request rate.
 */
@QuarkusTest
class ReaderConfigurationTest {
    @Inject
    ReaderConfiguration configuration;

    @Test
    void defaultsAreTheTimeLimitsOfTheHttpConnectionPolicy() {
        assertFalse(configuration.diagnostics().enabled(), "Scheduling diagnostics are opt-in");
        assertEquals(Duration.ofSeconds(2), configuration.connectTimeout(), "Connect timeout");
        assertEquals(Duration.ofSeconds(5), configuration.requestDeadline(), "Request deadline");
        assertEquals(Duration.ofSeconds(30), configuration.maxConnectionLifetime(), "Connection lifetime");
        assertEquals(Duration.ofSeconds(10), configuration.idleTimeout(), "Idle timeout");
    }
}
