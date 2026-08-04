package dev.nklip.javacraft.weather.domain;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * WMO 4677 weather interpretation codes, collapsed into the coarse buckets the dashboard shows.
 *
 * <p>Open-Meteo reports a numeric {@code weather_code}; the full WMO table distinguishes far more
 * cases than a three-card page can usefully display, so neighbouring codes share a bucket.
 */
public enum WeatherCondition {

    CLEAR_SKY("Clear sky", "☀️", 0),
    MAINLY_CLEAR("Mainly clear", "🌤️", 1),
    PARTLY_CLOUDY("Partly cloudy", "⛅", 2),
    OVERCAST("Overcast", "☁️", 3),
    FOG("Fog", "🌫️", 45, 48),
    DRIZZLE("Drizzle", "🌦️", 51, 53, 55),
    FREEZING_DRIZZLE("Freezing drizzle", "🌨️", 56, 57),
    RAIN("Rain", "🌧️", 61, 63, 65),
    FREEZING_RAIN("Freezing rain", "🌨️", 66, 67),
    SNOW("Snow", "❄️", 71, 73, 75, 77),
    RAIN_SHOWERS("Rain showers", "🌦️", 80, 81, 82),
    SNOW_SHOWERS("Snow showers", "🌨️", 85, 86),
    THUNDERSTORM("Thunderstorm", "⛈️", 95, 96, 99),
    /** Reported code outside the table above, or no code at all. */
    UNKNOWN("Unknown", "❓");

    private static final Map<Integer, WeatherCondition> BY_CODE = Stream.of(values())
            .flatMap(condition -> Stream.of(condition.codes)
                    .map(code -> Map.entry(code, condition)))
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));

    private final String description;
    private final String icon;
    private final Integer[] codes;

    WeatherCondition(String description, String icon, Integer... codes) {
        this.description = description;
        this.icon = icon;
        this.codes = codes;
    }

    /** Maps a WMO code to a bucket, falling back to {@link #UNKNOWN} for unmapped or null input. */
    public static WeatherCondition fromCode(Integer code) {
        if (code == null) {
            return UNKNOWN;
        }
        return BY_CODE.getOrDefault(code, UNKNOWN);
    }

    public String description() {
        return description;
    }

    public String icon() {
        return icon;
    }
}
