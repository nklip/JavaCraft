package dev.nklip.javacraft.openflights.data.reader.impl;

import dev.nklip.javacraft.openflights.api.Airline;
import dev.nklip.javacraft.openflights.api.Airport;
import dev.nklip.javacraft.openflights.api.Country;
import dev.nklip.javacraft.openflights.api.Plane;
import dev.nklip.javacraft.openflights.api.Route;
import dev.nklip.javacraft.openflights.data.parser.AirlineDatParser;
import dev.nklip.javacraft.openflights.data.parser.AirportDatParser;
import dev.nklip.javacraft.openflights.data.parser.CountryDatParser;
import dev.nklip.javacraft.openflights.data.parser.PlaneDatParser;
import dev.nklip.javacraft.openflights.data.parser.RouteDatParser;
import dev.nklip.javacraft.openflights.data.reader.OpenFlightsDataReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

public class ClasspathOpenFlightsDataReader implements OpenFlightsDataReader {

    private static final String DATA_DIR = "data/";

    private final CountryDatParser countryDatParser;
    private final AirlineDatParser airlineDatParser;
    private final AirportDatParser airportDatParser;
    private final PlaneDatParser planeDatParser;
    private final RouteDatParser routeDatParser;

    public ClasspathOpenFlightsDataReader(
            CountryDatParser countryDatParser,
            AirlineDatParser airlineDatParser,
            AirportDatParser airportDatParser,
            PlaneDatParser planeDatParser,
            RouteDatParser routeDatParser
    ) {
        this.countryDatParser = countryDatParser;
        this.airlineDatParser = airlineDatParser;
        this.airportDatParser = airportDatParser;
        this.planeDatParser = planeDatParser;
        this.routeDatParser = routeDatParser;
    }

    @Override
    public List<Country> readCountries() {
        return read(DATA_DIR + "countries.dat", countryDatParser::parseStream);
    }

    @Override
    public List<Airline> readAirlines() {
        return read(DATA_DIR + "airlines.dat", airlineDatParser::parseStream);
    }

    @Override
    public List<Airport> readAirports() {
        return read(DATA_DIR + "airports.dat", airportDatParser::parseStream);
    }

    @Override
    public List<Plane> readPlanes() {
        return read(DATA_DIR + "planes.dat", planeDatParser::parseStream);
    }

    @Override
    public List<Route> readRoutes() {
        return read(DATA_DIR + "routes.dat", routeDatParser::parseStream);
    }

    private <T> List<T> read(String resourcePath, StreamParser<T> parser) {
        try (InputStream inputStream = openResource(resourcePath)) {
            return parser.parse(inputStream);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read OpenFlights resource: " + resourcePath, e);
        }
    }

    private InputStream openResource(String resourcePath) throws IOException {
        InputStream inputStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath);
        if (inputStream == null) {
            throw new IOException("OpenFlights resource not found: " + resourcePath);
        }
        return inputStream;
    }

    @FunctionalInterface
    private interface StreamParser<T> {
        List<T> parse(InputStream inputStream) throws IOException;
    }
}
