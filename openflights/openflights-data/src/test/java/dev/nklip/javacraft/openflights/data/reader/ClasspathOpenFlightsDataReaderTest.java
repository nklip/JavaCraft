package dev.nklip.javacraft.openflights.data.reader;

import dev.nklip.javacraft.openflights.data.parser.AirlineDatParser;
import dev.nklip.javacraft.openflights.data.parser.AirportDatParser;
import dev.nklip.javacraft.openflights.data.parser.CountryDatParser;
import dev.nklip.javacraft.openflights.data.parser.PlaneDatParser;
import dev.nklip.javacraft.openflights.data.parser.RouteDatParser;
import dev.nklip.javacraft.openflights.data.reader.impl.ClasspathOpenFlightsDataReader;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ClasspathOpenFlightsDataReaderTest {

    @Test
    void readCountriesLoadsRecordsFromClasspathResource() {
        ClasspathOpenFlightsDataReader reader = new ClasspathOpenFlightsDataReader(
                new CountryDatParser(),
                new AirlineDatParser(),
                new AirportDatParser(),
                new PlaneDatParser(),
                new RouteDatParser()
        );

        Assertions.assertFalse(reader.readCountries().isEmpty());
        Assertions.assertTrue(reader.readCountries().stream().anyMatch(country ->
                "Papua New Guinea".equals(country.name()) && "PG".equals(country.isoCode())));
    }

    @Test
    void readAirlinesLoadsRecordsFromClasspathResource() {
        ClasspathOpenFlightsDataReader reader = new ClasspathOpenFlightsDataReader(
                new CountryDatParser(),
                new AirlineDatParser(),
                new AirportDatParser(),
                new PlaneDatParser(),
                new RouteDatParser()
        );

        Assertions.assertFalse(reader.readAirlines().isEmpty());
        Assertions.assertNotNull(reader.readAirlines().getFirst().name());
    }

    @Test
    void readAirportsLoadsRecordsFromClasspathResource() {
        ClasspathOpenFlightsDataReader reader = new ClasspathOpenFlightsDataReader(
                new CountryDatParser(),
                new AirlineDatParser(),
                new AirportDatParser(),
                new PlaneDatParser(),
                new RouteDatParser()
        );

        Assertions.assertFalse(reader.readAirports().isEmpty());
        Assertions.assertNotNull(reader.readAirports().getFirst().airportId());
    }

    @Test
    void readPlanesLoadsRecordsFromClasspathResource() {
        ClasspathOpenFlightsDataReader reader = new ClasspathOpenFlightsDataReader(
                new CountryDatParser(),
                new AirlineDatParser(),
                new AirportDatParser(),
                new PlaneDatParser(),
                new RouteDatParser()
        );

        Assertions.assertFalse(reader.readPlanes().isEmpty());
        Assertions.assertNotNull(reader.readPlanes().getFirst().name());
    }

    @Test
    void readRoutesLoadsRecordsFromClasspathResource() {
        ClasspathOpenFlightsDataReader reader = new ClasspathOpenFlightsDataReader(
                new CountryDatParser(),
                new AirlineDatParser(),
                new AirportDatParser(),
                new PlaneDatParser(),
                new RouteDatParser()
        );

        Assertions.assertFalse(reader.readRoutes().isEmpty());
        Assertions.assertNotNull(reader.readRoutes().getFirst().sourceAirportCode());
    }
}
