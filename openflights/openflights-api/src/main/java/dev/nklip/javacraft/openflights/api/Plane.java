package dev.nklip.javacraft.openflights.api;

import dev.nklip.javacraft.openflights.api.parser.OpenFlightsValueParser;
import java.util.List;

/**
 * Shared plane payload extracted from {@code planes.dat}.
 */
public record Plane(
        String name,
        String iataCode,
        String icaoCode
) {

    public static Plane fromColumns(List<String> columns) {
        OpenFlightsValueParser.requireColumnCount(columns, 3, "plane");
        return new Plane(
                OpenFlightsValueParser.nullableText(columns.get(0)),
                OpenFlightsValueParser.nullableText(columns.get(1)),
                OpenFlightsValueParser.nullableText(columns.get(2))
        );
    }
}
