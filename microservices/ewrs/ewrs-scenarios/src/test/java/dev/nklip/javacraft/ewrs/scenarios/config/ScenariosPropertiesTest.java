package dev.nklip.javacraft.ewrs.scenarios.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
@EnableConfigurationProperties(ScenariosProperties.class)
@TestPropertySource(properties = {
        "ewrs.scenarios.target-base-url=http://scenario-target:9000",
        "ewrs.scenarios.connect-timeout=3s",
        "ewrs.scenarios.read-timeout=12s",
        "ewrs.scenarios.projection-timeout=15s",
        "ewrs.scenarios.poll-interval=250ms"
})
class ScenariosPropertiesTest {

    @Autowired
    ScenariosProperties properties;

    @Test
    void testScenariosPropertiesBindFromProperties() {
        assertEquals("http://scenario-target:9000", properties.targetBaseUrl());
        assertEquals(Duration.ofSeconds(3), properties.connectTimeout());
        assertEquals(Duration.ofSeconds(12), properties.readTimeout());
        assertEquals(Duration.ofSeconds(15), properties.projectionTimeout());
        assertEquals(Duration.ofMillis(250), properties.pollInterval());
    }
}
