package dev.nklip.javacraft.weather.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class WeatherConditionTest {

    @ParameterizedTest
    @CsvSource({
            "0, CLEAR_SKY",
            "1, MAINLY_CLEAR",
            "2, PARTLY_CLOUDY",
            "3, OVERCAST",
            "45, FOG",
            "48, FOG",
            "51, DRIZZLE",
            "55, DRIZZLE",
            "56, FREEZING_DRIZZLE",
            "57, FREEZING_DRIZZLE",
            "61, RAIN",
            "65, RAIN",
            "66, FREEZING_RAIN",
            "67, FREEZING_RAIN",
            "71, SNOW",
            "77, SNOW",
            "80, RAIN_SHOWERS",
            "82, RAIN_SHOWERS",
            "85, SNOW_SHOWERS",
            "86, SNOW_SHOWERS",
            "95, THUNDERSTORM",
            "99, THUNDERSTORM"
    })
    void mapsWmoCodeToCondition(int code, WeatherCondition expected) {
        assertEquals(expected, WeatherCondition.fromCode(code));
    }

    @Test
    void fallsBackToUnknownForUnmappedCode() {
        assertEquals(WeatherCondition.UNKNOWN, WeatherCondition.fromCode(4));
        assertEquals(WeatherCondition.UNKNOWN, WeatherCondition.fromCode(-1));
        assertEquals(WeatherCondition.UNKNOWN, WeatherCondition.fromCode(1000));
    }

    @Test
    void fallsBackToUnknownForMissingCode() {
        assertEquals(WeatherCondition.UNKNOWN, WeatherCondition.fromCode(null));
    }

    @ParameterizedTest
    @EnumSource(WeatherCondition.class)
    void everyConditionIsRenderable(WeatherCondition condition) {
        assertNotNull(condition.description());
        assertFalse(condition.description().isBlank());
        assertNotNull(condition.icon());
        assertFalse(condition.icon().isBlank());
    }
}
