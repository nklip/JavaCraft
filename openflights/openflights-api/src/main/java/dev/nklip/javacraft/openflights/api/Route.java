package dev.nklip.javacraft.openflights.api;

import dev.nklip.javacraft.openflights.api.parser.OpenFlightsValueParser;
import java.util.List;

/**
 * Shared route payload extracted from {@code routes.dat}.
 */
public record Route(
        String airlineCode,
        Integer airlineId,
        String sourceAirportCode,
        Integer sourceAirportId,
        String destinationAirportCode,
        Integer destinationAirportId,
        boolean codeshare,
        Integer stops,
        List<String> equipmentCodes
) {

    public Route {
        equipmentCodes = List.copyOf(equipmentCodes);
    }

    public static Route fromColumns(List<String> columns) {
        OpenFlightsValueParser.requireColumnCount(columns, 9, "route");
        return new Route(
                OpenFlightsValueParser.nullableText(columns.get(0)),
                OpenFlightsValueParser.nullableInteger(columns.get(1)),
                OpenFlightsValueParser.nullableText(columns.get(2)),
                OpenFlightsValueParser.nullableInteger(columns.get(3)),
                OpenFlightsValueParser.nullableText(columns.get(4)),
                OpenFlightsValueParser.nullableInteger(columns.get(5)),
                OpenFlightsValueParser.yesNoFlag(columns.get(6)),
                OpenFlightsValueParser.nullableInteger(columns.get(7)),
                OpenFlightsValueParser.spaceSeparatedValues(columns.get(8))
        );
    }
}
