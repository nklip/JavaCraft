package dev.nklip.javacraft.openflights.kafka.consumer.config;

import dev.nklip.javacraft.openflights.api.Country;
import java.time.Duration;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.Consumer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.core.ConsumerFactory;

class KafkaConsumerConfigurationTest {

    @Test
    void testCountryConsumerFactoryCreatesConsumerWithoutDeserializerConflict() {
        KafkaConsumerConfiguration configuration = new KafkaConsumerConfiguration("localhost:9092", 8);
        ConsumerFactory<String, Country> consumerFactory = configuration.countryConsumerFactory();

        Assertions.assertEquals(
                "earliest",
                consumerFactory.getConfigurationProperties().get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG));

        Consumer<String, Country> consumer = Assertions.assertDoesNotThrow(
                () -> consumerFactory.createConsumer("test-group", "test-client"));

        Assertions.assertNotNull(consumer);
        consumer.close(Duration.ZERO);
    }

    @Test
    void testRouteKafkaListenerContainerFactoryUsesConfiguredConcurrency() {
        KafkaConsumerConfiguration configuration = new KafkaConsumerConfiguration("localhost:9092", 6);

        ConcurrentMessageListenerContainer<String, ?> container =
                configuration.routeKafkaListenerContainerFactory().createContainer("openflights.route.test");

        Assertions.assertEquals(6, container.getConcurrency());
    }
}
