package dev.nklip.javacraft.openflights.kafka.consumer.service;

import dev.nklip.javacraft.openflights.api.Airline;
import dev.nklip.javacraft.openflights.api.Airport;
import dev.nklip.javacraft.openflights.api.Country;
import dev.nklip.javacraft.openflights.api.Plane;
import dev.nklip.javacraft.openflights.api.Route;
import dev.nklip.javacraft.openflights.jpa.entity.AirlineEntity;
import dev.nklip.javacraft.openflights.jpa.entity.AirportEntity;
import dev.nklip.javacraft.openflights.jpa.entity.CountryEntity;
import dev.nklip.javacraft.openflights.jpa.entity.RouteEntity;
import dev.nklip.javacraft.openflights.jpa.repository.AirlineRepository;
import dev.nklip.javacraft.openflights.jpa.repository.AirportRepository;
import dev.nklip.javacraft.openflights.jpa.repository.CountryRepository;
import dev.nklip.javacraft.openflights.jpa.repository.PlaneRepository;
import dev.nklip.javacraft.openflights.jpa.repository.RouteRepository;
import dev.nklip.javacraft.openflights.kafka.consumer.OpenFlightsConsumerJpaTestApplication;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(classes = OpenFlightsConsumerJpaTestApplication.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Transactional
class OpenFlightsPersistenceServiceIntegrationTest {

    @Autowired
    private OpenFlightsPersistenceService persistenceService;

    @Autowired
    private CountryRepository countryRepository;

    @Autowired
    private AirlineRepository airlineRepository;

    @Autowired
    private AirportRepository airportRepository;

    @Autowired
    private PlaneRepository planeRepository;

    @Autowired
    private RouteRepository routeRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void testDuplicateMessagesDoNotCreateAdditionalRows() {
        Country country = new Country("Papua New Guinea", "PG", "PP");
        Airline airline = new Airline(410, "Ak Bars Aero", null, "2B", "BGB", null, "Papua New Guinea", true);
        Airport sourceAirport = new Airport(2965, "Sochi Airport", "Sochi", "Papua New Guinea", "AER", "URSS",
                43.449902, 39.9566, 89, 3.0, "E", "Europe/Moscow", "airport", "OurAirports");
        Airport destinationAirport = new Airport(2990, "Kazan Airport", "Kazan", "Papua New Guinea", "KZN", "UWKD",
                55.606201, 49.278702, 411, 3.0, "E", "Europe/Moscow", "airport", "OurAirports");
        Plane plane = new Plane("Bombardier CRJ200", "CR2", "CRJ2");
        Route route = new Route("2B", 410, "AER", 2965, "KZN", 2990, false, 0, List.of("CR2", "CRJ"));

        ingestAll(country, airline, sourceAirport, destinationAirport, plane, route);
        assertCounts(1L, 1L, 2L, 1L, 1L);
        assertEquipmentCodeCount(2L);

        ingestAll(country, airline, sourceAirport, destinationAirport, plane, route);
        assertCounts(1L, 1L, 2L, 1L, 1L);
        assertEquipmentCodeCount(2L);
    }

    @Test
    void testAlternateCountryNamesAreNormalizedAndCreatePlaceholderCountries() {
        Airline airline = new Airline(99901, "Alias Airline", null, "AA", "ALS", null, "Ivory Coast", true);
        Airport airport = new Airport(99902, "Alias Airport", "Bandar Seri Begawan", "Brunei", "BWN", "WBSB",
                4.9442, 114.9284, 73, 8.0, "N", "Asia/Brunei", "airport", "OurAirports");

        Assertions.assertDoesNotThrow(() -> persistenceService.saveAirline(airline));
        Assertions.assertDoesNotThrow(() -> persistenceService.saveAirport(airport));

        CountryEntity ivoryCoast = countryRepository.findByName("Cote d'Ivoire").orElseThrow();
        CountryEntity brunei = countryRepository.findByName("Brunei Darussalam").orElseThrow();
        AirlineEntity storedAirline = airlineRepository.findById(99901).orElseThrow();
        AirportEntity storedAirport = airportRepository.findById(99902).orElseThrow();

        Assertions.assertNull(ivoryCoast.getIsoCode());
        Assertions.assertNull(brunei.getIsoCode());
        Assertions.assertEquals("Cote d'Ivoire", storedAirline.getCountryName());
        Assertions.assertEquals("Brunei Darussalam", storedAirport.getCountryName());

        Assertions.assertDoesNotThrow(() -> persistenceService.saveCountry(new Country("Cote d'Ivoire", "CI", "IV")));

        CountryEntity updatedIvoryCoast = countryRepository.findByName("Cote d'Ivoire").orElseThrow();
        Assertions.assertEquals("CI", updatedIvoryCoast.getIsoCode());
        Assertions.assertEquals("IV", updatedIvoryCoast.getDafifCode());
    }

    @Test
    void testNonCountryGarbageValuesAreStoredAsNullCountryReferences() {
        Airline airline = new Airline(99903, "Garbage Airline", null, "GA", "GBG", null, "AEROPERLAS", true);
        Airport airport = new Airport(99904, "Garbage Airport", "Unknown", "WATCHDOG", "UNK", "ZZZZ",
                0.0, 0.0, 0, 0.0, "N", "UTC", "airport", "OurAirports");

        Assertions.assertDoesNotThrow(() -> persistenceService.saveAirline(airline));
        Assertions.assertDoesNotThrow(() -> persistenceService.saveAirport(airport));

        AirlineEntity storedAirline = airlineRepository.findById(99903).orElseThrow();
        AirportEntity storedAirport = airportRepository.findById(99904).orElseThrow();

        Assertions.assertNull(storedAirline.getCountryName());
        Assertions.assertNull(storedAirport.getCountryName());
    }

    @Test
    void testOverlappingPlaneCodesRemainDistinctAndReplayIsStable() {
        Plane legacy600 = new Plane("Embraer Legacy 600", "ER3", "E35L");
        Plane rj135 = new Plane("Embraer RJ135", "ER3", "E135");
        Plane rj140 = new Plane("Embraer RJ140", "ERD", "E135");

        Assertions.assertDoesNotThrow(() -> persistenceService.savePlane(legacy600));
        Assertions.assertDoesNotThrow(() -> persistenceService.savePlane(rj135));
        Assertions.assertDoesNotThrow(() -> persistenceService.savePlane(rj140));
        Assertions.assertEquals(3L, planeRepository.count());

        Assertions.assertDoesNotThrow(() -> persistenceService.savePlane(legacy600));
        Assertions.assertDoesNotThrow(() -> persistenceService.savePlane(rj135));
        Assertions.assertDoesNotThrow(() -> persistenceService.savePlane(rj140));
        Assertions.assertEquals(3L, planeRepository.count());
    }

    @Test
    void testSaveRouteCreatesPlaceholderAirlineAndAirportReferencesThatRealDataCanLaterReplace() {
        Route route = new Route("XI", 6557, "XIY", 7144, "CKG", 7145, false, 0, List.of("320"));

        Assertions.assertDoesNotThrow(() -> persistenceService.saveRoute(route));

        AirlineEntity placeholderAirline = airlineRepository.findById(6557).orElseThrow();
        AirportEntity placeholderSourceAirport = airportRepository.findById(7144).orElseThrow();
        AirportEntity placeholderDestinationAirport = airportRepository.findById(7145).orElseThrow();
        RouteEntity storedRoute = routeRepository.findByRouteKey("6557|XI|7144|XIY|7145|CKG|false|0").orElseThrow();

        Assertions.assertEquals("Unknown airline 6557 (XI)", placeholderAirline.getName());
        Assertions.assertEquals("Unknown airport 7144 (XIY)", placeholderSourceAirport.getName());
        Assertions.assertEquals("Unknown airport 7145 (CKG)", placeholderDestinationAirport.getName());
        Assertions.assertEquals(1L, routeRepository.count());
        Assertions.assertEquals(6557, storedRoute.getAirlineId());
        Assertions.assertEquals(7144, storedRoute.getSourceAirportId());
        Assertions.assertEquals(7145, storedRoute.getDestinationAirportId());

        Airline airline = new Airline(6557, "Route Airline", null, "XI", "XIY", null, null, true);
        Airport sourceAirport = new Airport(7144, "Xi'an Xianyang", "Xi'an", null, "XIY", "ZLXY",
                34.447102, 108.751999, 1572, 8.0, "N", "Asia/Shanghai", "airport", "OpenFlights");
        Airport destinationAirport = new Airport(7145, "Chongqing Jiangbei", "Chongqing", null, "CKG", "ZUCK",
                29.7192, 106.641998, 1365, 8.0, "N", "Asia/Shanghai", "airport", "OpenFlights");

        Assertions.assertDoesNotThrow(() -> persistenceService.saveAirline(airline));
        Assertions.assertDoesNotThrow(() -> persistenceService.saveAirport(sourceAirport));
        Assertions.assertDoesNotThrow(() -> persistenceService.saveAirport(destinationAirport));

        Assertions.assertEquals("Route Airline", airlineRepository.findById(6557).orElseThrow().getName());
        Assertions.assertEquals("Xi'an Xianyang", airportRepository.findById(7144).orElseThrow().getName());
        Assertions.assertEquals("Chongqing Jiangbei", airportRepository.findById(7145).orElseThrow().getName());
        Assertions.assertEquals(1L, routeRepository.count());
    }

    private void ingestAll(
            Country country,
            Airline airline,
            Airport sourceAirport,
            Airport destinationAirport,
            Plane plane,
            Route route
    ) {
        Assertions.assertDoesNotThrow(() -> persistenceService.saveCountry(country));
        Assertions.assertDoesNotThrow(() -> persistenceService.saveAirline(airline));
        Assertions.assertDoesNotThrow(() -> persistenceService.saveAirport(sourceAirport));
        Assertions.assertDoesNotThrow(() -> persistenceService.saveAirport(destinationAirport));
        Assertions.assertDoesNotThrow(() -> persistenceService.savePlane(plane));
        Assertions.assertDoesNotThrow(() -> persistenceService.saveRoute(route));
    }

    private void assertCounts(
            long countries,
            long airlines,
            long airports,
            long planes,
            long routes
    ) {
        Assertions.assertEquals(countries, countryRepository.count());
        Assertions.assertEquals(airlines, airlineRepository.count());
        Assertions.assertEquals(airports, airportRepository.count());
        Assertions.assertEquals(planes, planeRepository.count());
        Assertions.assertEquals(routes, routeRepository.count());
    }

    private void assertEquipmentCodeCount(long equipmentCodes) {
        routeRepository.flush();
        Long actual = jdbcTemplate.queryForObject("select count(*) from route_equipment_code", Long.class);
        Assertions.assertEquals(equipmentCodes, actual);
    }
}
