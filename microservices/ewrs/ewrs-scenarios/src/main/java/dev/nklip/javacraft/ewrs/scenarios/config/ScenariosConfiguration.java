package dev.nklip.javacraft.ewrs.scenarios.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Shared configuration for the standalone scenario driver.
 * It binds external properties and exposes the shared clock used in scenario execution responses.
 */
@Configuration
@EnableConfigurationProperties(ScenariosProperties.class)
public class ScenariosConfiguration {

    @Bean
    public Clock scenarioClock() {
        return Clock.systemUTC();
    }
}
