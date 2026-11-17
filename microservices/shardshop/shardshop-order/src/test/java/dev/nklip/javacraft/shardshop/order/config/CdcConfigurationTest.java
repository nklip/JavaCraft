package dev.nklip.javacraft.shardshop.order.config;

import io.quarkus.runtime.configuration.DurationConverter;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CdcConfigurationTest {
    @Test
    void pollIntervalDefaultsToTheExistingConnectorDelay() {
        assertEquals(Duration.ofMillis(500), builder().build()
                .getConfigMapping(CdcConfiguration.class).pollInterval());
    }

    @Test
    void pollIntervalCanBeConfiguredUsingTheCdcPrefix() {
        assertEquals(Duration.ofMillis(100), builder()
                .withDefaultValue("shardshop.order.cdc.poll-interval", "100ms").build()
                .getConfigMapping(CdcConfiguration.class).pollInterval());
    }

    private static SmallRyeConfigBuilder builder() {
        return new SmallRyeConfigBuilder().withMapping(CdcConfiguration.class)
                .withConverter(Duration.class, 100, new DurationConverter());
    }
}
