package dev.nklip.javacraft.openflights.data.parser;

import dev.nklip.javacraft.openflights.api.Route;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

public final class RouteDatParser {

    public Route parseLine(String line) throws IOException {
        return Route.fromColumns(OpenFlightsCsvSupport.parseLine(line));
    }

    public List<Route> parseFile(Path file) throws IOException {
        return OpenFlightsCsvSupport.parseFile(file, Route::fromColumns);
    }

    public List<Route> parseStream(InputStream inputStream) throws IOException {
        return OpenFlightsCsvSupport.parseStream(inputStream, Route::fromColumns);
    }
}
