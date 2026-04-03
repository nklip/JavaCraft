package dev.nklip.javacraft.openflights.kafka.producer.service;

import dev.nklip.javacraft.openflights.api.Airline;
import dev.nklip.javacraft.openflights.api.Airport;
import dev.nklip.javacraft.openflights.api.Country;
import dev.nklip.javacraft.openflights.api.kafka.OpenFlightsTopics;
import dev.nklip.javacraft.openflights.api.Plane;
import dev.nklip.javacraft.openflights.api.Route;
import java.util.concurrent.CompletableFuture;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

/**
 * Producer-side message gateway for publishing typed OpenFlights records to Kafka.
 *
 * <p>Why this class exists:
 *
 * <p>The OpenFlights producer reads and parses source files, then publishes the resulting shared API objects to Kafka.
 * The import flow should not have to know low-level Kafka details such as:
 * - which topic each entity type belongs to
 * - how message keys are derived
 * - how send results are logged
 * - how the shared {@link KafkaTemplate} is used
 *
 * <p>This class exists to keep those concerns in one place. It is the narrow producer-side boundary between
 * higher-level import logic and the Kafka client.
 *
 * <p>Main responsibilities of this class:
 *
 * <p>1. accept already-parsed OpenFlights records from the import flow
 * <p>2. route each record type to the correct Kafka topic
 * <p>3. derive stable Kafka keys for partitioning and ordering
 * <p>4. send the record asynchronously through the shared {@link KafkaTemplate}
 * <p>5. log send success/failure at an appropriate level
 *
 * <p>Why there is one method per entity type:
 *
 * <p>Countries, airlines, airports, planes, and routes are separate Kafka topics in this application. Keeping a
 * dedicated send method for each type makes that routing explicit in code and prevents callers from having to pass raw
 * topic names around. It also keeps topic selection type-safe and easier to read.
 *
 * <p>Why key generation is centralized here:
 *
 * <p>Kafka ordering and partition placement depend on the message key. For this project, the key is not an incidental
 * detail; it is part of the ingestion contract. Different datasets derive keys differently:
 * - countries prefer ISO code, then name
 * - airlines and airports prefer source ids, then fallback business codes/names
 * - planes use the best available code/name combination
 * - routes use a composite route identity
 *
 * <p>If that logic were duplicated in controllers or import services, it would be easy for different call sites to
 * publish the same logical entity with different keys. Centralizing key derivation here keeps Kafka behavior
 * consistent.
 *
 * <p>Why this class stays small and does not parse files itself:
 *
 * <p>The producer module already has a clear boundary:
 * - data parsers/readers in {@code openflights-data} handle source-file interpretation
 * - import services orchestrate reading datasets and deciding when to publish them
 * - this class performs Kafka-specific publication only
 *
 * <p>That separation matters because it keeps Kafka concerns out of file parsing and keeps parsing concerns out of
 * transport code.
 *
 * <p>Why sends are asynchronous:
 *
 * <p>{@link KafkaTemplate#send(String, Object, Object)} returns a future because Kafka publishing is asynchronous. This
 * class intentionally preserves that model. It does not block waiting for broker acknowledgements; instead, it attaches
 * completion logging so the producer can keep high throughput while still surfacing failures.
 *
 * <p>Why success is logged at DEBUG and failures at WARN:
 *
 * <p>OpenFlights imports can publish a large number of records, especially for routes. Logging every successful send at
 * INFO would be too noisy. DEBUG keeps per-record success information available for troubleshooting, while WARN keeps
 * publication failures visible and actionable.
 *
 * <p>In short, this class is the typed Kafka publishing adapter for OpenFlights: it gives the rest of the producer
 * module a simple, consistent API for sending records while centralizing topic routing, key selection, and send-result
 * handling in one place.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaMessageProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void sendMessage2DefaultTopic(String message) {
        send(OpenFlightsTopics.DEFAULT, message, message);
    }

    public void sendCountry(Country country) {
        Objects.requireNonNull(country, "country must not be null");
        send(OpenFlightsTopics.COUNTRY, firstNonBlank(country.isoCode(), country.name()), country);
    }

    public void sendAirline(Airline airline) {
        Objects.requireNonNull(airline, "airline must not be null");
        send(OpenFlightsTopics.AIRLINE, stringKey(airline.airlineId(), airline.iataCode(), airline.name()), airline);
    }

    public void sendAirport(Airport airport) {
        Objects.requireNonNull(airport, "airport must not be null");
        send(OpenFlightsTopics.AIRPORT, stringKey(airport.airportId(), airport.iataCode(), airport.name()), airport);
    }

    public void sendPlane(Plane plane) {
        Objects.requireNonNull(plane, "plane must not be null");
        send(OpenFlightsTopics.PLANE, stringKey(plane.icaoCode(), plane.iataCode(), plane.name()), plane);
    }

    public void sendRoute(Route route) {
        Objects.requireNonNull(route, "route must not be null");
        String routeKey = String.join("|",
                stringValue(route.airlineId(), route.airlineCode()),
                stringValue(route.sourceAirportId(), route.sourceAirportCode()),
                stringValue(route.destinationAirportId(), route.destinationAirportCode()),
                stringValue(route.stops(), "0"));
        send(OpenFlightsTopics.ROUTE, routeKey, route);
    }

    private void send(String topic, String key, Object payload) {
        CompletableFuture<SendResult<String, Object>> future = kafkaTemplate.send(topic, key, payload);
        future.whenComplete((result, ex) -> {
            if (ex == null) {
                log.debug("Sent message to topic [{}] with key [{}] and offset [{}]", topic, key,
                        result.getRecordMetadata().offset());
            } else {
                log.warn("Unable to send message to topic [{}] with key [{}]: {}", topic, key, ex.getMessage());
            }
        });
    }

    private String stringKey(Object first, Object second, Object third) {
        return firstNonBlank(stringValue(first, null), stringValue(second, null), stringValue(third, null));
    }

    private String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return "unknown";
    }

    private String stringValue(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }
}
