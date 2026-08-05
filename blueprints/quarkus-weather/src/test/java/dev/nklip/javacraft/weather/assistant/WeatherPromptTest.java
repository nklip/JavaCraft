package dev.nklip.javacraft.weather.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nklip.javacraft.weather.domain.Cities;
import dev.nklip.javacraft.weather.domain.CityWeather;
import dev.nklip.javacraft.weather.domain.WeatherCondition;
import dev.nklip.javacraft.weather.domain.WeatherSnapshot;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class WeatherPromptTest {

    private static final CityWeather GLASGOW = CityWeather.of(Cities.GLASGOW,
            new WeatherSnapshot(15.3, 91, 13.7, WeatherCondition.DRIZZLE,
                    LocalDateTime.of(2026, 8, 4, 7, 0)));
    private static final CityWeather SAMARA = CityWeather.of(Cities.SAMARA,
            new WeatherSnapshot(24.1, 40, 8.0, WeatherCondition.CLEAR_SKY,
                    LocalDateTime.of(2026, 8, 4, 10, 0)));
    private static final CityWeather NHA_TRANG =
            CityWeather.unavailable(Cities.NHA_TRANG, "Weather data is currently unavailable");

    @Test
    void groundsTheQuestionInEveryObservation() {
        String prompt = WeatherPrompt.userMessage("Where is it raining?",
                List.of(GLASGOW, SAMARA, NHA_TRANG));

        assertTrue(prompt.contains("15.3 °C"), prompt);
        assertTrue(prompt.contains("Drizzle"), prompt);
        assertTrue(prompt.contains("humidity 91%"), prompt);
        assertTrue(prompt.contains("wind 13.7 km/h"), prompt);
        assertTrue(prompt.contains("Glasgow, United Kingdom"), prompt);
        assertTrue(prompt.contains("Samara, Russia"), prompt);
        assertTrue(prompt.contains("24.1 °C"), prompt);
    }

    @Test
    void marksUnavailableCitiesRatherThanOmittingThem() {
        String prompt = WeatherPrompt.userMessage("How is Nha Trang?", List.of(NHA_TRANG));

        assertTrue(prompt.contains("Nha Trang, Vietnam"), prompt);
        assertTrue(prompt.contains("no reading available"), prompt);
        assertTrue(prompt.contains("Weather data is currently unavailable"), prompt);
    }

    @Test
    void includesTheQuestion() {
        assertTrue(WeatherPrompt.userMessage("Which city is warmest?", List.of(GLASGOW))
                .contains("Question: Which city is warmest?"));
    }

    @Test
    void trimsTheQuestion() {
        assertTrue(WeatherPrompt.userMessage("   Is it cold?  ", List.of(GLASGOW))
                .endsWith("Question: Is it cold?"));
    }

    @Test
    void handlesAnEmptyForecastList() {
        String prompt = WeatherPrompt.userMessage("Anything?", List.of());

        assertTrue(prompt.contains("(no observations available)"), prompt);
    }

    @Test
    void rejectsMissingQuestion() {
        assertThrows(NullPointerException.class,
                () -> WeatherPrompt.userMessage(null, List.of(GLASGOW)));
    }

    @Test
    void rejectsMissingForecasts() {
        assertThrows(NullPointerException.class, () -> WeatherPrompt.userMessage("Hi", null));
    }

    @Test
    void systemPromptConfinesTheModelToTheSuppliedObservations() {
        assertTrue(WeatherPrompt.SYSTEM.contains("only the observations"), WeatherPrompt.SYSTEM);
        assertTrue(WeatherPrompt.SYSTEM.contains("no forecast data"), WeatherPrompt.SYSTEM);
        assertEquals(WeatherPrompt.SYSTEM.strip(), WeatherPrompt.SYSTEM);
    }
}
