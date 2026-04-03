package dev.nklip.javacraft.openflights.api.parser;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Shared low-level parser helpers for raw OpenFlights column values.
 *
 * <p>Why this class exists:
 *
 * <p>The OpenFlights record classes such as {@code Country}, {@code Airline}, {@code Airport}, {@code Plane}, and
 * {@code Route} are all built from raw string columns that come from the source {@code .dat} files. Those files use a
 * few conventions repeatedly:
 *
 * <p>- the special token {@code \N} to mean "no value"
 * <p>- blank strings that should also be treated as missing values
 * <p>- numeric columns that are optional
 * <p>- {@code Y}/{@code N} style flags
 * <p>- space-separated values stored inside a single column
 *
 * <p>If every shared API record parsed those conventions for itself, the parsing logic would be duplicated across
 * multiple classes and would be easy to make inconsistent over time. One record might treat {@code \N} as null while
 * another might forget, or one record might split space-separated values differently from another.
 *
 * <p>This helper centralizes those source-level parsing rules so all OpenFlights record factories interpret the raw
 * columns the same way. That consistency is the main reason the class exists.
 *
 * <p>What kind of logic belongs here:
 *
 * <p>- source-format parsing rules that are shared across many record types
 * <p>- conversion from raw text columns to primitive Java values
 * <p>- generic validation such as column-count checks
 *
 * <p>What does not belong here:
 *
 * <p>- business rules about how values should be persisted in SQL
 * <p>- normalization decisions such as country alias canonicalization
 * <p>- JPA/entity mapping concerns
 *
 * <p>That boundary is important:
 *
 * <p>This class answers the question "How do we interpret the raw OpenFlights file columns?".
 * Other classes, such as persistence normalizers and mappers, answer later questions like
 * "What canonical value should PostgreSQL store?".
 *
 * <p>So this parser is intentionally small and source-focused. It turns repeated OpenFlights file conventions into
 * predictable Java values before the rest of the application adds higher-level meaning.
 */
public final class OpenFlightsValueParser {

    private static final String NULL_TOKEN = "\\N";

    private OpenFlightsValueParser() {
    }

    public static void requireColumnCount(List<String> columns, int expectedColumnCount, String recordType) {
        Objects.requireNonNull(columns, "columns must not be null");
        if (columns.size() != expectedColumnCount) {
            throw new IllegalArgumentException(
                    recordType + " record must contain " + expectedColumnCount
                            + " columns but contains " + columns.size()
            );
        }
    }

    public static String nullableText(String value) {
        if (value == null || value.isBlank() || NULL_TOKEN.equals(value)) {
            return null;
        }
        return value;
    }

    public static Integer nullableInteger(String value) {
        String normalizedValue = nullableText(value);
        return normalizedValue == null ? null : Integer.valueOf(normalizedValue);
    }

    public static Double nullableDouble(String value) {
        String normalizedValue = nullableText(value);
        return normalizedValue == null ? null : Double.valueOf(normalizedValue);
    }

    public static boolean yesNoFlag(String value) {
        return "Y".equalsIgnoreCase(nullableText(value));
    }

    public static List<String> spaceSeparatedValues(String value) {
        String normalizedValue = nullableText(value);
        if (normalizedValue == null) {
            return List.of();
        }
        return Arrays.stream(normalizedValue.split("\\s+"))
                .filter(token -> !token.isBlank())
                .toList();
    }
}
