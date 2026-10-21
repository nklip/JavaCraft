package dev.nklip.javacraft.shardshop.workload.producer.config;

import dev.nklip.javacraft.shardshop.workload.producer.load.OrderProducer;
import io.quarkus.runtime.configuration.DurationConverter;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.http.HttpClient;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ProducerConfigurationTest {
    @Test
    void defaultsAreBoundedAndCanConstructWithoutExternalServices() {
        var config = config("shardshop.producer.run-name", "stable");
        assertEquals(-1, config.rejectedOrderPercent());
        assertEquals(Duration.ofSeconds(60), config.orderDeadline());
        assertEquals(Duration.ofMinutes(5), config.startupDeadline());
        var producer = new ProducerWiring().producer(config, mock(HttpClient.class));
        producer.stop();
        assertFalse(producer.failed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"shardshop.producer.order-interval", "shardshop.producer.poll-interval",
            "shardshop.producer.order-deadline", "shardshop.producer.startup-deadline", "shardshop.producer.request-timeout"})
    void zeroDurationsFailBeforeHttp(String key) {
        assertThrows(IllegalArgumentException.class, () -> new ProducerWiring().producer(config(key, "0s"), mock()));
    }

    @Test
    void invalidRunNamesVersionsAndRetrySettingsFailBeforeHttp() {
        for (String runName : new String[]{"", " ", "\u00a0", "\u0085", "x\0y", "\ud800", "x".repeat(201)}) {
            assertThrows(IllegalArgumentException.class, () -> new OrderProducer.Settings(runName, 10,
                    Duration.ofSeconds(1), Duration.ofSeconds(60), Duration.ofMinutes(5)));
        }
        for (String[] entry : new String[][]{{"catalog-version", "missing"}, {"buyer-version", "missing"},
                {"rejected-order-percent", "101"}, {"max-attempts", "0"}, {"retry-backoff", "0s"},
                {"max-retry-backoff", "1ms"}, {"product-url", "http://host/?query"}}) {
            assertThrows(IllegalArgumentException.class, () -> new ProducerWiring().producer(
                    config("shardshop.producer." + entry[0], entry[1]), mock()));
        }
    }

    private ProducerConfiguration config(String key, String value) {
        return new SmallRyeConfigBuilder().withMapping(ProducerConfiguration.class)
                .withConverter(Duration.class, 100, new DurationConverter())
                .withDefaultValue("shardshop.producer.product-url", "http://127.0.0.1:1")
                .withDefaultValue("shardshop.producer.order-url", "http://127.0.0.1:1")
                .withDefaultValue("shardshop.producer.run-name", "stable")
                .withDefaultValue(key, value).build().getConfigMapping(ProducerConfiguration.class);
    }
}
