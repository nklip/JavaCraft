package dev.nklip.javacraft.openflights.kafka.producer.service;

import dev.nklip.javacraft.openflights.api.Airline;
import dev.nklip.javacraft.openflights.api.Airport;
import dev.nklip.javacraft.openflights.api.Country;
import dev.nklip.javacraft.openflights.api.Plane;
import dev.nklip.javacraft.openflights.api.Route;
import dev.nklip.javacraft.openflights.data.reader.OpenFlightsDataReader;
import dev.nklip.javacraft.openflights.kafka.producer.model.OpenFlightsImportResult;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenFlightsFileImportServiceTest {

    private static final Executor DIRECT_EXECUTOR = Runnable::run;

    @Test
    void springSelectsDedicatedImportExecutorWhenAnotherExecutorExists() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(OpenFlightsDataReader.class, () -> mock(OpenFlightsDataReader.class));
            context.registerBean(KafkaMessageProducer.class, () -> mock(KafkaMessageProducer.class));
            context.registerBean("otherExecutor", Executor.class, () -> DIRECT_EXECUTOR);
            context.registerBean("openFlightsImportExecutor", Executor.class, () -> DIRECT_EXECUTOR);
            context.register(OpenFlightsFileImportService.class);

            Assertions.assertDoesNotThrow(context::refresh);
            Assertions.assertNotNull(context.getBean(OpenFlightsFileImportService.class));
        }
    }

    @Test
    void importCountriesReadsAndPublishesCountries() {
        OpenFlightsDataReader dataReader = mock(OpenFlightsDataReader.class);
        KafkaMessageProducer kafkaMessageProducer = mock(KafkaMessageProducer.class);
        List<Country> countries = List.of(new Country("Russia", "RU", "RS"), new Country("France", "FR", "FR"));
        when(dataReader.readCountries()).thenReturn(countries);
        when(kafkaMessageProducer.sendCountry(any(Country.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        OpenFlightsFileImportService importService = new OpenFlightsFileImportService(dataReader, kafkaMessageProducer, DIRECT_EXECUTOR);

        OpenFlightsImportResult result = importService.importCountries();

        Assertions.assertEquals("countries", result.dataset());
        Assertions.assertEquals(2, result.submittedRecords());
        verify(kafkaMessageProducer, times(2)).sendCountry(org.mockito.ArgumentMatchers.any(Country.class));
    }

    @Test
    void importAirlinesReadsAndPublishesAirlines() {
        OpenFlightsDataReader dataReader = mock(OpenFlightsDataReader.class);
        KafkaMessageProducer kafkaMessageProducer = mock(KafkaMessageProducer.class);
        List<Airline> airlines = List.of(new Airline(410, "Ak Bars Aero", null, "2B", "BGB", null, "Russia", true));
        when(dataReader.readAirlines()).thenReturn(airlines);
        when(kafkaMessageProducer.sendAirline(any(Airline.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        OpenFlightsFileImportService importService = new OpenFlightsFileImportService(dataReader, kafkaMessageProducer, DIRECT_EXECUTOR);

        OpenFlightsImportResult result = importService.importAirlines();

        Assertions.assertEquals("airlines", result.dataset());
        Assertions.assertEquals(1, result.submittedRecords());
        verify(kafkaMessageProducer).sendAirline(airlines.getFirst());
    }

    @Test
    void importAirportsReadsAndPublishesAirports() {
        OpenFlightsDataReader dataReader = mock(OpenFlightsDataReader.class);
        KafkaMessageProducer kafkaMessageProducer = mock(KafkaMessageProducer.class);
        List<Airport> airports = List.of(new Airport(2965, "Sochi Airport", "Sochi", "Russia", "AER", "URSS",
                43.449902, 39.9566, 89, 3.0, "E", "Europe/Moscow", "airport", "OurAirports"));
        when(dataReader.readAirports()).thenReturn(airports);
        when(kafkaMessageProducer.sendAirport(any(Airport.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        OpenFlightsFileImportService importService = new OpenFlightsFileImportService(dataReader, kafkaMessageProducer, DIRECT_EXECUTOR);

        OpenFlightsImportResult result = importService.importAirports();

        Assertions.assertEquals("airports", result.dataset());
        Assertions.assertEquals(1, result.submittedRecords());
        verify(kafkaMessageProducer).sendAirport(airports.getFirst());
    }

    @Test
    void importPlanesReadsAndPublishesPlanes() {
        OpenFlightsDataReader dataReader = mock(OpenFlightsDataReader.class);
        KafkaMessageProducer kafkaMessageProducer = mock(KafkaMessageProducer.class);
        List<Plane> planes = List.of(new Plane("Bombardier CRJ200", "CR2", "CRJ2"));
        when(dataReader.readPlanes()).thenReturn(planes);
        when(kafkaMessageProducer.sendPlane(any(Plane.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        OpenFlightsFileImportService importService = new OpenFlightsFileImportService(dataReader, kafkaMessageProducer, DIRECT_EXECUTOR);

        OpenFlightsImportResult result = importService.importPlanes();

        Assertions.assertEquals("planes", result.dataset());
        Assertions.assertEquals(1, result.submittedRecords());
        verify(kafkaMessageProducer).sendPlane(planes.getFirst());
    }

    @Test
    void importRoutesReadsAndPublishesRoutes() {
        OpenFlightsDataReader dataReader = mock(OpenFlightsDataReader.class);
        KafkaMessageProducer kafkaMessageProducer = mock(KafkaMessageProducer.class);
        List<Route> routes = List.of(new Route("2B", 410, "AER", 2965, "KZN", 2990, false, 0, List.of("CR2", "CRJ")));
        when(dataReader.readRoutes()).thenReturn(routes);
        when(kafkaMessageProducer.sendRoute(any(Route.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        OpenFlightsFileImportService importService = new OpenFlightsFileImportService(dataReader, kafkaMessageProducer, DIRECT_EXECUTOR);

        OpenFlightsImportResult result = importService.importRoutes();

        Assertions.assertEquals("routes", result.dataset());
        Assertions.assertEquals(1, result.submittedRecords());
        verify(kafkaMessageProducer).sendRoute(routes.getFirst());
    }

    @Test
    void importCountriesPropagatesKafkaAcknowledgementFailure() {
        OpenFlightsDataReader dataReader = mock(OpenFlightsDataReader.class);
        KafkaMessageProducer kafkaMessageProducer = mock(KafkaMessageProducer.class);
        Country country = new Country("Russia", "RU", "RS");
        RuntimeException failure = new RuntimeException("broker unavailable");
        when(dataReader.readCountries()).thenReturn(List.of(country));
        when(kafkaMessageProducer.sendCountry(country)).thenReturn(CompletableFuture.failedFuture(failure));
        OpenFlightsFileImportService importService =
                new OpenFlightsFileImportService(dataReader, kafkaMessageProducer, DIRECT_EXECUTOR);

        CompletionException exception =
                Assertions.assertThrows(CompletionException.class, importService::importCountries);

        Assertions.assertSame(failure, exception.getCause());
    }

    /**
     * Guards the batching behaviour: a worker must dispatch every send in its chunk before waiting
     * for acknowledgements, rather than acknowledging one record at a time.
     *
     * <p>No send here completes until all records have been dispatched. Waiting per record would
     * therefore stall — each worker would block on its first record while the remaining records in
     * its chunk were never sent — so this test would time out. Dispatching first and joining once
     * releases the latch and completes normally.
     */
    @Test
    void importDispatchesEveryRecordBeforeWaitingForAcknowledgements() {
        int records = 64;
        OpenFlightsDataReader dataReader = mock(OpenFlightsDataReader.class);
        KafkaMessageProducer kafkaMessageProducer = mock(KafkaMessageProducer.class);
        List<Country> countries = IntStream.range(0, records)
                .mapToObj(i -> new Country("Country" + i, "C" + i, "c" + i))
                .toList();
        when(dataReader.readCountries()).thenReturn(countries);

        CountDownLatch dispatched = new CountDownLatch(records);
        List<CompletableFuture<Object>> issued = new CopyOnWriteArrayList<>();
        when(kafkaMessageProducer.sendCountry(any(Country.class))).thenAnswer(_ -> {
            CompletableFuture<Object> future = new CompletableFuture<>();
            issued.add(future);
            dispatched.countDown();
            if (dispatched.getCount() == 0) {
                issued.forEach(pending -> pending.complete(null));
            }
            return future;
        });

        ExecutorService executor = Executors.newCachedThreadPool();
        try {
            OpenFlightsFileImportService importService =
                    new OpenFlightsFileImportService(dataReader, kafkaMessageProducer, executor);

            OpenFlightsImportResult result = Assertions.assertTimeoutPreemptively(
                    Duration.ofSeconds(10),
                    importService::importCountries,
                    "sends must be dispatched before acknowledgements are awaited"
            );

            Assertions.assertEquals(records, result.submittedRecords());
            Assertions.assertEquals(0, dispatched.getCount());
        } finally {
            executor.shutdownNow();
        }
    }
}
