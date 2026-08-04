package dev.nklip.javacraft.weather.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The slice of the Open-Meteo forecast response this showcase reads.
 *
 * <p>Fields are boxed because Open-Meteo omits them when a measurement is unavailable; the
 * service layer is responsible for turning a partial payload into a failed {@code CityWeather}
 * rather than a snapshot full of zeroes.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenMeteoForecast(@JsonProperty("current") Current current) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Current(
            @JsonProperty("time") String time,
            @JsonProperty("temperature_2m") Double temperatureCelsius,
            @JsonProperty("relative_humidity_2m") Integer relativeHumidityPercent,
            @JsonProperty("wind_speed_10m") Double windSpeedKmh,
            @JsonProperty("weather_code") Integer weatherCode) {
    }
}
