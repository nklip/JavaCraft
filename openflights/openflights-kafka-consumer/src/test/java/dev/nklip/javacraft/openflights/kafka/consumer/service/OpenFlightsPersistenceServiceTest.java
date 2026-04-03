package dev.nklip.javacraft.openflights.kafka.consumer.service;

import dev.nklip.javacraft.openflights.api.Airline;
import dev.nklip.javacraft.openflights.api.Airport;
import dev.nklip.javacraft.openflights.api.Country;
import dev.nklip.javacraft.openflights.api.Plane;
import dev.nklip.javacraft.openflights.api.Route;
import dev.nklip.javacraft.openflights.jpa.entity.CountryEntity;
import dev.nklip.javacraft.openflights.jpa.entity.PlaneEntity;
import dev.nklip.javacraft.openflights.jpa.mapper.AirlineEntityMapper;
import dev.nklip.javacraft.openflights.jpa.mapper.AirportEntityMapper;
import dev.nklip.javacraft.openflights.jpa.mapper.CountryEntityMapper;
import dev.nklip.javacraft.openflights.jpa.mapper.PlaneEntityMapper;
import dev.nklip.javacraft.openflights.jpa.mapper.RouteEntityMapper;
import dev.nklip.javacraft.openflights.jpa.normalize.CountryNameNormalizer;
import dev.nklip.javacraft.openflights.jpa.entity.RouteEntity;
import dev.nklip.javacraft.openflights.jpa.repository.AirlineRepository;
import dev.nklip.javacraft.openflights.jpa.repository.AirportRepository;
import dev.nklip.javacraft.openflights.jpa.repository.CountryRepository;
import dev.nklip.javacraft.openflights.jpa.repository.PlaneRepository;
import dev.nklip.javacraft.openflights.jpa.repository.RouteRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenFlightsPersistenceServiceTest {

    @Test
    void testSaveCountryUpdatesExistingCountryWhenIsoCodeMatches() {
        CountryRepository countryRepository = mock(CountryRepository.class);
        OpenFlightsPersistenceService persistenceService = persistenceService(countryRepository, mock(AirlineRepository.class),
                mock(AirportRepository.class), mock(PlaneRepository.class), mock(RouteRepository.class));
        Country country = new Country("Russia", "RU", "RS");
        CountryEntity existing = new CountryEntity();
        existing.setName("Old Name");
        existing.setIsoCode("RU");
        existing.setDafifCode("OLD");
        when(countryRepository.findByIsoCode("RU")).thenReturn(Optional.of(existing));

        Assertions.assertDoesNotThrow(() -> persistenceService.saveCountry(country));

        Assertions.assertEquals("Russia", existing.getName());
        Assertions.assertEquals("RU", existing.getIsoCode());
        Assertions.assertEquals("RS", existing.getDafifCode());
        verify(countryRepository).save(existing);
    }

    @Test
    void testSaveCountryCreatesNewCountryWhenRepositoryHasNoMatch() {
        CountryRepository countryRepository = mock(CountryRepository.class);
        OpenFlightsPersistenceService persistenceService = persistenceService(countryRepository, mock(AirlineRepository.class),
                mock(AirportRepository.class), mock(PlaneRepository.class), mock(RouteRepository.class));
        Country country = new Country("Russia", "RU", "RS");
        when(countryRepository.findByIsoCode("RU")).thenReturn(Optional.empty());
        when(countryRepository.findByName("Russia")).thenReturn(Optional.empty());

        Assertions.assertDoesNotThrow(() -> persistenceService.saveCountry(country));

        verify(countryRepository).save(any(CountryEntity.class));
    }

    @Test
    void testSaveAirlineMapsAndSavesAirline() {
        AirlineRepository airlineRepository = mock(AirlineRepository.class);
        CountryRepository countryRepository = mock(CountryRepository.class);
        when(countryRepository.findByName("Cote d'Ivoire")).thenReturn(Optional.empty());
        OpenFlightsPersistenceService persistenceService = persistenceService(countryRepository, airlineRepository,
                mock(AirportRepository.class), mock(PlaneRepository.class), mock(RouteRepository.class));
        Airline airline = new Airline(410, "Ak Bars Aero", null, "2B", "BGB", null, "Ivory Coast", true);

        Assertions.assertDoesNotThrow(() -> persistenceService.saveAirline(airline));

        ArgumentCaptor<CountryEntity> placeholderCaptor = ArgumentCaptor.forClass(CountryEntity.class);
        ArgumentCaptor<dev.nklip.javacraft.openflights.jpa.entity.AirlineEntity> airlineCaptor =
                ArgumentCaptor.forClass(dev.nklip.javacraft.openflights.jpa.entity.AirlineEntity.class);
        verify(countryRepository).saveAndFlush(placeholderCaptor.capture());
        verify(airlineRepository).save(airlineCaptor.capture());
        Assertions.assertEquals("Cote d'Ivoire", placeholderCaptor.getValue().getName());
        Assertions.assertEquals("Cote d'Ivoire", airlineCaptor.getValue().getCountryName());
    }

    @Test
    void testSaveAirportMapsAndSavesAirport() {
        AirportRepository airportRepository = mock(AirportRepository.class);
        OpenFlightsPersistenceService persistenceService = persistenceService(mock(CountryRepository.class),
                mock(AirlineRepository.class), airportRepository, mock(PlaneRepository.class), mock(RouteRepository.class));
        Airport airport = new Airport(2965, "Sochi Airport", "Sochi", "WATCHDOG", "AER", "URSS",
                43.449902, 39.9566, 89, 3.0, "E", "Europe/Moscow", "airport", "OurAirports");

        Assertions.assertDoesNotThrow(() -> persistenceService.saveAirport(airport));

        ArgumentCaptor<dev.nklip.javacraft.openflights.jpa.entity.AirportEntity> airportCaptor =
                ArgumentCaptor.forClass(dev.nklip.javacraft.openflights.jpa.entity.AirportEntity.class);
        verify(airportRepository).save(airportCaptor.capture());
        Assertions.assertNull(airportCaptor.getValue().getCountryName());
    }

    @Test
    void testSaveAirlineReusesExistingPlaceholderCountryWhenAnotherTransactionCreatesItFirst() {
        CountryRepository countryRepository = mock(CountryRepository.class);
        AirlineRepository airlineRepository = mock(AirlineRepository.class);
        OpenFlightsPersistenceService persistenceService = persistenceService(countryRepository, airlineRepository,
                mock(AirportRepository.class), mock(PlaneRepository.class), mock(RouteRepository.class));
        Airline airline = new Airline(410, "Ak Bars Aero", null, "2B", "BGB", null, "Ivory Coast", true);
        CountryEntity existingPlaceholder = new CountryEntity();
        existingPlaceholder.setName("Cote d'Ivoire");

        when(countryRepository.findByName("Cote d'Ivoire"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existingPlaceholder));
        doThrow(new DataIntegrityViolationException("duplicate"))
                .when(countryRepository).saveAndFlush(any(CountryEntity.class));

        Assertions.assertDoesNotThrow(() -> persistenceService.saveAirline(airline));

        verify(countryRepository, atLeastOnce()).findByName(eq("Cote d'Ivoire"));
        verify(airlineRepository).save(any());
    }

    @Test
    void testSavePlaneUpdatesExistingPlaneWhenNaturalKeyMatches() {
        PlaneRepository planeRepository = mock(PlaneRepository.class);
        OpenFlightsPersistenceService persistenceService = persistenceService(mock(CountryRepository.class),
                mock(AirlineRepository.class), mock(AirportRepository.class), planeRepository, mock(RouteRepository.class));
        Plane plane = new Plane("Bombardier CRJ200", "CR2", "CRJ2");
        PlaneEntity existing = new PlaneEntity();
        existing.setName("Old Plane");
        existing.setIataCode("OLD");
        existing.setIcaoCode("CRJ2");
        when(planeRepository.findByNameAndIataCodeAndIcaoCode("Bombardier CRJ200", "CR2", "CRJ2"))
                .thenReturn(Optional.of(existing));

        Assertions.assertDoesNotThrow(() -> persistenceService.savePlane(plane));

        Assertions.assertEquals("Bombardier CRJ200", existing.getName());
        Assertions.assertEquals("CR2", existing.getIataCode());
        Assertions.assertEquals("CRJ2", existing.getIcaoCode());
        verify(planeRepository).save(existing);
    }

    @Test
    void testSaveRouteUpdatesExistingRouteWhenNaturalKeyMatches() {
        RouteRepository routeRepository = mock(RouteRepository.class);
        AirlineRepository airlineRepository = mock(AirlineRepository.class);
        AirportRepository airportRepository = mock(AirportRepository.class);
        OpenFlightsPersistenceService persistenceService = persistenceService(mock(CountryRepository.class),
                airlineRepository, airportRepository, mock(PlaneRepository.class), routeRepository);
        Route route = new Route("2B", 410, "AER", 2965, "KZN", 2990, false, 0, List.of("CR2", "CRJ"));
        RouteEntity requestedRoute = new RouteEntity();
        requestedRoute.setAirlineCode("2B");
        requestedRoute.setAirlineId(410);
        requestedRoute.setSourceAirportCode("AER");
        requestedRoute.setSourceAirportId(2965);
        requestedRoute.setDestinationAirportCode("KZN");
        requestedRoute.setDestinationAirportId(2990);
        requestedRoute.setCodeshare(false);
        requestedRoute.setStops(0);
        requestedRoute.refreshRouteKey();
        RouteEntity existing = new RouteEntity();
        existing.setAirlineCode("XX");
        existing.setAirlineId(410);
        existing.setSourceAirportCode("AER");
        existing.setSourceAirportId(2965);
        existing.setDestinationAirportCode("KZN");
        existing.setDestinationAirportId(2990);
        existing.setCodeshare(false);
        existing.setStops(0);
        when(airlineRepository.existsById(410)).thenReturn(true);
        when(airportRepository.existsById(2965)).thenReturn(true);
        when(airportRepository.existsById(2990)).thenReturn(true);
        when(routeRepository.findByRouteKey(requestedRoute.getRouteKey())).thenReturn(Optional.of(existing));

        Assertions.assertDoesNotThrow(() -> persistenceService.saveRoute(route));

        Assertions.assertEquals("2B", existing.getAirlineCode());
        Assertions.assertEquals(List.of("CR2", "CRJ"), existing.getEquipmentCodes());
        verify(routeRepository).save(existing);
    }

    @Test
    void testSaveRouteCreatesPlaceholderReferencesWhenAirlineAndAirportsAreMissing() {
        AirlineRepository airlineRepository = mock(AirlineRepository.class);
        AirportRepository airportRepository = mock(AirportRepository.class);
        RouteRepository routeRepository = mock(RouteRepository.class);
        OpenFlightsPersistenceService persistenceService = persistenceService(mock(CountryRepository.class),
                airlineRepository, airportRepository, mock(PlaneRepository.class), routeRepository);
        Route route = new Route("XI", 6557, "XIY", 7144, "CKG", 7145, false, 0, List.of("320"));

        when(airlineRepository.existsById(6557)).thenReturn(false);
        when(airportRepository.existsById(7144)).thenReturn(false);
        when(airportRepository.existsById(7145)).thenReturn(false);
        when(routeRepository.findByRouteKey("6557|XI|7144|XIY|7145|CKG|false|0")).thenReturn(Optional.empty());

        Assertions.assertDoesNotThrow(() -> persistenceService.saveRoute(route));

        ArgumentCaptor<dev.nklip.javacraft.openflights.jpa.entity.AirlineEntity> airlineCaptor =
                ArgumentCaptor.forClass(dev.nklip.javacraft.openflights.jpa.entity.AirlineEntity.class);
        ArgumentCaptor<dev.nklip.javacraft.openflights.jpa.entity.AirportEntity> airportCaptor =
                ArgumentCaptor.forClass(dev.nklip.javacraft.openflights.jpa.entity.AirportEntity.class);
        verify(airlineRepository).saveAndFlush(airlineCaptor.capture());
        verify(airportRepository, times(2)).saveAndFlush(airportCaptor.capture());
        verify(routeRepository).save(any(RouteEntity.class));

        Assertions.assertEquals(6557, airlineCaptor.getValue().getAirlineId());
        Assertions.assertEquals("Unknown airline 6557 (XI)", airlineCaptor.getValue().getName());
        Assertions.assertFalse(airlineCaptor.getValue().isActive());
        Assertions.assertEquals(List.of(7144, 7145), airportCaptor.getAllValues().stream()
                .map(dev.nklip.javacraft.openflights.jpa.entity.AirportEntity::getAirportId)
                .toList());
        Assertions.assertEquals(List.of("Unknown airport 7144 (XIY)", "Unknown airport 7145 (CKG)"),
                airportCaptor.getAllValues().stream()
                        .map(dev.nklip.javacraft.openflights.jpa.entity.AirportEntity::getName)
                        .toList());
    }

    @Test
    void testSaveRouteReusesPlaceholderReferencesCreatedByAnotherTransaction() {
        AirlineRepository airlineRepository = mock(AirlineRepository.class);
        AirportRepository airportRepository = mock(AirportRepository.class);
        RouteRepository routeRepository = mock(RouteRepository.class);
        OpenFlightsPersistenceService persistenceService = persistenceService(mock(CountryRepository.class),
                airlineRepository, airportRepository, mock(PlaneRepository.class), routeRepository);
        Route route = new Route("XI", 6557, "XIY", 7144, "CKG", 7145, false, 0, List.of("320"));

        when(airlineRepository.existsById(6557)).thenReturn(false, true);
        when(airportRepository.existsById(7144)).thenReturn(true);
        when(airportRepository.existsById(7145)).thenReturn(true);
        when(routeRepository.findByRouteKey("6557|XI|7144|XIY|7145|CKG|false|0")).thenReturn(Optional.empty());
        doThrow(new DataIntegrityViolationException("duplicate"))
                .when(airlineRepository).saveAndFlush(any(dev.nklip.javacraft.openflights.jpa.entity.AirlineEntity.class));

        Assertions.assertDoesNotThrow(() -> persistenceService.saveRoute(route));

        verify(airlineRepository, atLeastOnce()).existsById(6557);
        verify(routeRepository).save(any(RouteEntity.class));
    }

    private OpenFlightsPersistenceService persistenceService(
            CountryRepository countryRepository,
            AirlineRepository airlineRepository,
            AirportRepository airportRepository,
            PlaneRepository planeRepository,
            RouteRepository routeRepository
    ) {
        return new OpenFlightsPersistenceService(
                countryRepository,
                airlineRepository,
                airportRepository,
                planeRepository,
                routeRepository,
                new CountryEntityMapper(),
                new AirlineEntityMapper(),
                new AirportEntityMapper(),
                new PlaneEntityMapper(),
                new RouteEntityMapper(),
                new CountryNameNormalizer(),
                new OpenFlightsPlaceholderWriteService(countryRepository, airlineRepository, airportRepository)
        );
    }
}
