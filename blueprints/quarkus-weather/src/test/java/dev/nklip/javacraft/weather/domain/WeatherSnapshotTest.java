package dev.nklip.javacraft.weather.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WeatherSnapshotTest {

    private static final LocalDateTime OBSERVED_AT = LocalDateTime.of(2026, 8, 4, 12, 30);

    @Test
    void keepsSuppliedMeasurements() {
        WeatherSnapshot snapshot =
                new WeatherSnapshot(15.3, 72, 12.5, WeatherCondition.OVERCAST, OBSERVED_AT);

        assertEquals(15.3, snapshot.temperatureCelsius());
        assertEquals(72, snapshot.relativeHumidityPercent());
        assertEquals(12.5, snapshot.windSpeedKmh());
        assertEquals(WeatherCondition.OVERCAST, snapshot.condition());
        assertEquals(OBSERVED_AT, snapshot.observedAt());
    }

    @Test
    void allowsSubZeroTemperature() {
        assertEquals(-18.4,
                new WeatherSnapshot(-18.4, 80, 3, WeatherCondition.SNOW, OBSERVED_AT)
                        .temperatureCelsius());
    }

    @Test
    void rejectsMissingCondition() {
        assertThrows(NullPointerException.class,
                () -> new WeatherSnapshot(10, 50, 5, null, OBSERVED_AT));
    }

    @Test
    void rejectsMissingObservationTime() {
        assertThrows(NullPointerException.class,
                () -> new WeatherSnapshot(10, 50, 5, WeatherCondition.RAIN, null));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 101})
    void rejectsHumidityOutOfRange(int humidity) {
        assertThrows(IllegalArgumentException.class,
                () -> new WeatherSnapshot(10, humidity, 5, WeatherCondition.RAIN, OBSERVED_AT));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 100})
    void acceptsHumidityBounds(int humidity) {
        assertEquals(humidity,
                new WeatherSnapshot(10, humidity, 5, WeatherCondition.RAIN, OBSERVED_AT)
                        .relativeHumidityPercent());
    }

    @Test
    void rejectsNegativeWindSpeed() {
        assertThrows(IllegalArgumentException.class,
                () -> new WeatherSnapshot(10, 50, -0.1, WeatherCondition.RAIN, OBSERVED_AT));
    }
}
