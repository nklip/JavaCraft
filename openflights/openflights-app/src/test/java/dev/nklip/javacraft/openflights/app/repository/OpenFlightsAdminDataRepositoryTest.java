package dev.nklip.javacraft.openflights.app.repository;

import dev.nklip.javacraft.openflights.app.model.OpenFlightsDataCleanupResult;
import dev.nklip.javacraft.openflights.jpa.entity.AirlineEntity;
import dev.nklip.javacraft.openflights.jpa.entity.AirportEntity;
import dev.nklip.javacraft.openflights.jpa.entity.CountryEntity;
import dev.nklip.javacraft.openflights.jpa.entity.PlaneEntity;
import dev.nklip.javacraft.openflights.jpa.entity.RouteEntity;
import dev.nklip.javacraft.openflights.jpa.repository.AirlineRepository;
import dev.nklip.javacraft.openflights.jpa.repository.AirportRepository;
import dev.nklip.javacraft.openflights.jpa.repository.CountryRepository;
import dev.nklip.javacraft.openflights.jpa.repository.PlaneRepository;
import dev.nklip.javacraft.openflights.jpa.repository.RouteRepository;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@DataJpaTest
@Import(OpenFlightsAdminDataRepository.class)
class OpenFlightsAdminDataRepositoryTest {

    @Autowired
    private OpenFlightsAdminDataRepository adminDataRepository;

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
    void cleanAllDataDeletesEveryTableInForeignKeySafeOrder() {
        CountryEntity country = new CountryEntity();
        country.setName("Papua New Guinea");
        country.setIsoCode("PG");
        countryRepository.save(country);

        AirlineEntity airline = new AirlineEntity();
        airline.setAirlineId(410);
        airline.setName("Ak Bars Aero");
        airline.setCountryName(country.getName());
        airline.setActive(true);
        airlineRepository.save(airline);

        AirportEntity sourceAirport = new AirportEntity();
        sourceAirport.setAirportId(2965);
        sourceAirport.setName("Sochi Airport");
        sourceAirport.setCity("Sochi");
        sourceAirport.setCountryName(country.getName());
        sourceAirport.setIataCode("AER");
        airportRepository.save(sourceAirport);

        AirportEntity destinationAirport = new AirportEntity();
        destinationAirport.setAirportId(2990);
        destinationAirport.setName("Kazan Airport");
        destinationAirport.setCity("Kazan");
        destinationAirport.setCountryName(country.getName());
        destinationAirport.setIataCode("KZN");
        airportRepository.save(destinationAirport);

        PlaneEntity plane = new PlaneEntity();
        plane.setName("Bombardier CRJ200");
        plane.setIataCode("CR2");
        plane.setIcaoCode("CRJ2");
        planeRepository.save(plane);

        RouteEntity route = new RouteEntity();
        route.setAirlineCode("2B");
        route.setAirlineId(airline.getAirlineId());
        route.setSourceAirportCode(sourceAirport.getIataCode());
        route.setSourceAirportId(sourceAirport.getAirportId());
        route.setDestinationAirportCode(destinationAirport.getIataCode());
        route.setDestinationAirportId(destinationAirport.getAirportId());
        route.setCodeshare(false);
        route.setStops(0);
        route.setEquipmentCodes(List.of("CR2", "CRJ"));
        routeRepository.saveAndFlush(route);

        OpenFlightsDataCleanupResult result = adminDataRepository.cleanAllData();

        Assertions.assertEquals(1L, result.countriesDeleted());
        Assertions.assertEquals(1L, result.airlinesDeleted());
        Assertions.assertEquals(2L, result.airportsDeleted());
        Assertions.assertEquals(1L, result.planesDeleted());
        Assertions.assertEquals(1L, result.routesDeleted());
        Assertions.assertEquals(2L, result.routeEquipmentCodesDeleted());
        Assertions.assertEquals(8L, result.totalDeleted());
        Assertions.assertEquals(0L, countryRepository.count());
        Assertions.assertEquals(0L, airlineRepository.count());
        Assertions.assertEquals(0L, airportRepository.count());
        Assertions.assertEquals(0L, planeRepository.count());
        Assertions.assertEquals(0L, routeRepository.count());
        Assertions.assertEquals(0L, countRouteEquipmentCodes());
    }

    private long countRouteEquipmentCodes() {
        Long routeEquipmentCodes = jdbcTemplate.queryForObject("select count(*) from route_equipment_code", Long.class);
        Assertions.assertNotNull(routeEquipmentCodes);
        return routeEquipmentCodes;
    }
}
