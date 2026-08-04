package dev.nklip.javacraft.weather.service;

import dev.nklip.javacraft.weather.client.OpenMeteoClient;
import dev.nklip.javacraft.weather.client.OpenMeteoForecast;
import dev.nklip.javacraft.weather.domain.Cities;
import dev.nklip.javacraft.weather.domain.City;
import dev.nklip.javacraft.weather.domain.CityWeather;
import dev.nklip.javacraft.weather.domain.WeatherCondition;
import dev.nklip.javacraft.weather.domain.WeatherSnapshot;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

/** Fetches the current observation for each configured city and maps it into the domain. */
@ApplicationScoped
public class WeatherService {

    /** Open-Meteo returns only the measurements named here. */
    static final String CURRENT_FIELDS =
            "temperature_2m,relative_humidity_2m,wind_speed_10m,weather_code";

    /** Each city reports in its own timezone rather than the server's. */
    static final String TIMEZONE = "auto";

    /**
     * Shown on the card when a city cannot be read; deliberately free of internal detail.
     * Public because it is user-facing copy that the page test asserts on.
     */
    public static final String UNAVAILABLE_MESSAGE = "Weather data is currently unavailable";

    private static final Logger LOG = Logger.getLogger(WeatherService.class);

    private final OpenMeteoClient client;

    @Inject
    public WeatherService(@RestClient OpenMeteoClient client) {
        this.client = client;
    }

    /**
     * One result per city, in {@link Cities#all()} order. Never throws: a city that cannot be
     * read becomes an unavailable result so the remaining cards still render.
     */
    public List<CityWeather> currentWeather() {
        return Cities.all().stream().map(this::fetch).toList();
    }

    private CityWeather fetch(City city) {
        try {
            OpenMeteoForecast forecast = client.currentWeather(
                    city.latitude(), city.longitude(), CURRENT_FIELDS, TIMEZONE);
            return CityWeather.of(city, toSnapshot(forecast));
        } catch (RuntimeException e) {
            // Message only: the stack trace of a routine upstream timeout is noise, and the
            // request URL carries the city's coordinates.
            LOG.warnf("Could not read weather for %s: %s", city.label(), e.toString());
            return CityWeather.unavailable(city, UNAVAILABLE_MESSAGE);
        }
    }

    /**
     * @throws IllegalStateException when the payload is missing a measurement, which
     *         {@link #fetch} converts into an unavailable card
     */
    private static WeatherSnapshot toSnapshot(OpenMeteoForecast forecast) {
        OpenMeteoForecast.Current current = forecast == null ? null : forecast.current();
        if (current == null
                || current.temperatureCelsius() == null
                || current.relativeHumidityPercent() == null
                || current.windSpeedKmh() == null) {
            throw new IllegalStateException("incomplete current weather payload");
        }
        return new WeatherSnapshot(
                current.temperatureCelsius(),
                current.relativeHumidityPercent(),
                current.windSpeedKmh(),
                WeatherCondition.fromCode(current.weatherCode()),
                parseObservedAt(current.time()));
    }

    private static LocalDateTime parseObservedAt(String time) {
        if (time == null) {
            throw new IllegalStateException("missing observation time");
        }
        try {
            return LocalDateTime.parse(time);
        } catch (DateTimeParseException e) {
            throw new IllegalStateException("unparseable observation time: " + time, e);
        }
    }
}
