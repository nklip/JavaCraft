package dev.nklip.javacraft.shardshop.workload.producer.config;

import dev.nklip.javacraft.shardshop.workload.producer.load.OrderProducer;
import io.quarkus.runtime.configuration.DurationConverter;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ProducerConfigurationTest {
    @Test
    void defaultsAreBoundedAndCanConstructWithoutExternalServices() {
        var config = config("shardshop.producer.run-name", "stable");
        assertEquals(-1, config.rejectedOrderPercent());
        assertEquals(4, config.submissionConcurrency());
        assertEquals(2, config.observerConcurrency());
        assertEquals(100, config.maxNonTerminalOrders());
        assertEquals(Duration.ofSeconds(60), config.orderDeadline());
        assertEquals(Duration.ofMinutes(5), config.startupDeadline());
        var wiring = new ProducerWiring();
        var control = wiring.control(config);
        assertEquals("0.5", control.status().targetRate().toPlainString());
        assertEquals("WAITING", control.status().state());
        var producer = wiring.producer(config, mock(HttpClient.class), control);
        producer.stop();
        assertFalse(producer.failed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "30.001", "0.0001", "1E+3"})
    void invalidOrderRatesFailAtStartup(String rate) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new ProducerWiring().control(config("shardshop.producer.order-rate", rate)));
        assertEquals("shardshop.producer.order-rate must be 0..30 with at most three decimals", failure.getMessage());
        assertEquals("0", new ProducerWiring().control(config("shardshop.producer.order-rate", "0.000"))
                .status().targetRate().toPlainString(), "Zero starts paused");
    }

    @ParameterizedTest
    @ValueSource(strings = {"shardshop.producer.poll-interval",
            "shardshop.producer.order-deadline", "shardshop.producer.startup-deadline", "shardshop.producer.request-timeout"})
    void zeroDurationsFailBeforeHttp(String key) {
        var config = config(key, "0s");
        assertThrows(IllegalArgumentException.class, () -> new ProducerWiring().producer(config, mock(),
                new ProducerWiring().control(config)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "13"})
    void invalidConcurrencyFailsBeforeHttp(String value) {
        for (String key : List.of("submission-concurrency", "observer-concurrency")) {
            var config = config("shardshop.producer." + key, value);
            assertThrows(IllegalArgumentException.class, () -> new ProducerWiring().producer(config, mock(),
                    new ProducerWiring().control(config)));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "3", "10001"})
    void invalidBacklogFailsBeforeHttp(String value) {
        var config = config("shardshop.producer.max-non-terminal-orders", value);
        assertThrows(IllegalArgumentException.class, () -> new ProducerWiring().producer(config, mock(),
                new ProducerWiring().control(config)));
    }

    @Test
    void invalidRunNamesVersionsAndRetrySettingsFailBeforeHttp() {
        for (String runName : new String[]{"", " ", "\u00a0", "\u0085", "x\0y", "\ud800", "x".repeat(201)}) {
            assertThrows(IllegalArgumentException.class, () -> new OrderProducer.Settings(runName, 10,
                    Duration.ofSeconds(60), Duration.ofMinutes(5)));
        }
        for (String[] entry : new String[][]{{"catalog-version", "missing"}, {"buyer-version", "missing"},
                {"rejected-order-percent", "101"}, {"max-attempts", "0"}, {"retry-backoff", "0s"},
                {"max-retry-backoff", "1ms"}, {"product-url", "http://host/?query"}}) {
            var config = config("shardshop.producer." + entry[0], entry[1]);
            assertThrows(IllegalArgumentException.class, () -> new ProducerWiring().producer(config, mock(),
                    new ProducerWiring().control(config)));
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
