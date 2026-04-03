package dev.nklip.javacraft.openflights.api;

import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;
import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class OpenFlightsApiTest {

    @Test
    void airlineFromColumnsMapsOpenFlightsNullTokens() {
        Airline airline = Airline.fromColumns(List.of("-1", "Unknown", "\\N", "-", "N/A", "\\N", "\\N", "Y"));

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
    void airportFromColumnsMapsNumericAndTextFields() throws IOException {
        Airport airport = Airport.fromColumns(parseCsvLine(
                "1,\"Goroka Airport\",\"Goroka\",\"Papua New Guinea\",\"GKA\",\"AYGA\",-6.081689834590001,145.391998291,5282,10,\"U\",\"Pacific/Port_Moresby\",\"airport\",\"OurAirports\""
        ));

        Assertions.assertEquals(1, airport.airportId());
        Assertions.assertEquals("Goroka Airport", airport.name());
        Assertions.assertEquals("Goroka", airport.city());
        Assertions.assertEquals("Papua New Guinea", airport.country());
        Assertions.assertEquals("GKA", airport.iataCode());
        Assertions.assertEquals("AYGA", airport.icaoCode());
        Assertions.assertEquals(-6.081689834590001, airport.latitude());
        Assertions.assertEquals(145.391998291, airport.longitude());
        Assertions.assertEquals(5282, airport.altitudeFeet());
        Assertions.assertEquals(10.0, airport.timezoneHours());
        Assertions.assertEquals("U", airport.daylightSavingTime());
        Assertions.assertEquals("Pacific/Port_Moresby", airport.timezoneName());
        Assertions.assertEquals("airport", airport.type());
        Assertions.assertEquals("OurAirports", airport.source());
    }

    @Test
    void countryFromColumnsKeepsCountryNameWithComma() throws IOException {
        Country country = Country.fromColumns(parseCsvLine("\"Bonaire, Saint Eustatius and Saba\",\"BQ\",\"\""));

        Assertions.assertEquals("Bonaire, Saint Eustatius and Saba", country.name());
        Assertions.assertEquals("BQ", country.isoCode());
        Assertions.assertNull(country.dafifCode());
    }

    @Test
    void planeFromColumnsMapsCodes() throws IOException {
        Plane plane = Plane.fromColumns(parseCsvLine("\"Aerospatiale (Nord) 262\",\"ND2\",\"N262\""));

        Assertions.assertEquals("Aerospatiale (Nord) 262", plane.name());
        Assertions.assertEquals("ND2", plane.iataCode());
        Assertions.assertEquals("N262", plane.icaoCode());
    }

    @Test
    void routeFromColumnsSplitsEquipmentCodes() throws IOException {
        Route route = Route.fromColumns(parseCsvLine("2B,410,AER,2965,KZN,2990,,0,\"CR2 CRJ\""));

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

    private static List<String> parseCsvLine(String line) throws IOException {
        try (CSVReader csvReader = new CSVReader(new StringReader(line))) {
            try {
                return List.of(csvReader.readNext());
            } catch (CsvValidationException e) {
                throw new IOException("Failed to parse OpenFlights CSV line", e);
            }
        }
    }
}
