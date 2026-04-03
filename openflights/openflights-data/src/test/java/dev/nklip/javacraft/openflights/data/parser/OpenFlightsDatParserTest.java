package dev.nklip.javacraft.openflights.data.parser;

import dev.nklip.javacraft.openflights.api.Airline;
import dev.nklip.javacraft.openflights.api.Airport;
import dev.nklip.javacraft.openflights.api.Country;
import dev.nklip.javacraft.openflights.api.Plane;
import dev.nklip.javacraft.openflights.api.Route;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class OpenFlightsDatParserTest {

    private static final Path DATA_DIR = Path.of("src", "main", "resources", "data");

    @Test
    void testAirlineDatParserParsesOpenFlightsNullTokens() throws IOException {
        Airline airline = new AirlineDatParser().parseLine("-1,\"Unknown\",\\N,\"-\",\"N/A\",\\N,\\N,\"Y\"");

        Assertions.assertEquals(-1, airline.airlineId());
        Assertions.assertEquals("Unknown", airline.name());
        Assertions.assertNull(airline.alias());
        Assertions.assertEquals("-", airline.iataCode());
        Assertions.assertEquals("N/A", airline.icaoCode());
        Assertions.assertNull(airline.callsign());
        Assertions.assertNull(airline.country());
        Assertions.assertTrue(airline.active());
    }

    @Test
    void testAirportDatParserParsesRealOpenFlightsFile() throws IOException {
        List<Airport> airports = new AirportDatParser().parseFile(DATA_DIR.resolve("airports.dat"));

        Assertions.assertFalse(airports.isEmpty());
        Airport firstAirport = airports.getFirst();
        Assertions.assertEquals(1, firstAirport.airportId());
        Assertions.assertEquals("Goroka Airport", firstAirport.name());
        Assertions.assertEquals("Papua New Guinea", firstAirport.country());
        Assertions.assertEquals("GKA", firstAirport.iataCode());
    }

    @Test
    void testCountryDatParserParsesRealOpenFlightsFileFromStream() throws IOException {
        try (InputStream inputStream = OpenFlightsDatParserTest.class.getResourceAsStream("/data/countries.dat")) {
            Assertions.assertNotNull(inputStream);

            List<Country> countries = new CountryDatParser().parseStream(inputStream);

            Assertions.assertFalse(countries.isEmpty());
            Assertions.assertTrue(countries.stream().anyMatch(country ->
                    "Papua New Guinea".equals(country.name()) && "PG".equals(country.isoCode())));
        }
    }

    @Test
    void testRouteDatParserParsesRealOpenFlightsFileFromStream() throws IOException {
        try (InputStream inputStream = OpenFlightsDatParserTest.class.getResourceAsStream("/data/routes.dat")) {
            Assertions.assertNotNull(inputStream);

            List<Route> routes = new RouteDatParser().parseStream(inputStream);

            Assertions.assertFalse(routes.isEmpty());
            Assertions.assertNotNull(routes.getFirst().sourceAirportCode());
        }
    }

    @Test
    void testCountryDatParserParsesQuotedCountryName() throws IOException {
        Country country = new CountryDatParser().parseLine("\"Bonaire, Saint Eustatius and Saba\",\"BQ\",\"\"");

        Assertions.assertEquals("Bonaire, Saint Eustatius and Saba", country.name());
        Assertions.assertEquals("BQ", country.isoCode());
        Assertions.assertNull(country.dafifCode());
    }

    @Test
    void testPlaneDatParserParsesPlaneCodes() throws IOException {
        Plane plane = new PlaneDatParser().parseLine("\"Aerospatiale (Nord) 262\",\"ND2\",\"N262\"");

        Assertions.assertEquals("Aerospatiale (Nord) 262", plane.name());
        Assertions.assertEquals("ND2", plane.iataCode());
        Assertions.assertEquals("N262", plane.icaoCode());
    }

    @Test
    void testRouteDatParserParsesEquipmentCodes() throws IOException {
        Route route = new RouteDatParser().parseLine("2B,410,AER,2965,KZN,2990,,0,\"CR2 CRJ\"");

        Assertions.assertEquals("2B", route.airlineCode());
        Assertions.assertEquals(410, route.airlineId());
        Assertions.assertEquals("AER", route.sourceAirportCode());
        Assertions.assertEquals(2965, route.sourceAirportId());
        Assertions.assertEquals("KZN", route.destinationAirportCode());
        Assertions.assertEquals(2990, route.destinationAirportId());
        Assertions.assertFalse(route.codeshare());
        Assertions.assertEquals(0, route.stops());
        Assertions.assertEquals(List.of("CR2", "CRJ"), route.equipmentCodes());
    }
}
