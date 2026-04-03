package dev.nklip.javacraft.openflights.testing.cucumber.step;

import dev.nklip.javacraft.openflights.api.Airline;
import dev.nklip.javacraft.openflights.api.Airport;
import dev.nklip.javacraft.openflights.api.Country;
import dev.nklip.javacraft.openflights.api.Plane;
import dev.nklip.javacraft.openflights.api.Route;
import dev.nklip.javacraft.openflights.data.reader.OpenFlightsDataReader;
import dev.nklip.javacraft.openflights.jpa.mapper.RouteEntityMapper;
import dev.nklip.javacraft.openflights.jpa.normalize.CountryNameNormalizer;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ExpectedOpenFlightsIngestionCalculatorTest {

    @Test
    void calculateBuildsIntermediateAndFinalDatabaseExpectations() {
        OpenFlightsDataReader dataReader = Mockito.mock(OpenFlightsDataReader.class);
        Mockito.when(dataReader.readCountries()).thenReturn(List.of(
                new Country("Peru", "PE", null),
                new Country("South Korea", "KR", null)
        ));
        Mockito.when(dataReader.readAirlines()).thenReturn(List.of(
                new Airline(1, "Aero Peru", null, "AP", "APR", null, "Peru", true),
                new Airline(2, "Korea Air", null, "KA", "KOR", null, "Republic of Korea", true),
                new Airline(3, "Placeholder Air", null, "PA", "PHA", null, "West Bank", true)
        ));
        Mockito.when(dataReader.readAirports()).thenReturn(List.of(
                new Airport(10, "Lima", "Lima", "Peru", "LIM", "SPJC",
                        null, null, null, null, null, null, null, null),
                new Airport(20, "Incheon", "Seoul", "Republic of Korea", "ICN", "RKSI",
                        null, null, null, null, null, null, null, null),
                new Airport(30, "Jerusalem", "Jerusalem", "West Bank", "JRS", "OJJR",
                        null, null, null, null, null, null, null, null)
        ));
        Mockito.when(dataReader.readPlanes()).thenReturn(List.of(
                new Plane("Boeing 737", "73G", "B737"),
                new Plane("Boeing 737", "73G", "B737")
        ));
        Mockito.when(dataReader.readRoutes()).thenReturn(List.of(
                new Route("AP", 1, "LIM", 10, "ICN", 20, false, 0, List.of("738", "320")),
                new Route("XY", 99, "AAA", 100, "BBB", 200, false, 1, List.of("333")),
                new Route("XY", 99, "AAA", 100, "BBB", 200, false, 1, List.of("333", "777"))
        ));

        ExpectedOpenFlightsIngestionCalculator calculator = new ExpectedOpenFlightsIngestionCalculator(
                dataReader,
                new CountryNameNormalizer(),
                new RouteEntityMapper()
        );

        OpenFlightsIngestionExpectations expectations = calculator.calculate();

        Assertions.assertEquals(2, expectations.submittedCountries());
        Assertions.assertEquals(3, expectations.submittedAirlines());
        Assertions.assertEquals(3, expectations.submittedAirports());
        Assertions.assertEquals(2, expectations.submittedPlanes());
        Assertions.assertEquals(3, expectations.submittedRoutes());
        Assertions.assertEquals(new OpenFlightsDatabaseStatus(2, 0, 0, 0, 0, 0),
                expectations.expectedAfterCountries());
        Assertions.assertEquals(new OpenFlightsDatabaseStatus(3, 3, 0, 0, 0, 0),
                expectations.expectedAfterAirlines());
        Assertions.assertEquals(new OpenFlightsDatabaseStatus(3, 3, 3, 0, 0, 0),
                expectations.expectedAfterAirports());
        Assertions.assertEquals(new OpenFlightsDatabaseStatus(3, 3, 3, 1, 0, 0),
                expectations.expectedAfterPlanes());
        Assertions.assertEquals(new OpenFlightsDatabaseStatus(3, 4, 5, 1, 2, 4),
                expectations.expectedDatabaseStatus());
    }
}
