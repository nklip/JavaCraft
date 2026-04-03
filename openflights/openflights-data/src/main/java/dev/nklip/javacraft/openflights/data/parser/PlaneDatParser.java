package dev.nklip.javacraft.openflights.data.parser;

import dev.nklip.javacraft.openflights.api.Plane;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

public final class PlaneDatParser {

    public Plane parseLine(String line) throws IOException {
        return Plane.fromColumns(OpenFlightsCsvSupport.parseLine(line));
    }

    public List<Plane> parseFile(Path file) throws IOException {
        return OpenFlightsCsvSupport.parseFile(file, Plane::fromColumns);
    }

    public List<Plane> parseStream(InputStream inputStream) throws IOException {
        return OpenFlightsCsvSupport.parseStream(inputStream, Plane::fromColumns);
    }
}
