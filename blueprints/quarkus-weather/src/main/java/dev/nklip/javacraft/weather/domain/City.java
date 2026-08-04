package dev.nklip.javacraft.weather.domain;

import java.util.Objects;

/**
 * A location the dashboard reports on, carried as WGS84 coordinates because Open-Meteo's
 * forecast endpoint is coordinate-based and needs no geocoding round trip.
 */
public record City(String name, String country, double latitude, double longitude) {

    public City {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(country, "country");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (country.isBlank()) {
            throw new IllegalArgumentException("country must not be blank");
        }
        if (latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException("latitude out of range: " + latitude);
        }
        if (longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("longitude out of range: " + longitude);
        }
    }

    /** Display label, e.g. {@code "Glasgow, United Kingdom"}. */
    public String label() {
        return name + ", " + country;
    }
}
