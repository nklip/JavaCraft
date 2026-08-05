package dev.nklip.javacraft.weather.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class WeatherAnswerTest {

    @Test
    void answeredCarriesTheText() {
        WeatherAnswer answer = WeatherAnswer.answered("Glasgow is drizzly.");

        assertEquals(WeatherAnswer.Status.ANSWERED, answer.status());
        assertEquals("Glasgow is drizzly.", answer.text());
        assertTrue(answer.answeredSuccessfully());
    }

    static java.util.List<WeatherAnswer> failureStates() {
        return java.util.List.of(
                WeatherAnswer.disabled(), WeatherAnswer.refused(), WeatherAnswer.failed());
    }

    @ParameterizedTest
    @MethodSource("failureStates")
    void failureStatesAreNotSuccessesButStillCarryUserFacingText(WeatherAnswer answer) {
        assertFalse(answer.answeredSuccessfully());
        assertFalse(answer.text().isBlank());
    }

    @Test
    void distinguishesTheThreeFailureStates() {
        assertEquals(WeatherAnswer.Status.DISABLED, WeatherAnswer.disabled().status());
        assertEquals(WeatherAnswer.Status.REFUSED, WeatherAnswer.refused().status());
        assertEquals(WeatherAnswer.Status.FAILED, WeatherAnswer.failed().status());
    }

    @Test
    void rejectsMissingStatus() {
        assertThrows(NullPointerException.class, () -> new WeatherAnswer(null, "text"));
    }

    @Test
    void rejectsMissingText() {
        assertThrows(NullPointerException.class,
                () -> new WeatherAnswer(WeatherAnswer.Status.ANSWERED, null));
    }

    @Test
    void rejectsBlankText() {
        assertThrows(IllegalArgumentException.class,
                () -> new WeatherAnswer(WeatherAnswer.Status.ANSWERED, "  "));
    }
}
