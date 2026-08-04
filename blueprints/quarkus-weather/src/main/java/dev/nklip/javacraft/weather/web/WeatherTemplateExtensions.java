package dev.nklip.javacraft.weather.web;

import io.quarkus.qute.TemplateExtension;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Presentation-only formatting for the dashboard.
 *
 * <p>Lives in the web layer rather than on the domain records so {@code WeatherSnapshot} stays
 * free of display concerns. Every formatter pins {@link Locale#ROOT} so the rendered page — and
 * the tests asserting on it — do not change with the host's locale.
 */
@TemplateExtension
public class WeatherTemplateExtensions {

    private static final DateTimeFormatter OBSERVED_AT =
            DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale.ROOT);

    private WeatherTemplateExtensions() {
    }

    /** {@code {someDouble.oneDecimal}} — temperatures and wind speeds. */
    public static String oneDecimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    /** {@code {someLocalDateTime.display}} — the observation timestamp. */
    public static String display(LocalDateTime value) {
        return OBSERVED_AT.format(value);
    }
}
