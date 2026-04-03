package dev.nklip.javacraft.openflights.kafka.consumer.service;

import dev.nklip.javacraft.openflights.api.Airline;
import dev.nklip.javacraft.openflights.api.Airport;
import dev.nklip.javacraft.openflights.api.Country;
import dev.nklip.javacraft.openflights.api.kafka.OpenFlightsTopics;
import dev.nklip.javacraft.openflights.api.Plane;
import dev.nklip.javacraft.openflights.api.Route;
import dev.nklip.javacraft.openflights.kafka.consumer.config.KafkaConsumerConfiguration;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.annotation.KafkaListener;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class KafkaMessageConsumerTest {

    @Test
    void listenCountryHasKafkaListenerConfiguration() throws NoSuchMethodException {
        Method listenMethod = KafkaMessageConsumer.class.getDeclaredMethod("listenCountry", Country.class);
        assertKafkaListener(listenMethod, OpenFlightsTopics.COUNTRY, "countryKafkaListenerContainerFactory");
    }

    @Test
    void listenAirlineHasKafkaListenerConfiguration() throws NoSuchMethodException {
        Method listenMethod = KafkaMessageConsumer.class.getDeclaredMethod("listenAirline", Airline.class);
        assertKafkaListener(listenMethod, OpenFlightsTopics.AIRLINE, "airlineKafkaListenerContainerFactory");
    }

    @Test
    void listenAirportHasKafkaListenerConfiguration() throws NoSuchMethodException {
        Method listenMethod = KafkaMessageConsumer.class.getDeclaredMethod("listenAirport", Airport.class);
        assertKafkaListener(listenMethod, OpenFlightsTopics.AIRPORT, "airportKafkaListenerContainerFactory");
    }

    @Test
    void listenPlaneHasKafkaListenerConfiguration() throws NoSuchMethodException {
        Method listenMethod = KafkaMessageConsumer.class.getDeclaredMethod("listenPlane", Plane.class);
        assertKafkaListener(listenMethod, OpenFlightsTopics.PLANE, "planeKafkaListenerContainerFactory");
    }

    @Test
    void listenRouteHasKafkaListenerConfiguration() throws NoSuchMethodException {
        Method listenMethod = KafkaMessageConsumer.class.getDeclaredMethod("listenRoute", Route.class);
        assertKafkaListener(listenMethod, OpenFlightsTopics.ROUTE, "routeKafkaListenerContainerFactory");
    }

    @Test
    void listenCountryDelegatesToPersistenceService() {
        OpenFlightsPersistenceService persistenceService = mock(OpenFlightsPersistenceService.class);
        KafkaMessageConsumer consumer = new KafkaMessageConsumer(persistenceService);
        Country country = new Country("Russia", "RU", "RS");

        Assertions.assertDoesNotThrow(() -> consumer.listenCountry(country));

        verify(persistenceService).saveCountry(country);
    }

    @Test
    void listenAirlineDelegatesToPersistenceService() {
        OpenFlightsPersistenceService persistenceService = mock(OpenFlightsPersistenceService.class);
        KafkaMessageConsumer consumer = new KafkaMessageConsumer(persistenceService);
        Airline airline = new Airline(410, "Ak Bars Aero", null, "2B", "BGB", null, "Russia", true);

        Assertions.assertDoesNotThrow(() -> consumer.listenAirline(airline));

        verify(persistenceService).saveAirline(airline);
    }

    @Test
    void listenAirportDelegatesToPersistenceService() {
        OpenFlightsPersistenceService persistenceService = mock(OpenFlightsPersistenceService.class);
        KafkaMessageConsumer consumer = new KafkaMessageConsumer(persistenceService);
        Airport airport = new Airport(2965, "Sochi Airport", "Sochi", "Russia", "AER", "URSS",
                43.449902, 39.9566, 89, 3.0, "E", "Europe/Moscow", "airport", "OurAirports");

        Assertions.assertDoesNotThrow(() -> consumer.listenAirport(airport));

        verify(persistenceService).saveAirport(airport);
    }

    @Test
    void listenPlaneDelegatesToPersistenceService() {
        OpenFlightsPersistenceService persistenceService = mock(OpenFlightsPersistenceService.class);
        KafkaMessageConsumer consumer = new KafkaMessageConsumer(persistenceService);
        Plane plane = new Plane("Bombardier CRJ200", "CR2", "CRJ2");

        Assertions.assertDoesNotThrow(() -> consumer.listenPlane(plane));

        verify(persistenceService).savePlane(plane);
    }

    @Test
    void listenRouteDelegatesToPersistenceService() {
        OpenFlightsPersistenceService persistenceService = mock(OpenFlightsPersistenceService.class);
        KafkaMessageConsumer consumer = new KafkaMessageConsumer(persistenceService);
        Route route = new Route("2B", 410, "AER", 2965, "KZN", 2990, false, 0, List.of("CR2", "CRJ"));

        Assertions.assertDoesNotThrow(() -> consumer.listenRoute(route));

        verify(persistenceService).saveRoute(route);
    }

    private void assertKafkaListener(Method listenMethod, String topic, String containerFactory) {
        KafkaListener kafkaListener = listenMethod.getAnnotation(KafkaListener.class);

        Assertions.assertNotNull(kafkaListener);
        Assertions.assertArrayEquals(new String[]{topic}, kafkaListener.topics());
        Assertions.assertEquals(KafkaConsumerConfiguration.GROUP_ID, kafkaListener.groupId());
        Assertions.assertEquals(containerFactory, kafkaListener.containerFactory());
    }
}
