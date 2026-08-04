package dev.nklip.javacraft.weather.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class WeatherTemplateExtensionsTest {

    private final Locale originalDefault = Locale.getDefault();

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(originalDefault);
    }

    @ParameterizedTest
    @CsvSource({
            "15.3, 15.3",
            "15.25, 15.3",
            "0.0, 0.0",
            "-18.44, -18.4",
            "100, 100.0"
    })
    void formatsMeasurementsToOneDecimal(double value, String expected) {
        assertEquals(expected, WeatherTemplateExtensions.oneDecimal(value));
    }

    @Test
    void usesADecimalPointRegardlessOfHostLocale() {
        // A comma-decimal locale would otherwise render "15,3" and break the page's numbers.
        Locale.setDefault(Locale.GERMANY);

        assertEquals("15.3", WeatherTemplateExtensions.oneDecimal(15.3));
    }

    @Test
    void formatsObservationTimestamp() {
        assertEquals("04 Aug 2026, 12:30",
                WeatherTemplateExtensions.display(LocalDateTime.of(2026, 8, 4, 12, 30)));
    }

    @Test
    void usesEnglishMonthNamesRegardlessOfHostLocale() {
        Locale.setDefault(Locale.GERMANY);

        assertEquals("04 Mar 2026, 09:05",
                WeatherTemplateExtensions.display(LocalDateTime.of(2026, 3, 4, 9, 5)));
    }
}
