package dev.nklip.javacraft.openflights.data.parser;

import dev.nklip.javacraft.openflights.api.Country;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

public final class CountryDatParser {

    public Country parseLine(String line) throws IOException {
        return Country.fromColumns(OpenFlightsCsvSupport.parseLine(line));
    }

    public List<Country> parseFile(Path file) throws IOException {
        return OpenFlightsCsvSupport.parseFile(file, Country::fromColumns);
    }

    public List<Country> parseStream(InputStream inputStream) throws IOException {
        return OpenFlightsCsvSupport.parseStream(inputStream, Country::fromColumns);
    }
}
