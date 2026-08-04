package dev.nklip.javacraft.weather.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.nklip.javacraft.weather.client.OpenMeteoClient;
import dev.nklip.javacraft.weather.client.OpenMeteoForecast;
import dev.nklip.javacraft.weather.domain.Cities;
import dev.nklip.javacraft.weather.domain.CityWeather;
import dev.nklip.javacraft.weather.domain.WeatherCondition;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Plain unit tests: constructor injection means the service needs no CDI container to exercise.
 */
class WeatherServiceTest {

    private final OpenMeteoClient client = mock(OpenMeteoClient.class);
    private final WeatherService service = new WeatherService(client);

    private static OpenMeteoForecast forecast(
            String time, Double temp, Integer humidity, Double wind, Integer code) {
        return new OpenMeteoForecast(
                new OpenMeteoForecast.Current(time, temp, humidity, wind, code));
    }

    private void stubAll(OpenMeteoForecast response) {
        when(client.currentWeather(anyDouble(), anyDouble(), eq(WeatherService.CURRENT_FIELDS),
                eq(WeatherService.TIMEZONE))).thenReturn(response);
    }

    @Test
    void returnsOneResultPerCityInOrder() {
        stubAll(forecast("2026-08-04T12:00", 15.3, 72, 12.5, 3));

        List<CityWeather> results = service.currentWeather();

        assertEquals(Cities.all(), results.stream().map(CityWeather::city).toList());
    }

    @Test
    void mapsPayloadIntoSnapshot() {
        stubAll(forecast("2026-08-04T12:00", 15.3, 72, 12.5, 3));

        CityWeather glasgow = service.currentWeather().getFirst();

        assertTrue(glasgow.available());
        assertEquals(15.3, glasgow.snapshot().temperatureCelsius());
        assertEquals(72, glasgow.snapshot().relativeHumidityPercent());
        assertEquals(12.5, glasgow.snapshot().windSpeedKmh());
        assertEquals(WeatherCondition.OVERCAST, glasgow.snapshot().condition());
        assertEquals(LocalDateTime.of(2026, 8, 4, 12, 0), glasgow.snapshot().observedAt());
    }

    @Test
    void queriesEachCityWithItsOwnCoordinates() {
        stubAll(forecast("2026-08-04T12:00", 15.3, 72, 12.5, 0));

        service.currentWeather();

        Cities.all().forEach(city -> verify(client).currentWeather(
                city.latitude(), city.longitude(),
                WeatherService.CURRENT_FIELDS, WeatherService.TIMEZONE));
        verify(client, times(3)).currentWeather(
                anyDouble(), anyDouble(), eq(WeatherService.CURRENT_FIELDS),
                eq(WeatherService.TIMEZONE));
    }

    @Test
    void treatsMissingWeatherCodeAsUnknownRatherThanFailure() {
        stubAll(forecast("2026-08-04T12:00", 15.3, 72, 12.5, null));

        CityWeather glasgow = service.currentWeather().getFirst();

        assertTrue(glasgow.available());
        assertEquals(WeatherCondition.UNKNOWN, glasgow.snapshot().condition());
    }

    @Test
    void degradesCityWhenClientThrows() {
        when(client.currentWeather(anyDouble(), anyDouble(), eq(WeatherService.CURRENT_FIELDS),
                eq(WeatherService.TIMEZONE))).thenThrow(new IllegalStateException("timeout"));

        List<CityWeather> results = service.currentWeather();

        assertEquals(3, results.size());
        results.forEach(result -> {
            assertFalse(result.available());
            assertEquals(WeatherService.UNAVAILABLE_MESSAGE, result.failureReason());
        });
    }

    @Test
    void keepsHealthyCitiesWhenOneFails() {
        when(client.currentWeather(eq(Cities.GLASGOW.latitude()), anyDouble(),
                eq(WeatherService.CURRENT_FIELDS), eq(WeatherService.TIMEZONE)))
                .thenThrow(new IllegalStateException("timeout"));
        when(client.currentWeather(eq(Cities.SAMARA.latitude()), anyDouble(),
                eq(WeatherService.CURRENT_FIELDS), eq(WeatherService.TIMEZONE)))
                .thenReturn(forecast("2026-08-04T17:00", 24.1, 40, 8.0, 0));
        when(client.currentWeather(eq(Cities.NHA_TRANG.latitude()), anyDouble(),
                eq(WeatherService.CURRENT_FIELDS), eq(WeatherService.TIMEZONE)))
                .thenReturn(forecast("2026-08-04T19:00", 29.7, 78, 15.2, 95));

        List<CityWeather> results = service.currentWeather();

        assertFalse(results.get(0).available());
        assertTrue(results.get(1).available());
        assertTrue(results.get(2).available());
        assertEquals(WeatherCondition.THUNDERSTORM, results.get(2).snapshot().condition());
    }

    static List<OpenMeteoForecast> incompletePayloads() {
        return List.of(
                new OpenMeteoForecast(null),
                forecast("2026-08-04T12:00", null, 72, 12.5, 3),
                forecast("2026-08-04T12:00", 15.3, null, 12.5, 3),
                forecast("2026-08-04T12:00", 15.3, 72, null, 3),
                forecast(null, 15.3, 72, 12.5, 3),
                forecast("not-a-timestamp", 15.3, 72, 12.5, 3));
    }

    @ParameterizedTest
    @MethodSource("incompletePayloads")
    void degradesCityWhenPayloadIsUnusable(OpenMeteoForecast payload) {
        stubAll(payload);

        CityWeather glasgow = service.currentWeather().getFirst();

        assertFalse(glasgow.available());
        assertEquals(WeatherService.UNAVAILABLE_MESSAGE, glasgow.failureReason());
    }

    @Test
    void degradesCityWhenResponseBodyIsMissingEntirely() {
        stubAll(null);

        CityWeather glasgow = service.currentWeather().getFirst();

        assertFalse(glasgow.available());
        assertEquals(WeatherService.UNAVAILABLE_MESSAGE, glasgow.failureReason());
    }
}
