package dev.nklip.javacraft.weather.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class CitiesTest {

    @Test
    void reportsTheThreeShowcaseCitiesInOrder() {
        assertEquals(
                List.of("Glasgow", "Samara", "Nha Trang"),
                Cities.all().stream().map(City::name).toList());
    }

    @Test
    void pairsEachCityWithItsCountry() {
        assertEquals(
                List.of("United Kingdom", "Russia", "Vietnam"),
                Cities.all().stream().map(City::country).toList());
    }

    @Test
    void exposesAnImmutableList() {
        assertUnmodifiable(Cities.all(), Cities.GLASGOW);
        assertUnmodifiable(Cities.all(), Cities.SAMARA);
        assertUnmodifiable(Cities.all(), Cities.NHA_TRANG);
    }

    /**
     * Checks unmodifiability behind a generic boundary.
     *
     * <p>Asserting {@code Cities.all().add(...)} directly reads as a bug to static analysis —
     * IDEs constant-fold the {@code List.of(...)} origin and flag "immutable object is modified",
     * which is the very thing being asserted. Passing the list as a plain {@code List<T>} moves
     * the check to runtime, where it belongs.
     */
    private static <T> void assertUnmodifiable(List<T> list, T element) {
        assertThrows(UnsupportedOperationException.class, () -> list.add(element));
    }
}
