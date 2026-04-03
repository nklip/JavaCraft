package dev.nklip.javacraft.openflights.testing.cucumber.step;

import dev.nklip.javacraft.openflights.kafka.producer.model.OpenFlightsImportResult;
import io.cucumber.java.Before;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Scope;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;

import static io.cucumber.spring.CucumberTestContext.SCOPE_CUCUMBER_GLUE;

@Slf4j
@Scope(SCOPE_CUCUMBER_GLUE)
public class OpenFlightsIngestionStepDefinitions {

    private static final Duration INGESTION_TIMEOUT = Duration.ofMinutes(5);
    private static final long POLL_INTERVAL_MILLIS = 500L;

    private final int port;
    private final JdbcTemplate jdbcTemplate;
    private final ExpectedOpenFlightsIngestionCalculator ingestionCalculator;
    private final RestTemplate restTemplate = new RestTemplate();

    private OpenFlightsIngestionExpectations expectations;
    private long ingestionStartedAtMillis;
    private long ingestionDurationMillis;
    private OpenFlightsIngestionTimings ingestionTimings;
    private OpenFlightsIngestionTimingTracker timingTracker;

    public OpenFlightsIngestionStepDefinitions(
            @LocalServerPort int port,
            JdbcTemplate jdbcTemplate,
            ExpectedOpenFlightsIngestionCalculator ingestionCalculator
    ) {
        this.port = port;
        this.jdbcTemplate = jdbcTemplate;
        this.ingestionCalculator = ingestionCalculator;
    }

    @Before
    public void prepareScenario() {
        expectations = ingestionCalculator.calculate();
        ingestionStartedAtMillis = -1L;
        ingestionDurationMillis = -1L;
        ingestionTimings = null;
        timingTracker = null;
        cleanupDatabase();
    }

    @When("all OpenFlights datasets are ingested through the producer endpoints in the documented order")
    public void ingestAllOpenFlightsDatasets() {
        ingestionStartedAtMillis = currentDatabaseTimeMillis();
        timingTracker = new OpenFlightsIngestionTimingTracker(
                expectations.expectedDatabaseStatus(),
                ingestionStartedAtMillis
        );

        assertImportAccepted("/api/v1/openflights/import/countries",
                "countries", expectations.submittedCountries());
        waitForDatabaseStatus(expectations.expectedAfterCountries(), "countries");
        assertImportAccepted("/api/v1/openflights/import/airlines",
                "airlines", expectations.submittedAirlines());
        waitForDatabaseStatus(expectations.expectedAfterAirlines(), "airlines");
        assertImportAccepted("/api/v1/openflights/import/airports",
                "airports", expectations.submittedAirports());
        waitForDatabaseStatus(expectations.expectedAfterAirports(), "airports");
        assertImportAccepted("/api/v1/openflights/import/planes",
                "planes", expectations.submittedPlanes());
        waitForDatabaseStatus(expectations.expectedAfterPlanes(), "planes");
        assertImportAccepted("/api/v1/openflights/import/routes",
                "routes", expectations.submittedRoutes());
    }

    @Then("PostgreSQL eventually contains the expected OpenFlights dataset")
    public void postgresEventuallyContainsExpectedDataset() {
        Assertions.assertTrue(ingestionStartedAtMillis >= 0L, "Ingestion start time must be recorded first");
        Assertions.assertNotNull(timingTracker, "Expected PostgreSQL ingestion timing tracker to be initialized");

        TimedOpenFlightsDatabaseStatus actualStatus =
                waitForDatabaseStatus(expectations.expectedDatabaseStatus(), "final OpenFlights dataset");
        ingestionTimings = timingTracker.finish(actualStatus.observedAtMillis());
        ingestionDurationMillis = ingestionTimings.totalMillis();
        log.info(
                "OpenFlights full ingestion finished with PostgreSQL status {}. Timings [countries={} ms, airlines={} ms, "
                        + "airports={} ms, planes={} ms, routes={} ms, routeEquipmentCodes={} ms, total={} ms]",
                actualStatus.status(),
                ingestionTimings.countriesMillis(),
                ingestionTimings.airlinesMillis(),
                ingestionTimings.airportsMillis(),
                ingestionTimings.planesMillis(),
                ingestionTimings.routesMillis(),
                ingestionTimings.routeEquipmentCodesMillis(),
                ingestionTimings.totalMillis()
        );
    }

    @Then("PostgreSQL estimates a non-negative full-ingestion duration")
    public void postgresEstimatesNonNegativeDuration() {
        Assertions.assertNotNull(ingestionTimings, "Expected per-type PostgreSQL ingestion timings to be captured");
        assertNonNegativeTiming("countries", ingestionTimings.countriesMillis());
        assertNonNegativeTiming("airlines", ingestionTimings.airlinesMillis());
        assertNonNegativeTiming("airports", ingestionTimings.airportsMillis());
        assertNonNegativeTiming("planes", ingestionTimings.planesMillis());
        assertNonNegativeTiming("routes", ingestionTimings.routesMillis());
        assertNonNegativeTiming("route equipment codes", ingestionTimings.routeEquipmentCodesMillis());
        Assertions.assertTrue(ingestionDurationMillis >= 0L,
                "Expected a non-negative PostgreSQL ingestion duration but was " + ingestionDurationMillis);
    }

    private void assertImportAccepted(String path, String datasetName, int expectedSubmittedRecords) {
        ResponseEntity<OpenFlightsImportResult> response = restTemplate.exchange(
                "http://localhost:%s%s".formatted(port, path),
                HttpMethod.POST,
                null,
                OpenFlightsImportResult.class
        );

        Assertions.assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        Assertions.assertNotNull(response.getBody());
        Assertions.assertEquals(datasetName, response.getBody().dataset());
        Assertions.assertEquals(expectedSubmittedRecords, response.getBody().submittedRecords());
    }

    private TimedOpenFlightsDatabaseStatus waitForDatabaseStatus(OpenFlightsDatabaseStatus expectedStatus, String phaseName) {
        long deadlineMillis = System.currentTimeMillis() + INGESTION_TIMEOUT.toMillis();
        TimedOpenFlightsDatabaseStatus actualStatus = readTimedDatabaseStatus();
        timingTracker.recordProgress(actualStatus.status(), actualStatus.observedAtMillis());

        while (!expectedStatus.equals(actualStatus.status()) && System.currentTimeMillis() <= deadlineMillis) {
            sleepBeforeNextPoll();
            actualStatus = readTimedDatabaseStatus();
            timingTracker.recordProgress(actualStatus.status(), actualStatus.observedAtMillis());
        }

        if (!expectedStatus.equals(actualStatus.status())) {
            Assertions.fail("PostgreSQL did not reach expected status for %s within %s. Expected=%s, actual=%s"
                    .formatted(phaseName, INGESTION_TIMEOUT, expectedStatus, actualStatus.status()));
        }
        return actualStatus;
    }

    private TimedOpenFlightsDatabaseStatus readTimedDatabaseStatus() {
        OpenFlightsDatabaseStatus status = new OpenFlightsDatabaseStatus(
                queryCount("country"),
                queryCount("airline"),
                queryCount("airport"),
                queryCount("plane"),
                queryCount("flight_route"),
                queryCount("route_equipment_code")
        );
        return new TimedOpenFlightsDatabaseStatus(currentDatabaseTimeMillis(), status);
    }

    private long queryCount(String tableName) {
        Long count = jdbcTemplate.queryForObject("select count(*) from " + tableName, Long.class);
        Assertions.assertNotNull(count);
        return count;
    }

    private long currentDatabaseTimeMillis() {
        Long currentTimeMillis = jdbcTemplate.queryForObject(
                "select cast(floor(extract(epoch from clock_timestamp()) * 1000) as bigint)",
                Long.class
        );
        Assertions.assertNotNull(currentTimeMillis);
        return currentTimeMillis;
    }

    private void cleanupDatabase() {
        jdbcTemplate.execute("truncate table route_equipment_code, flight_route, plane, airport, airline, country restart identity cascade");
    }

    private void assertNonNegativeTiming(String datasetType, long durationMillis) {
        Assertions.assertTrue(durationMillis >= 0L,
                "Expected a non-negative PostgreSQL ingestion duration for " + datasetType + " but was " + durationMillis);
    }

    private void sleepBeforeNextPoll() {
        try {
            Thread.sleep(POLL_INTERVAL_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for PostgreSQL ingestion status", e);
        }
    }

    private record TimedOpenFlightsDatabaseStatus(long observedAtMillis, OpenFlightsDatabaseStatus status) {
    }
}
