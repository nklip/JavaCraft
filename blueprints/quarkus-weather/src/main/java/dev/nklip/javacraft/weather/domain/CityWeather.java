package dev.nklip.javacraft.weather.domain;

import java.util.Objects;

/**
 * A city paired with either its observation or the reason one is missing.
 *
 * <p>Modelled as a result rather than a bare snapshot so one unreachable city degrades its own
 * card instead of failing the whole page. Exactly one of {@code snapshot} / {@code failureReason}
 * is non-null.
 */
public record CityWeather(City city, WeatherSnapshot snapshot, String failureReason) {

    public CityWeather {
        Objects.requireNonNull(city, "city");
        if ((snapshot == null) == (failureReason == null)) {
            throw new IllegalArgumentException(
                    "exactly one of snapshot or failureReason must be set");
        }
    }

    public static CityWeather of(City city, WeatherSnapshot snapshot) {
        return new CityWeather(city, Objects.requireNonNull(snapshot, "snapshot"), null);
    }

    public static CityWeather unavailable(City city, String failureReason) {
        return new CityWeather(city, null, Objects.requireNonNull(failureReason, "failureReason"));
    }

    /** Drives which branch the Qute template renders. */
    public boolean available() {
        return snapshot != null;
    }
}
