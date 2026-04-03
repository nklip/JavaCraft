package dev.nklip.javacraft.openflights.kafka.consumer.service;

import dev.nklip.javacraft.openflights.api.Airline;
import dev.nklip.javacraft.openflights.api.Airport;
import dev.nklip.javacraft.openflights.api.Country;
import dev.nklip.javacraft.openflights.api.Plane;
import dev.nklip.javacraft.openflights.api.Route;
import dev.nklip.javacraft.openflights.jpa.entity.AirlineEntity;
import dev.nklip.javacraft.openflights.jpa.entity.AirportEntity;
import dev.nklip.javacraft.openflights.jpa.entity.CountryEntity;
import dev.nklip.javacraft.openflights.jpa.entity.PlaneEntity;
import dev.nklip.javacraft.openflights.jpa.entity.RouteEntity;
import dev.nklip.javacraft.openflights.jpa.mapper.AirlineEntityMapper;
import dev.nklip.javacraft.openflights.jpa.mapper.AirportEntityMapper;
import dev.nklip.javacraft.openflights.jpa.mapper.CountryEntityMapper;
import dev.nklip.javacraft.openflights.jpa.mapper.PlaneEntityMapper;
import dev.nklip.javacraft.openflights.jpa.mapper.RouteEntityMapper;
import dev.nklip.javacraft.openflights.jpa.normalize.CountryNameNormalizer;
import dev.nklip.javacraft.openflights.jpa.repository.AirlineRepository;
import dev.nklip.javacraft.openflights.jpa.repository.AirportRepository;
import dev.nklip.javacraft.openflights.jpa.repository.CountryRepository;
import dev.nklip.javacraft.openflights.jpa.repository.PlaneRepository;
import dev.nklip.javacraft.openflights.jpa.repository.RouteRepository;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Main persistence orchestration service for OpenFlights consumer ingestion.
 *
 * <p>Why this service exists:
 *
 * <p>The Kafka listeners receive shared API records such as {@link Country}, {@link Airline}, {@link Airport},
 * {@link Plane}, and {@link Route}. Those records are transport payloads, not JPA entities, and persisting them is
 * not a simple "repository.save(record)" operation. Before anything is written to PostgreSQL, the application often
 * needs to:
 *
 * <p>1. normalize source values so they match the SQL model
 * 2. resolve whether the incoming message should create a new row or update an existing one
 * 3. protect idempotency so repeated Kafka deliveries do not keep inserting duplicate data
 * 4. create placeholder reference rows when routes arrive before their airline or airport data
 * 5. keep all of that inside a clear transactional boundary
 *
 * <p>This class is the place where that ingestion logic lives. It sits between the Kafka listener layer and the JPA
 * repositories and coordinates the work of:
 *
 * <p>- repositories, which only perform database I/O
 * <p>- mappers, which convert API records into entity state
 * <p>- normalizers, which canonicalize source values such as country names
 * <p>- placeholder writes, which repair missing foreign-key references during out-of-order ingestion
 *
 * <p>Why the logic is not pushed down into repositories:
 *
 * <p>The repository layer should stay focused on I/O only. It should not own decisions such as "does this country
 * already exist by ISO code or by name?", "should this route update an existing natural-key row?", or "should a
 * placeholder airport be created before saving the route?". Those are ingestion/business rules, so they belong in a
 * service.
 *
 * <p>Why the logic is not pushed up into Kafka listeners:
 *
 * <p>The listener layer should stay focused on consuming typed Kafka messages. If we embedded all persistence rules
 * directly in listener methods, the consumer package would become harder to test, harder to reuse, and much more
 * coupled to persistence details. This service gives the listeners one clear entry point per payload type while
 * keeping the real write logic centralized.
 *
 * <p>Key responsibilities of this service:
 *
 * <p>- countries:
 * update existing rows when the source matches by ISO code or by name instead of blindly inserting duplicates
 *
 * <p>- airlines and airports:
 * normalize incoming country names and ensure the referenced country row exists before persisting the entity
 *
 * <p>- planes:
 * deduplicate by the natural key used by the project so replaying the same plane message stays idempotent
 *
 * <p>- routes:
 * ensure referenced airline and airport rows exist, compute the natural route key, update an existing route when the
 * same route is redelivered, and otherwise insert a new route
 *
 * <p>Another important reason this service exists is ingestion order tolerance. The ideal import order is countries,
 * airlines, airports, planes, then routes, but Kafka processing and retries mean messages can still arrive out of
 * order. This service contains the safeguards that let the system continue to ingest data correctly even when a route
 * references airline or airport rows that are not in PostgreSQL yet.
 *
 * <p>In short, this class is the "write-side coordinator" for OpenFlights ingestion: it keeps the listeners simple,
 * keeps repositories I/O-focused, and makes PostgreSQL persistence resilient, idempotent, and consistent.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class OpenFlightsPersistenceService {

    private final CountryRepository countryRepository;
    private final AirlineRepository airlineRepository;
    private final AirportRepository airportRepository;
    private final PlaneRepository planeRepository;
    private final RouteRepository routeRepository;
    private final CountryEntityMapper countryEntityMapper;
    private final AirlineEntityMapper airlineEntityMapper;
    private final AirportEntityMapper airportEntityMapper;
    private final PlaneEntityMapper planeEntityMapper;
    private final RouteEntityMapper routeEntityMapper;
    private final CountryNameNormalizer countryNameNormalizer;
    private final OpenFlightsPlaceholderWriteService placeholderWriteService;

    public void saveCountry(Country country) {
        CountryEntity source = countryEntityMapper.toEntity(country);
        CountryEntity entity = findCountry(country).orElseGet(CountryEntity::new);
        entity.setName(source.getName());
        entity.setIsoCode(source.getIsoCode());
        entity.setDafifCode(source.getDafifCode());
        countryRepository.save(entity);
    }

    public void saveAirline(Airline airline) {
        String normalizedCountryName = resolveCountryName(airline.country());
        airlineRepository.save(airlineEntityMapper.toEntity(airline, normalizedCountryName));
    }

    public void saveAirport(Airport airport) {
        String normalizedCountryName = resolveCountryName(airport.country());
        airportRepository.save(airportEntityMapper.toEntity(airport, normalizedCountryName));
    }

    public void savePlane(Plane plane) {
        PlaneEntity source = planeEntityMapper.toEntity(plane);
        PlaneEntity entity = findPlane(plane).orElseGet(PlaneEntity::new);
        entity.setName(source.getName());
        entity.setIataCode(source.getIataCode());
        entity.setIcaoCode(source.getIcaoCode());
        planeRepository.save(entity);
    }

    public void saveRoute(Route route) {
        ensureRouteReferencesExist(route);
        String routeKey = routeEntityMapper.toRouteKey(route);
        RouteEntity entity = routeRepository.findByRouteKey(routeKey)
                .map(existingRoute -> routeEntityMapper.copyToEntity(route, existingRoute))
                .orElseGet(() -> routeEntityMapper.toEntity(route));
        routeRepository.save(entity);
    }

    private Optional<CountryEntity> findCountry(Country country) {
        if (country.isoCode() != null && !country.isoCode().isBlank()) {
            Optional<CountryEntity> byIsoCode = countryRepository.findByIsoCode(country.isoCode());
            if (byIsoCode.isPresent()) {
                return byIsoCode;
            }
        }
        if (country.name() != null && !country.name().isBlank()) {
            return countryRepository.findByName(country.name());
        }
        return Optional.empty();
    }

    private String resolveCountryName(String rawCountryName) {
        String normalizedCountryName = countryNameNormalizer.normalize(rawCountryName);
        if (normalizedCountryName == null) {
            return null;
        }

        ensureCountryExists(normalizedCountryName);
        return normalizedCountryName;
    }

    private void ensureCountryExists(String countryName) {
        if (countryRepository.findByName(countryName).isPresent()) {
            return;
        }

        CountryEntity placeholderCountry = new CountryEntity();
        placeholderCountry.setName(countryName);
        try {
            placeholderWriteService.saveCountryPlaceholder(placeholderCountry);
        } catch (DataIntegrityViolationException exception) {
            if (countryRepository.findByName(countryName).isEmpty()) {
                throw exception;
            }
        }
    }

    private void ensureRouteReferencesExist(Route route) {
        ensureAirlineExists(route.airlineId(), route.airlineCode());
        ensureAirportExists(route.sourceAirportId(), route.sourceAirportCode());
        ensureAirportExists(route.destinationAirportId(), route.destinationAirportCode());
    }

    private void ensureAirlineExists(Integer airlineId, String airlineCode) {
        if (airlineId == null) {
            return;
        }
        persistPlaceholderIfMissing(
                () -> airlineRepository.existsById(airlineId),
                () -> placeholderWriteService.saveAirlinePlaceholder(createPlaceholderAirline(airlineId, airlineCode))
        );
    }

    private void ensureAirportExists(Integer airportId, String airportCode) {
        if (airportId == null) {
            return;
        }
        persistPlaceholderIfMissing(
                () -> airportRepository.existsById(airportId),
                () -> placeholderWriteService.saveAirportPlaceholder(createPlaceholderAirport(airportId, airportCode))
        );
    }

    private void persistPlaceholderIfMissing(BooleanSupplier existsCheck, Runnable savePlaceholder) {
        if (existsCheck.getAsBoolean()) {
            return;
        }

        try {
            savePlaceholder.run();
        } catch (DataIntegrityViolationException exception) {
            if (!existsCheck.getAsBoolean()) {
                throw exception;
            }
        }
    }

    private AirlineEntity createPlaceholderAirline(Integer airlineId, String airlineCode) {
        AirlineEntity airlineEntity = new AirlineEntity();
        airlineEntity.setAirlineId(airlineId);
        airlineEntity.setName(buildPlaceholderName("airline", airlineId, airlineCode));
        airlineEntity.setActive(false);
        return airlineEntity;
    }

    private AirportEntity createPlaceholderAirport(Integer airportId, String airportCode) {
        AirportEntity airportEntity = new AirportEntity();
        airportEntity.setAirportId(airportId);
        airportEntity.setName(buildPlaceholderName("airport", airportId, airportCode));
        airportEntity.setIataCode(airportCode);
        return airportEntity;
    }

    private String buildPlaceholderName(String entityType, Integer entityId, String entityCode) {
        if (entityCode == null || entityCode.isBlank()) {
            return "Unknown " + entityType + " " + entityId;
        }
        return "Unknown " + entityType + " " + entityId + " (" + entityCode + ")";
    }

    private Optional<PlaneEntity> findPlane(Plane plane) {
        return planeRepository.findByNameAndIataCodeAndIcaoCode(
                plane.name(), plane.iataCode(), plane.icaoCode()
        );
    }
}
