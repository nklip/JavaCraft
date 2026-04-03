package dev.nklip.javacraft.openflights.jpa.mapper;

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
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class OpenFlightsEntityMapperTest {

    @Test
    void countryEntityMapperCopiesAllFields() {
        Country country = new Country("Papua New Guinea", "PG", "PP");

        CountryEntity countryEntity = new CountryEntityMapper().toEntity(country);

        Assertions.assertEquals("Papua New Guinea", countryEntity.getName());
        Assertions.assertEquals("PG", countryEntity.getIsoCode());
        Assertions.assertEquals("PP", countryEntity.getDafifCode());
    }

    @Test
    void airlineEntityMapperCopiesAllFields() {
        Airline airline = new Airline(410, "Ak Bars Aero", null, "2B", "BGB", null, "Russia", true);

        AirlineEntity airlineEntity = new AirlineEntityMapper().toEntity(airline);

        Assertions.assertEquals(410, airlineEntity.getAirlineId());
        Assertions.assertEquals("Ak Bars Aero", airlineEntity.getName());
        Assertions.assertNull(airlineEntity.getAlias());
        Assertions.assertEquals("2B", airlineEntity.getIataCode());
        Assertions.assertEquals("BGB", airlineEntity.getIcaoCode());
        Assertions.assertNull(airlineEntity.getCallsign());
        Assertions.assertEquals("Russia", airlineEntity.getCountryName());
        Assertions.assertTrue(airlineEntity.isActive());
    }

    @Test
    void airlineEntityMapperAllowsOverridingCountryName() {
        Airline airline = new Airline(410, "Ak Bars Aero", null, "2B", "BGB", null, "Ivory Coast", true);

        AirlineEntity airlineEntity = new AirlineEntityMapper().toEntity(airline, "Cote d'Ivoire");

        Assertions.assertEquals("Cote d'Ivoire", airlineEntity.getCountryName());
    }

    @Test
    void airportEntityMapperCopiesAllFields() {
        Airport airport = new Airport(
                2965,
                "Sochi Airport",
                "Sochi",
                "Russia",
                "AER",
                "URSS",
                43.449902,
                39.9566,
                89,
                3.0,
                "E",
                "Europe/Moscow",
                "airport",
                "OurAirports"
        );

        AirportEntity airportEntity = new AirportEntityMapper().toEntity(airport);

        Assertions.assertEquals(2965, airportEntity.getAirportId());
        Assertions.assertEquals("Sochi Airport", airportEntity.getName());
        Assertions.assertEquals("Sochi", airportEntity.getCity());
        Assertions.assertEquals("Russia", airportEntity.getCountryName());
        Assertions.assertEquals("AER", airportEntity.getIataCode());
        Assertions.assertEquals("URSS", airportEntity.getIcaoCode());
        Assertions.assertEquals(43.449902, airportEntity.getLatitude());
        Assertions.assertEquals(39.9566, airportEntity.getLongitude());
        Assertions.assertEquals(89, airportEntity.getAltitudeFeet());
        Assertions.assertEquals(3.0, airportEntity.getTimezoneHours());
        Assertions.assertEquals("E", airportEntity.getDaylightSavingTime());
        Assertions.assertEquals("Europe/Moscow", airportEntity.getTimezoneName());
        Assertions.assertEquals("airport", airportEntity.getType());
        Assertions.assertEquals("OurAirports", airportEntity.getSource());
    }

    @Test
    void airportEntityMapperAllowsOverridingCountryName() {
        Airport airport = new Airport(
                2965,
                "Sochi Airport",
                "Sochi",
                "Brunei",
                "AER",
                "URSS",
                43.449902,
                39.9566,
                89,
                3.0,
                "E",
                "Europe/Moscow",
                "airport",
                "OurAirports"
        );

        AirportEntity airportEntity = new AirportEntityMapper().toEntity(airport, "Brunei Darussalam");

        Assertions.assertEquals("Brunei Darussalam", airportEntity.getCountryName());
    }

    @Test
    void planeEntityMapperCopiesAllFields() {
        Plane plane = new Plane("Bombardier CRJ200", "CR2", "CRJ2");

        PlaneEntity planeEntity = new PlaneEntityMapper().toEntity(plane);

        Assertions.assertEquals("Bombardier CRJ200", planeEntity.getName());
        Assertions.assertEquals("CR2", planeEntity.getIataCode());
        Assertions.assertEquals("CRJ2", planeEntity.getIcaoCode());
    }

    @Test
    void routeEntityMapperCopiesAllFields() {
        Route route = new Route("2B", 410, "AER", 2965, "KZN", 2990, false, 0, List.of("CR2", "CRJ"));

        RouteEntity routeEntity = new RouteEntityMapper().toEntity(route);

        Assertions.assertEquals("2B", routeEntity.getAirlineCode());
        Assertions.assertEquals(410, routeEntity.getAirlineId());
        Assertions.assertEquals("AER", routeEntity.getSourceAirportCode());
        Assertions.assertEquals(2965, routeEntity.getSourceAirportId());
        Assertions.assertEquals("KZN", routeEntity.getDestinationAirportCode());
        Assertions.assertEquals(2990, routeEntity.getDestinationAirportId());
        Assertions.assertFalse(routeEntity.isCodeshare());
        Assertions.assertEquals(0, routeEntity.getStops());
        Assertions.assertEquals(List.of("CR2", "CRJ"), routeEntity.getEquipmentCodes());
        Assertions.assertEquals("410|2B|2965|AER|2990|KZN|false|0", routeEntity.getRouteKey());
    }

    @Test
    void routeEntityMapperCopiesFieldsIntoExistingEntity() {
        Route route = new Route("2B", 410, "AER", 2965, "KZN", 2990, false, 0, List.of("CR2", "CRJ"));
        RouteEntity existingRouteEntity = new RouteEntity();
        existingRouteEntity.setAirlineCode("XX");
        existingRouteEntity.setDestinationAirportCode("OLD");

        RouteEntity routeEntity = new RouteEntityMapper().copyToEntity(route, existingRouteEntity);

        Assertions.assertSame(existingRouteEntity, routeEntity);
        Assertions.assertEquals("2B", routeEntity.getAirlineCode());
        Assertions.assertEquals("KZN", routeEntity.getDestinationAirportCode());
        Assertions.assertEquals("410|2B|2965|AER|2990|KZN|false|0", routeEntity.getRouteKey());
    }
}
