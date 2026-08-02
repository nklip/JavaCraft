package dev.nklip.javacraft.ewrs.app.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Provides the shared clock used by EWRS when it timestamps events and projection work.
 * Architecture mapping: invoked during startup so the Write Side and Projection Flow use one injectable time source.
 */
@Configuration
public class ClockConfiguration {

    @Bean
    @Primary
    public Clock ewrsClock() {
        return Clock.systemUTC();
    }
}
