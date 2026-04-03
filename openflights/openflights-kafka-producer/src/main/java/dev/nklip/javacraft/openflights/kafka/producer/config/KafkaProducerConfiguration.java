package dev.nklip.javacraft.openflights.kafka.producer.config;

import static org.springframework.kafka.support.serializer.JsonSerializer.ADD_TYPE_INFO_HEADERS;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * Spring Kafka producer wiring for OpenFlights message publishing.
 *
 * <p>Why this configuration exists:
 *
 * <p>The producer side of OpenFlights reads typed records from the source datasets and publishes them to Kafka topics.
 * Those records are not sent as raw text lines from the original {@code .dat} files. By the time the producer publishes
 * them, they are already modeled as shared API objects such as countries, airlines, airports, planes, and routes.
 *
 * <p>This class centralizes how those Java objects are turned into Kafka producer clients and how they are serialized
 * on the wire. Without this configuration, low-level producer setup would be repeated anywhere the application needs to
 * send records, which would make the producer harder to reason about and easier to misconfigure.
 *
 * <p>Main responsibilities of this class:
 *
 * <p>1. build the Kafka producer configuration from the application properties
 * <p>2. define how Kafka keys and values are serialized
 * <p>3. enable reliability-related producer options
 * <p>4. expose a shared {@link KafkaTemplate} that application services can use to publish records
 *
 * <p>Why the value serializer is JSON:
 *
 * <p>The producer publishes shared OpenFlights API objects, not primitive strings. JSON is a straightforward transport
 * format here because:
 * - it works naturally with the shared record classes in {@code openflights-api}
 * - Spring Kafka can serialize and deserialize those types directly
 * - it keeps the producer and consumer loosely coupled at the transport level
 *
 * <p>Why keys are strings:
 *
 * <p>The producer services derive stable business keys for each payload type, such as airline ids, airport ids, or
 * route keys. Kafka uses those keys for partitioning and ordering. A string serializer keeps that partitioning logic
 * simple and consistent across all entity types.
 *
 * <p>Why idempotence and {@code acks=all} are enabled:
 *
 * <p>OpenFlights ingestion can publish a large number of records, especially for routes. Reliability matters more than
 * raw benchmark throughput, so the producer is configured to favor safe delivery:
 * - {@code acks=all} asks Kafka to confirm writes with the strongest acknowledgement level available
 * - idempotence reduces the risk of duplicate records being produced by client retries
 *
 * <p>Why type headers are disabled:
 *
 * <p>This project uses topic-per-entity semantics. The topic name already tells the consumer what payload type to
 * expect, and the consumer configuration binds each topic to a concrete target class. Because of that, extra Jackson
 * type headers are unnecessary here and would only add more transport metadata than the consumer actually needs.
 *
 * <p>Why this class returns a shared {@link KafkaTemplate}:
 *
 * <p>Application services should focus on deciding what to publish and with which key, not on building Kafka producer
 * clients. The shared template created here gives the rest of the producer module one consistent entry point for
 * sending records.
 *
 * <p>In short, this class is the producer-side wiring hub: it keeps Kafka client mechanics in one place so the rest of
 * the producer module can focus on reading OpenFlights data and publishing typed records.
 */
@Configuration
public class KafkaProducerConfiguration {

    @Value(value = "${spring.kafka.bootstrap-servers}")
    private String bootstrapAddress;

    /**
     * To create messages, we first need to configure a ProducerFactory.
     * This sets the strategy for creating Kafka Producer instances.
     **/
    @Bean
    public ProducerFactory<String, Object> producerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapAddress);
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        configProps.put(ProducerConfig.ACKS_CONFIG, "all");
        configProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        // Topic names already define the payload type, so consumers don't need type headers.
        configProps.put(ADD_TYPE_INFO_HEADERS, false);
        return new DefaultKafkaProducerFactory<>(configProps);
    }

    /**
     * Then we need a KafkaTemplate, which wraps a Producer instance
     * and provides convenience methods for sending messages to Kafka topics.
     * =========
     * Producer instances are thread safe.
     * So, using a single instance throughout an application context will give higher performance.
     * KakfaTemplate instances are also thread safe, and use of one instance is recommended.
     **/
    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }
}
