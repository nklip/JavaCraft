package dev.nklip.javacraft.ewrs.scenarios.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * External configuration for the standalone scenario driver.
 * It exists so the module can run against a local EWRS app by default while still being easy to retarget in tests.
 */
@ConfigurationProperties("ewrs.scenarios")
public record ScenariosProperties(
        @DefaultValue("http://localhost:8053") String targetBaseUrl,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("10s") Duration readTimeout,
        @DefaultValue("10s") Duration projectionTimeout,
        @DefaultValue("100ms") Duration pollInterval
) {
}
