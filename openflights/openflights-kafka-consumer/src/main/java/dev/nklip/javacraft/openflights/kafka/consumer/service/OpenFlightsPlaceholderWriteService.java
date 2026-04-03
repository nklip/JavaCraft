package dev.nklip.javacraft.openflights.kafka.consumer.service;

import dev.nklip.javacraft.openflights.jpa.entity.AirlineEntity;
import dev.nklip.javacraft.openflights.jpa.entity.AirportEntity;
import dev.nklip.javacraft.openflights.jpa.entity.CountryEntity;
import dev.nklip.javacraft.openflights.jpa.repository.AirlineRepository;
import dev.nklip.javacraft.openflights.jpa.repository.AirportRepository;
import dev.nklip.javacraft.openflights.jpa.repository.CountryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Isolates placeholder writes for missing reference data into their own transactions.
 *
 * <p>Why this helper exists:
 *
 * <p>The route consumer can process several route records in parallel. When a route arrives before its referenced
 * airline or airports have been ingested, {@link OpenFlightsPersistenceService} creates lightweight placeholder rows
 * so the route can still be stored and later be enriched by the real airline/airport messages.
 *
 * <p>With concurrent route consumers, two threads can discover the same missing reference at nearly the same time.
 * Both threads can then try to insert the same placeholder primary key. One insert succeeds, and the other one fails
 * with a unique-constraint violation. That duplicate insert is expected and recoverable, because the losing thread can
 * re-check the database and continue once the placeholder created by the winning thread is visible.
 *
 * <p>The problem is that PostgreSQL marks the current transaction as aborted after that constraint violation. If the
 * placeholder insert happens inside the main route-processing transaction, the route save cannot continue even though
 * the required placeholder row now exists. In practice that led to retries and noisy failures during parallel route
 * ingestion.
 *
 * <p>This service fixes that by moving the placeholder insert into a separate {@code REQUIRES_NEW} transaction. If the
 * placeholder insert wins, it commits independently. If it loses with a duplicate-key error, only this short helper
 * transaction is rolled back, while the caller's main transaction stays healthy and can re-check the repository and
 * proceed with saving the route.
 *
 * <p>So the main purpose of this class is not abstraction for its own sake; it is to give placeholder writes their own
 * transaction boundary so concurrent ingestion remains safe under normal runtime parallelism.
 */
@Service
@RequiredArgsConstructor
public class OpenFlightsPlaceholderWriteService {

    private final CountryRepository countryRepository;
    private final AirlineRepository airlineRepository;
    private final AirportRepository airportRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveCountryPlaceholder(CountryEntity countryEntity) {
        countryRepository.saveAndFlush(countryEntity);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveAirlinePlaceholder(AirlineEntity airlineEntity) {
        airlineRepository.saveAndFlush(airlineEntity);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveAirportPlaceholder(AirportEntity airportEntity) {
        airportRepository.saveAndFlush(airportEntity);
    }
}
