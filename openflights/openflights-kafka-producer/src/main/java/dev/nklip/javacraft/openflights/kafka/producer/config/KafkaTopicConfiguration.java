package dev.nklip.javacraft.openflights.kafka.producer.config;

import dev.nklip.javacraft.openflights.api.kafka.OpenFlightsTopics;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;


/**
 * Declares the Kafka topics used by the OpenFlights producer/consumer flow.
 *
 * <p>Why this configuration exists:
 *
 * <p>The OpenFlights application uses topic-per-entity semantics. Instead of publishing every payload into one generic
 * Kafka topic, it publishes countries, airlines, airports, planes, and routes to separate topics. That separation is
 * intentional because each dataset has different volume, different processing cost, and different consumer behavior.
 *
 * <p>This class centralizes the topic declarations so the application can create the expected Kafka topics
 * programmatically at startup through Spring Kafka's {@link KafkaAdmin}. Without this configuration, the local/dev/test
 * environments would depend on manually pre-creating topics or on Kafka auto-creating them with whatever defaults the
 * broker happens to use.
 *
 * <p>Main responsibilities of this class:
 *
 * <p>1. provide a configured {@link KafkaAdmin} so the application can talk to Kafka's admin API
 * <p>2. declare one {@link NewTopic} bean per logical OpenFlights topic
 * <p>3. keep partition-count decisions in one place instead of scattering them across code and infrastructure scripts
 *
 * <p>Why topic creation is done in code:
 *
 * <p>Keeping topic definitions in application configuration makes the expected Kafka shape explicit and versioned with
 * the code. That is especially useful in this project because:
 * - topic names are part of the application contract
 * - tests spin up Kafka dynamically and should not rely on manual setup
 * - local development should start with predictable topic settings
 *
 * <p>Why most topics use a single partition:
 *
 * <p>The country, airline, airport, and plane datasets are relatively smaller and lighter to consume. Their primary
 * need is correctness and straightforward ordering rather than high write throughput. A single partition keeps those
 * topics simple and avoids unnecessary parallelism for datasets that do not benefit much from it.
 *
 * <p>Why the route topic is different:
 *
 * <p>Routes are the heaviest dataset by far. Route ingestion publishes a much larger number of records, and the
 * consumer side does more work per route because it also persists route equipment rows and resolves route references.
 * Because of that, the route topic is allowed to use a larger, configurable partition count.
 *
 * <p>The route partition count is a throughput tuning knob:
 * - higher values allow more parallel route consumption
 * - lower values favor simpler ordering and easier debugging
 *
 * <p>That is why the route topic does not share the same fixed partition count as the smaller entity topics.
 *
 * <p>Another important point is that this class only defines topic shape. It does not decide how records are keyed or
 * how consumers process them. Those concerns live in the producer and consumer configurations. This class simply says
 * "these are the topics the application expects to exist, and this is how many partitions each one should have."
 *
 * <p>In short, this class is the Kafka topology declaration for OpenFlights: it makes the topic layout explicit,
 * repeatable, and aligned with the relative size and behavior of each dataset.
 */
@Configuration
public class KafkaTopicConfiguration {

    private static final int DEFAULT_TOPIC_PARTITIONS = 1;
    private static final int DEFAULT_ROUTE_TOPIC_PARTITIONS = 8;

    private final String bootstrapAddress;
    private final int routeTopicPartitions;

    public KafkaTopicConfiguration(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapAddress,
            @Value("${openflights.kafka.route-topic-partitions:" + DEFAULT_ROUTE_TOPIC_PARTITIONS + "}") int routeTopicPartitions) {
        this.bootstrapAddress = bootstrapAddress;
        this.routeTopicPartitions = routeTopicPartitions;
    }

    /**
     * AdminClient allows to us to create topics programmatically.
     */
    @Bean
    public KafkaAdmin kafkaAdmin() {
        Map<String, Object> configs = new HashMap<>();
        configs.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapAddress);
        return new KafkaAdmin(configs);
    }

    @Bean
    public NewTopic defaultTopic() {
        return new NewTopic(OpenFlightsTopics.DEFAULT, DEFAULT_TOPIC_PARTITIONS, (short) 1);
    }

    @Bean
    public NewTopic countryTopic() {
        return new NewTopic(OpenFlightsTopics.COUNTRY, DEFAULT_TOPIC_PARTITIONS, (short) 1);
    }

    @Bean
    public NewTopic airlineTopic() {
        return new NewTopic(OpenFlightsTopics.AIRLINE, DEFAULT_TOPIC_PARTITIONS, (short) 1);
    }

    @Bean
    public NewTopic airportTopic() {
        return new NewTopic(OpenFlightsTopics.AIRPORT, DEFAULT_TOPIC_PARTITIONS, (short) 1);
    }

    @Bean
    public NewTopic planeTopic() {
        return new NewTopic(OpenFlightsTopics.PLANE, DEFAULT_TOPIC_PARTITIONS, (short) 1);
    }

    @Bean
    public NewTopic routeTopic() {
        return new NewTopic(OpenFlightsTopics.ROUTE, routeTopicPartitions, (short) 1);
    }
}
