package dev.nklip.javacraft.weather.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class CityWeatherTest {

    private static final WeatherSnapshot SNAPSHOT = new WeatherSnapshot(
            15.3, 72, 12.5, WeatherCondition.OVERCAST, LocalDateTime.of(2026, 8, 4, 12, 30));

    @Test
    void availableResultCarriesTheSnapshot() {
        CityWeather weather = CityWeather.of(Cities.GLASGOW, SNAPSHOT);

        assertTrue(weather.available());
        assertEquals(SNAPSHOT, weather.snapshot());
        assertNull(weather.failureReason());
    }

    @Test
    void unavailableResultCarriesTheReason() {
        CityWeather weather = CityWeather.unavailable(Cities.SAMARA, "boom");

        assertFalse(weather.available());
        assertNull(weather.snapshot());
        assertEquals("boom", weather.failureReason());
    }

    @Test
    void rejectsMissingCity() {
        assertThrows(NullPointerException.class, () -> CityWeather.of(null, SNAPSHOT));
    }

    @Test
    void rejectsMissingSnapshot() {
        assertThrows(NullPointerException.class, () -> CityWeather.of(Cities.GLASGOW, null));
    }

    @Test
    void rejectsMissingFailureReason() {
        assertThrows(NullPointerException.class,
                () -> CityWeather.unavailable(Cities.GLASGOW, null));
    }

    @Test
    void rejectsResultThatIsNeitherAvailableNorFailed() {
        assertThrows(IllegalArgumentException.class,
                () -> new CityWeather(Cities.GLASGOW, null, null));
    }

    @Test
    void rejectsResultThatIsBothAvailableAndFailed() {
        assertThrows(IllegalArgumentException.class,
                () -> new CityWeather(Cities.GLASGOW, SNAPSHOT, "boom"));
    }
}
