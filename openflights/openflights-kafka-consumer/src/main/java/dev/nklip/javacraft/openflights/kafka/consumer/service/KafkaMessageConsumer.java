package dev.nklip.javacraft.openflights.kafka.consumer.service;

import dev.nklip.javacraft.openflights.api.Airline;
import dev.nklip.javacraft.openflights.api.Airport;
import dev.nklip.javacraft.openflights.api.Country;
import dev.nklip.javacraft.openflights.api.kafka.OpenFlightsTopics;
import dev.nklip.javacraft.openflights.api.Plane;
import dev.nklip.javacraft.openflights.api.Route;
import dev.nklip.javacraft.openflights.kafka.consumer.config.KafkaConsumerConfiguration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

/**
 * Thin Kafka listener facade for the OpenFlights consumer module.
 *
 * <p>Why this class exists:
 *
 * <p>The application consumes several Kafka topics, and each topic carries one specific OpenFlights payload type:
 * {@link Country}, {@link Airline}, {@link Airport}, {@link Plane}, or {@link Route}. Spring Kafka needs concrete
 * listener methods annotated with {@link KafkaListener} so incoming records can be dispatched from Kafka into the
 * application.
 *
 * <p>This class is that entry point. It is the boundary where a Kafka record stops being "a message in a topic" and
 * becomes a typed method call inside the application.
 *
 * <p>Why the methods are intentionally small:
 *
 * <p>The listener layer should stay focused on Kafka concerns only:
 * - receive a typed record from the correct topic
 * - optionally log a lightweight trace/debug message
 * - delegate to the persistence orchestration layer
 *
 * <p>It should not own business rules such as normalization, placeholder creation, route idempotency, or SQL write
 * decisions. Those concerns live in {@link OpenFlightsPersistenceService}. Keeping this class thin has a few benefits:
 *
 * <p>- easier to read: one topic maps to one method and one persistence call
 * <p>- easier to test: listener behavior is mostly "message received -> delegate"
 * <p>- easier to change: persistence rules can evolve without rewriting Kafka listener code
 * <p>- clearer architecture: Kafka transport stays separated from persistence orchestration
 *
 * <p>Why there is one listener method per entity type:
 *
 * <p>OpenFlights uses topic-per-entity semantics. Each topic is already configured to deserialize into one concrete
 * payload type, so it is clearer and safer to keep one method per topic rather than receive a generic object and
 * branch manually.
 *
 * <p>That means:
 * - country topic -> {@link #listenCountry(Country)}
 * - airline topic -> {@link #listenAirline(Airline)}
 * - airport topic -> {@link #listenAirport(Airport)}
 * - plane topic -> {@link #listenPlane(Plane)}
 * - route topic -> {@link #listenRoute(Route)}
 *
 * <p>This one-to-one mapping makes the consumer easier to reason about and lines up with the typed container factories
 * defined in {@link KafkaConsumerConfiguration}.
 *
 * <p>Why logging here is lightweight:
 *
 * <p>Kafka listeners are high-volume code paths, especially for route ingestion. Logging full payloads or too much
 * detail here would create noise and slow ingestion. That is why the class logs only compact debug messages that help
 * confirm which entity type was received without turning the listener into a verbose tracing layer.
 *
 * <p>In short, this class exists to keep Kafka consumption explicit and simple: it receives typed records from the
 * right topics and hands them off to the service that knows how to persist them correctly.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class KafkaMessageConsumer {

    private final OpenFlightsPersistenceService persistenceService;

    @KafkaListener(
            topics = OpenFlightsTopics.COUNTRY,
            groupId = KafkaConsumerConfiguration.GROUP_ID,
            containerFactory = "countryKafkaListenerContainerFactory"
    )
    public void listenCountry(Country country) {
        log.debug("Received country [{}]", country.name());
        persistenceService.saveCountry(country);
    }

    @KafkaListener(
            topics = OpenFlightsTopics.AIRLINE,
            groupId = KafkaConsumerConfiguration.GROUP_ID,
            containerFactory = "airlineKafkaListenerContainerFactory"
    )
    public void listenAirline(Airline airline) {
        log.debug("Received airline [{}]", airline.airlineId());
        persistenceService.saveAirline(airline);
    }

    @KafkaListener(
            topics = OpenFlightsTopics.AIRPORT,
            groupId = KafkaConsumerConfiguration.GROUP_ID,
            containerFactory = "airportKafkaListenerContainerFactory"
    )
    public void listenAirport(Airport airport) {
        log.debug("Received airport [{}]", airport.airportId());
        persistenceService.saveAirport(airport);
    }

    @KafkaListener(
            topics = OpenFlightsTopics.PLANE,
            groupId = KafkaConsumerConfiguration.GROUP_ID,
            containerFactory = "planeKafkaListenerContainerFactory"
    )
    public void listenPlane(Plane plane) {
        log.debug("Received plane [{}]", plane.name());
        persistenceService.savePlane(plane);
    }

    @KafkaListener(
            topics = OpenFlightsTopics.ROUTE,
            groupId = KafkaConsumerConfiguration.GROUP_ID,
            containerFactory = "routeKafkaListenerContainerFactory"
    )
    public void listenRoute(Route route) {
        log.debug("Received route [{} -> {}]", route.sourceAirportCode(), route.destinationAirportCode());
        persistenceService.saveRoute(route);
    }
}
