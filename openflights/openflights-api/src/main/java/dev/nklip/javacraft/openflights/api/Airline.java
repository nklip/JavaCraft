package dev.nklip.javacraft.openflights.api;

import dev.nklip.javacraft.openflights.api.parser.OpenFlightsValueParser;
import java.util.List;

/**
 * Shared airline payload extracted from {@code airlines.dat}.
 */
public record Airline(
        Integer airlineId,
        String name,
        String alias,
        String iataCode,
        String icaoCode,
        String callsign,
        String country,
        boolean active
) {

    public static Airline fromColumns(List<String> columns) {
        OpenFlightsValueParser.requireColumnCount(columns, 8, "airline");
        return new Airline(
                OpenFlightsValueParser.nullableInteger(columns.get(0)),
                OpenFlightsValueParser.nullableText(columns.get(1)),
                OpenFlightsValueParser.nullableText(columns.get(2)),
                OpenFlightsValueParser.nullableText(columns.get(3)),
                OpenFlightsValueParser.nullableText(columns.get(4)),
                OpenFlightsValueParser.nullableText(columns.get(5)),
                OpenFlightsValueParser.nullableText(columns.get(6)),
                OpenFlightsValueParser.yesNoFlag(columns.get(7))
        );
    }
}
