package dev.nklip.javacraft.weather.domain;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * One observation for one city.
 *
 * <p>{@code observedAt} is local to the city, not to the viewer: Open-Meteo is queried with
 * {@code timezone=auto}, so each city reports its own wall-clock time.
 */
public record WeatherSnapshot(
        double temperatureCelsius,
        int relativeHumidityPercent,
        double windSpeedKmh,
        WeatherCondition condition,
        LocalDateTime observedAt) {

    public WeatherSnapshot {
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(observedAt, "observedAt");
        if (relativeHumidityPercent < 0 || relativeHumidityPercent > 100) {
            throw new IllegalArgumentException(
                    "relativeHumidityPercent out of range: " + relativeHumidityPercent);
        }
        if (windSpeedKmh < 0) {
            throw new IllegalArgumentException("windSpeedKmh must not be negative: " + windSpeedKmh);
        }
    }
}
