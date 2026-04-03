package dev.nklip.javacraft.openflights.data.parser;

import dev.nklip.javacraft.openflights.api.Airport;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

public final class AirportDatParser {

    public Airport parseLine(String line) throws IOException {
        return Airport.fromColumns(OpenFlightsCsvSupport.parseLine(line));
    }

    public List<Airport> parseFile(Path file) throws IOException {
        return OpenFlightsCsvSupport.parseFile(file, Airport::fromColumns);
    }

    public List<Airport> parseStream(InputStream inputStream) throws IOException {
        return OpenFlightsCsvSupport.parseStream(inputStream, Airport::fromColumns);
    }
}
