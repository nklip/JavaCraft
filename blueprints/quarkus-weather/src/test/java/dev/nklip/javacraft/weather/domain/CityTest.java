package dev.nklip.javacraft.weather.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CityTest {

    @Test
    void buildsLabelFromNameAndCountry() {
        assertEquals("Glasgow, United Kingdom", Cities.GLASGOW.label());
    }

    @Test
    void rejectsMissingName() {
        assertThrows(NullPointerException.class, () -> new City(null, "UK", 0, 0));
    }

    @Test
    void rejectsMissingCountry() {
        assertThrows(NullPointerException.class, () -> new City("Glasgow", null, 0, 0));
    }

    @Test
    void rejectsBlankName() {
        assertThrows(IllegalArgumentException.class, () -> new City("  ", "UK", 0, 0));
    }

    @Test
    void rejectsBlankCountry() {
        assertThrows(IllegalArgumentException.class, () -> new City("Glasgow", " ", 0, 0));
    }

    @ParameterizedTest
    @ValueSource(doubles = {-90.001, 90.001})
    void rejectsLatitudeOutOfRange(double latitude) {
        assertThrows(IllegalArgumentException.class, () -> new City("X", "Y", latitude, 0));
    }

    @ParameterizedTest
    @ValueSource(doubles = {-180.001, 180.001})
    void rejectsLongitudeOutOfRange(double longitude) {
        assertThrows(IllegalArgumentException.class, () -> new City("X", "Y", 0, longitude));
    }

    @ParameterizedTest
    @ValueSource(doubles = {-90, 90})
    void acceptsLatitudeBounds(double latitude) {
        assertEquals(latitude, new City("X", "Y", latitude, 0).latitude());
    }

    @ParameterizedTest
    @ValueSource(doubles = {-180, 180})
    void acceptsLongitudeBounds(double longitude) {
        assertEquals(longitude, new City("X", "Y", 0, longitude).longitude());
    }
}
