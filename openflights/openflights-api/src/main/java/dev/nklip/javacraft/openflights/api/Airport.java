package dev.nklip.javacraft.openflights.api;

import dev.nklip.javacraft.openflights.api.parser.OpenFlightsValueParser;
import java.util.List;

/**
 * Shared airport payload extracted from {@code airports.dat}.
 */
public record Airport(
        Integer airportId,
        String name,
        String city,
        String country,
        String iataCode,
        String icaoCode,
        Double latitude,
        Double longitude,
        Integer altitudeFeet,
        Double timezoneHours,
        String daylightSavingTime,
        String timezoneName,
        String type,
        String source
) {

    public static Airport fromColumns(List<String> columns) {
        OpenFlightsValueParser.requireColumnCount(columns, 14, "airport");
        return new Airport(
                OpenFlightsValueParser.nullableInteger(columns.get(0)),
                OpenFlightsValueParser.nullableText(columns.get(1)),
                OpenFlightsValueParser.nullableText(columns.get(2)),
                OpenFlightsValueParser.nullableText(columns.get(3)),
                OpenFlightsValueParser.nullableText(columns.get(4)),
                OpenFlightsValueParser.nullableText(columns.get(5)),
                OpenFlightsValueParser.nullableDouble(columns.get(6)),
                OpenFlightsValueParser.nullableDouble(columns.get(7)),
                OpenFlightsValueParser.nullableInteger(columns.get(8)),
                OpenFlightsValueParser.nullableDouble(columns.get(9)),
                OpenFlightsValueParser.nullableText(columns.get(10)),
                OpenFlightsValueParser.nullableText(columns.get(11)),
                OpenFlightsValueParser.nullableText(columns.get(12)),
                OpenFlightsValueParser.nullableText(columns.get(13))
        );
    }
}
