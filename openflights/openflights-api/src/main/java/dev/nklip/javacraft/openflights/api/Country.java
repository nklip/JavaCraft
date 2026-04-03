package dev.nklip.javacraft.openflights.api;

import dev.nklip.javacraft.openflights.api.parser.OpenFlightsValueParser;
import java.util.List;

/**
 * Shared country payload extracted from {@code countries.dat}.
 */
public record Country(
        String name,
        String isoCode,
        String dafifCode
) {

    public static Country fromColumns(List<String> columns) {
        OpenFlightsValueParser.requireColumnCount(columns, 3, "country");
        return new Country(
                OpenFlightsValueParser.nullableText(columns.get(0)),
                OpenFlightsValueParser.nullableText(columns.get(1)),
                OpenFlightsValueParser.nullableText(columns.get(2))
        );
    }
}
