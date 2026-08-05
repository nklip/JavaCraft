package dev.nklip.javacraft.weather.assistant;

import dev.nklip.javacraft.weather.domain.CityWeather;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Builds the prompt sent to Claude.
 *
 * <p>The model is <b>not</b> asked what the weather is — it has no live data and would answer
 * fluently and wrongly. Instead every request carries the observations this dashboard just
 * fetched from Open-Meteo, and the system prompt confines the answer to them. Claude's job is
 * interpretation and phrasing; Open-Meteo supplies the facts.
 */
public final class WeatherPrompt {

    static final String SYSTEM = """
            You are a weather assistant embedded in a dashboard that tracks exactly three cities: \
            Glasgow (United Kingdom), Samara (Russia) and Nha Trang (Vietnam).

            Answer using only the observations supplied in the user message. They were fetched \
            moments ago and are the only weather data you have. You have no forecast data and no \
            information about any other city or any other time.

            If a question cannot be answered from those observations — a different city, a future \
            forecast, a past reading — say so plainly in one sentence instead of guessing.

            Keep answers to a few sentences of plain prose. Quote temperatures in °C and wind in \
            km/h exactly as given. Do not use markdown, headings or bullet lists.""";

    private WeatherPrompt() {
    }

    /** The user turn: current observations as grounding, then the question. */
    public static String userMessage(String question, List<CityWeather> forecasts) {
        Objects.requireNonNull(question, "question");
        Objects.requireNonNull(forecasts, "forecasts");

        return """
                Current observations:
                %s

                Question: %s""".formatted(observations(forecasts), question.strip());
    }

    private static String observations(List<CityWeather> forecasts) {
        if (forecasts.isEmpty()) {
            return "- (no observations available)";
        }
        return forecasts.stream().map(WeatherPrompt::describe).collect(Collectors.joining("\n"));
    }

    private static String describe(CityWeather forecast) {
        if (!forecast.available()) {
            return "- %s: no reading available (%s)"
                    .formatted(forecast.city().label(), forecast.failureReason());
        }
        var snapshot = forecast.snapshot();
        return "- %s: %.1f °C, %s, humidity %d%%, wind %.1f km/h, observed %s local time"
                .formatted(
                        forecast.city().label(),
                        snapshot.temperatureCelsius(),
                        snapshot.condition().description(),
                        snapshot.relativeHumidityPercent(),
                        snapshot.windSpeedKmh(),
                        snapshot.observedAt());
    }
}
