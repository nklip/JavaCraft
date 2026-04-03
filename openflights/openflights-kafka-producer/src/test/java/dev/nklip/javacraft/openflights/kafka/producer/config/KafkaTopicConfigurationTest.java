package dev.nklip.javacraft.openflights.kafka.producer.config;

import dev.nklip.javacraft.openflights.api.kafka.OpenFlightsTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaAdmin;

class KafkaTopicConfigurationTest {

    @Test
    void routeTopicUsesConfiguredPartitionCount() {
        KafkaTopicConfiguration configuration = new KafkaTopicConfiguration("localhost:9092", 8);

        NewTopic topic = configuration.routeTopic();

        Assertions.assertEquals(OpenFlightsTopics.ROUTE, topic.name());
        Assertions.assertEquals(8, topic.numPartitions());
        Assertions.assertEquals((short) 1, topic.replicationFactor());
    }

    @Test
    void kafkaAdminUsesConfiguredBootstrapServers() {
        KafkaTopicConfiguration configuration = new KafkaTopicConfiguration("localhost:9092", 8);

        KafkaAdmin kafkaAdmin = configuration.kafkaAdmin();

        Assertions.assertEquals("localhost:9092",
                kafkaAdmin.getConfigurationProperties().get("bootstrap.servers"));
    }
}
