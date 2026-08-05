package dev.nklip.javacraft.weather.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nklip.javacraft.weather.assistant.WeatherAnswer;
import org.junit.jupiter.api.Test;

class AskResponseTest {

    @Test
    void lowercasesTheStatusForTheBrowser() {
        // The page builds a CSS class from this value, so the casing is load-bearing.
        assertEquals("answered", AskResponse.of(WeatherAnswer.answered("Sunny.")).status());
        assertEquals("refused", AskResponse.of(WeatherAnswer.refused()).status());
        assertEquals("failed", AskResponse.of(WeatherAnswer.failed()).status());
        assertEquals("disabled", AskResponse.of(WeatherAnswer.disabled()).status());
    }

    @Test
    void carriesTheAnswerText() {
        assertEquals("Sunny.", AskResponse.of(WeatherAnswer.answered("Sunny.")).text());
    }

    @Test
    void reportsTheLengthLimitOnAnInvalidQuestion() {
        AskResponse response = AskResponse.invalidQuestion();

        assertEquals("invalid", response.status());
        assertTrue(response.text().contains(String.valueOf(AskRequest.MAX_LENGTH)));
    }
}
