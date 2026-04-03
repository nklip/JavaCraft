package dev.nklip.javacraft.openflights.kafka.producer.service;

import dev.nklip.javacraft.openflights.api.Airline;
import dev.nklip.javacraft.openflights.api.Airport;
import dev.nklip.javacraft.openflights.api.Country;
import dev.nklip.javacraft.openflights.api.Plane;
import dev.nklip.javacraft.openflights.api.Route;
import dev.nklip.javacraft.openflights.data.reader.OpenFlightsDataReader;
import dev.nklip.javacraft.openflights.kafka.producer.model.OpenFlightsImportResult;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenFlightsFileImportServiceTest {

    private static final Executor DIRECT_EXECUTOR = Runnable::run;

    @Test
    void importCountriesReadsAndPublishesCountries() {
        OpenFlightsDataReader dataReader = mock(OpenFlightsDataReader.class);
        KafkaMessageProducer kafkaMessageProducer = mock(KafkaMessageProducer.class);
        List<Country> countries = List.of(new Country("Russia", "RU", "RS"), new Country("France", "FR", "FR"));
        when(dataReader.readCountries()).thenReturn(countries);
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
        OpenFlightsFileImportService importService = new OpenFlightsFileImportService(dataReader, kafkaMessageProducer, DIRECT_EXECUTOR);

        OpenFlightsImportResult result = importService.importRoutes();

        Assertions.assertEquals("routes", result.dataset());
        Assertions.assertEquals(1, result.submittedRecords());
        verify(kafkaMessageProducer).sendRoute(routes.getFirst());
    }
}
