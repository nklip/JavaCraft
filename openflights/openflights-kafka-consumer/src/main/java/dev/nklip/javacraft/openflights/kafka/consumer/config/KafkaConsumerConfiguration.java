package dev.nklip.javacraft.openflights.kafka.consumer.config;

import dev.nklip.javacraft.openflights.api.Airline;
import dev.nklip.javacraft.openflights.api.Airport;
import dev.nklip.javacraft.openflights.api.Country;
import dev.nklip.javacraft.openflights.api.Plane;
import dev.nklip.javacraft.openflights.api.Route;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Spring Kafka consumer wiring for all OpenFlights entity topics.
 *
 * <p>Why this configuration exists:
 *
 * <p>The consumer side of OpenFlights does not read one generic "message" type and figure everything out at runtime.
 * It consumes several distinct Kafka topics whose payloads are already modeled as typed API records:
 * {@link Country}, {@link Airline}, {@link Airport}, {@link Plane}, and {@link Route}.
 *
 * <p>This class centralizes how those Kafka messages are turned into strongly typed Java objects and how listener
 * containers should behave once they receive them. Without this configuration, each listener would need to repeat the
 * same low-level setup for deserialization, group membership, acknowledgements, retry handling, and concurrency.
 *
 * <p>Main responsibilities of this class:
 *
 * <p>1. create a {@link ConsumerFactory} for each payload type so Spring Kafka can deserialize JSON directly into the
 * matching OpenFlights API record
 *
 * <p>2. create one {@link ConcurrentKafkaListenerContainerFactory} per payload type so listener methods can stay typed
 * and simple
 *
 * <p>3. define common consumer behavior such as:
 * - shared consumer group id
 * - earliest-offset consumption
 * - manual record acknowledgements instead of Kafka auto-commit
 * - retry/backoff behavior for transient persistence failures
 *
 * <p>Why there are typed factories instead of one generic consumer factory:
 *
 * <p>Each OpenFlights topic carries a different record class. Using typed factories keeps deserialization explicit and
 * safe. Listener methods can declare {@code Country}, {@code Airline}, {@code Airport}, {@code Plane}, or
 * {@code Route} directly instead of receiving raw strings or generic maps and converting them later.
 *
 * <p>That gives a few benefits:
 * - clearer listener method signatures
 * - less parsing logic inside the listener layer
 * - fewer runtime casting/conversion mistakes
 * - easier testing because each listener consumes a concrete type
 *
 * <p>Why JSON deserializer settings are configured here:
 *
 * <p>The producer sends shared OpenFlights API objects as JSON. The consumer needs to deserialize only classes from
 * the trusted OpenFlights API package. This class sets up that trust boundary and tells the deserializer to use the
 * expected target class directly rather than relying on type headers.
 *
 * <p>Why acknowledgements are set to {@link ContainerProperties.AckMode#RECORD}:
 *
 * <p>The application persists each consumed record independently. Record-level acknowledgement means a message is
 * acknowledged only after its listener call completes successfully. That fits the write-heavy ingestion model better
 * than auto-commit because it reduces the chance of claiming progress before PostgreSQL persistence actually succeeded.
 *
 * <p>Why a common error handler is configured:
 *
 * <p>Some ingestion failures are transient or recoverable, especially when foreign-key-related placeholder rows are
 * being created concurrently. The {@link DefaultErrorHandler} with a fixed backoff gives the consumer a small retry
 * window before the record is treated as failed. This keeps retry behavior centralized instead of scattering it across
 * listener methods.
 *
 * <p>Why routes have separate concurrency control:
 *
 * <p>Route ingestion is by far the heaviest dataset. It is much larger than countries, airlines, airports, or planes,
 * and route persistence also creates {@code route_equipment_code} rows and resolves foreign-key references.
 *
 * <p>Because of that, route consumers are allowed to run with configurable concurrency while the other datasets use the
 * default single-threaded container behavior. This lets runtime deployments scale route ingestion throughput without
 * forcing the same complexity on the smaller topics.
 *
 * <p>In short, this class is the Kafka-side equivalent of a wiring hub: it keeps listener code focused on business
 * handling while centralizing all consumer mechanics in one place.
 */
@EnableKafka
@Configuration
public class KafkaConsumerConfiguration {

    public static final String GROUP_ID = "general-group";
    private static final int DEFAULT_ROUTE_CONCURRENCY = 8;

    private final String bootstrapAddress;
    private final int routeConsumerConcurrency;

    public KafkaConsumerConfiguration(
            @org.springframework.beans.factory.annotation.Value("${spring.kafka.bootstrap-servers}") String bootstrapAddress,
            @org.springframework.beans.factory.annotation.Value("${openflights.kafka.route-consumer-concurrency:" + DEFAULT_ROUTE_CONCURRENCY + "}")
            int routeConsumerConcurrency) {
        this.bootstrapAddress = bootstrapAddress;
        this.routeConsumerConcurrency = routeConsumerConcurrency;
    }

    @Bean
    public CommonErrorHandler kafkaErrorHandler() {
        return new DefaultErrorHandler(new FixedBackOff(1_000L, 5));
    }

    @Bean
    public ConsumerFactory<String, Country> countryConsumerFactory() {
        return consumerFactory(Country.class);
    }

    @Bean
    public ConsumerFactory<String, Airline> airlineConsumerFactory() {
        return consumerFactory(Airline.class);
    }

    @Bean
    public ConsumerFactory<String, Airport> airportConsumerFactory() {
        return consumerFactory(Airport.class);
    }

    @Bean
    public ConsumerFactory<String, Plane> planeConsumerFactory() {
        return consumerFactory(Plane.class);
    }

    @Bean
    public ConsumerFactory<String, Route> routeConsumerFactory() {
        return consumerFactory(Route.class);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Country> countryKafkaListenerContainerFactory() {
        return kafkaListenerContainerFactory(countryConsumerFactory());
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Airline> airlineKafkaListenerContainerFactory() {
        return kafkaListenerContainerFactory(airlineConsumerFactory());
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Airport> airportKafkaListenerContainerFactory() {
        return kafkaListenerContainerFactory(airportConsumerFactory());
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Plane> planeKafkaListenerContainerFactory() {
        return kafkaListenerContainerFactory(planeConsumerFactory());
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Route> routeKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, Route> factory =
                kafkaListenerContainerFactory(routeConsumerFactory());
        factory.setConcurrency(routeConsumerConcurrency);
        return factory;
    }

    private <T> ConsumerFactory<String, T> consumerFactory(Class<T> valueType) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapAddress);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, GROUP_ID);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        JsonDeserializer<T> valueDeserializer = new JsonDeserializer<>(valueType);
        valueDeserializer.addTrustedPackages("dev.nklip.javacraft.openflights.api");
        valueDeserializer.ignoreTypeHeaders();
        return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), valueDeserializer);
    }

    private <T> ConcurrentKafkaListenerContainerFactory<String, T> kafkaListenerContainerFactory(
            ConsumerFactory<String, T> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, T> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.setCommonErrorHandler(kafkaErrorHandler());
        return factory;
    }
}
