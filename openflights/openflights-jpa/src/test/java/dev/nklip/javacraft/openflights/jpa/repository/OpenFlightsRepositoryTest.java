package dev.nklip.javacraft.openflights.jpa.repository;

import dev.nklip.javacraft.openflights.jpa.entity.AirlineEntity;
import dev.nklip.javacraft.openflights.jpa.entity.AirportEntity;
import dev.nklip.javacraft.openflights.jpa.entity.CountryEntity;
import dev.nklip.javacraft.openflights.jpa.entity.PlaneEntity;
import dev.nklip.javacraft.openflights.jpa.entity.RouteEntity;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

@DataJpaTest
class OpenFlightsRepositoryTest {

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
    private TestEntityManager entityManager;

    @Test
    void testRepositoriesPersistAndLoadEntitiesWithRelationships() {
        CountryEntity country = new CountryEntity();
        country.setName("Papua New Guinea");
        country.setIsoCode("PG");
        country.setDafifCode("PP");
        CountryEntity savedCountry = countryRepository.save(country);

        AirlineEntity airline = new AirlineEntity();
        airline.setAirlineId(410);
        airline.setName("Ak Bars Aero");
        airline.setCountryName(savedCountry.getName());
        airline.setActive(true);
        airlineRepository.save(airline);

        AirportEntity sourceAirport = new AirportEntity();
        sourceAirport.setAirportId(2965);
        sourceAirport.setName("Sochi Airport");
        sourceAirport.setCity("Sochi");
        sourceAirport.setCountryName(savedCountry.getName());
        sourceAirport.setIataCode("AER");
        airportRepository.save(sourceAirport);

        AirportEntity destinationAirport = new AirportEntity();
        destinationAirport.setAirportId(2990);
        destinationAirport.setName("Kazan Airport");
        destinationAirport.setCity("Kazan");
        destinationAirport.setCountryName(savedCountry.getName());
        destinationAirport.setIataCode("KZN");
        airportRepository.save(destinationAirport);

        PlaneEntity plane = new PlaneEntity();
        plane.setName("Bombardier CRJ200");
        plane.setIataCode("CR2");
        plane.setIcaoCode("CRJ2");
        PlaneEntity savedPlane = planeRepository.save(plane);

        RouteEntity route = new RouteEntity();
        route.setAirlineCode("2B");
        route.setAirlineId(airline.getAirlineId());
        route.setSourceAirportCode(sourceAirport.getIataCode());
        route.setSourceAirportId(sourceAirport.getAirportId());
        route.setDestinationAirportCode(destinationAirport.getIataCode());
        route.setDestinationAirportId(destinationAirport.getAirportId());
        route.setCodeshare(false);
        route.setStops(0);
        route.setEquipmentCodes(List.of(savedPlane.getIataCode(), "CRJ"));
        RouteEntity savedRoute = routeRepository.save(route);

        Assertions.assertEquals(savedCountry.getName(), countryRepository.findByIsoCode("PG")
                .orElseThrow()
                .getName());
        Assertions.assertEquals(1, airlineRepository.findByCountryName(savedCountry.getName()).size());
        Assertions.assertEquals(2, airportRepository.findByCountryName(savedCountry.getName()).size());
        Assertions.assertEquals("Bombardier CRJ200", planeRepository.findByIataCode("CR2")
                .orElseThrow()
                .getName());
        Assertions.assertEquals(1, routeRepository.findByAirlineId(410).size());

        entityManager.flush();
        entityManager.clear();

        RouteEntity persistedRoute = routeRepository.findById(savedRoute.getId()).orElseThrow();
        Assertions.assertEquals("Ak Bars Aero", persistedRoute.getAirline().getName());
        Assertions.assertEquals("Sochi Airport", persistedRoute.getSourceAirport().getName());
        Assertions.assertEquals("Kazan Airport", persistedRoute.getDestinationAirport().getName());
        Assertions.assertEquals(savedCountry.getName(), persistedRoute.getSourceAirport().getCountry().getName());
        Assertions.assertEquals(List.of("CR2", "CRJ"), persistedRoute.getEquipmentCodes());
        Assertions.assertEquals(savedRoute.getRouteKey(), routeRepository.findByRouteKey(savedRoute.getRouteKey())
                .orElseThrow()
                .getRouteKey());
    }

    @Test
    void testRouteRepositoryRejectsDuplicateNaturalKey() {
        RouteEntity firstRoute = new RouteEntity();
        firstRoute.setAirlineCode("2B");
        firstRoute.setSourceAirportCode("AER");
        firstRoute.setDestinationAirportCode("KZN");
        firstRoute.setCodeshare(false);
        firstRoute.setStops(0);
        firstRoute.setEquipmentCodes(List.of("CR2", "CRJ"));
        routeRepository.save(firstRoute);
        entityManager.flush();

        RouteEntity duplicateRoute = new RouteEntity();
        duplicateRoute.setAirlineCode("2B");
        duplicateRoute.setSourceAirportCode("AER");
        duplicateRoute.setDestinationAirportCode("KZN");
        duplicateRoute.setCodeshare(false);
        duplicateRoute.setStops(0);
        duplicateRoute.setEquipmentCodes(List.of("CR2", "CRJ"));

        Assertions.assertThrows(DataIntegrityViolationException.class, () -> {
            routeRepository.saveAndFlush(duplicateRoute);
        });
    }
}
