package dev.nklip.javacraft.weather.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.nklip.javacraft.weather.assistant.WeatherAnswer;

/** What the page renders under the question box. */
public record AskResponse(
        @JsonProperty("status") String status,
        @JsonProperty("text") String text) {

    public static AskResponse of(WeatherAnswer answer) {
        return new AskResponse(answer.status().name().toLowerCase(java.util.Locale.ROOT),
                answer.text());
    }

    public static AskResponse invalidQuestion() {
        return new AskResponse("invalid",
                "Ask a weather question of at most " + AskRequest.MAX_LENGTH + " characters.");
    }
}
