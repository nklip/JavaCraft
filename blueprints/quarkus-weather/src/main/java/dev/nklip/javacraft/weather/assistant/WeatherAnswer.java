package dev.nklip.javacraft.weather.assistant;

import java.util.Objects;

/**
 * What the dashboard renders under the question box.
 *
 * <p>{@code status} rather than a bare string so the page can style a refusal or an outage
 * differently from an answer, and so a failure never reaches the user as prose that reads like
 * one.
 */
public record WeatherAnswer(Status status, String text) {

    public enum Status {
        /** Claude answered. */
        ANSWERED,
        /** No API key configured — the panel should not have been reachable. */
        DISABLED,
        /** Claude's safety classifiers declined the request. */
        REFUSED,
        /** Network failure, rate limit, or any other upstream problem. */
        FAILED
    }

    public WeatherAnswer {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(text, "text");
        if (text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
    }

    public static WeatherAnswer answered(String text) {
        return new WeatherAnswer(Status.ANSWERED, text);
    }

    public static WeatherAnswer disabled() {
        return new WeatherAnswer(Status.DISABLED, "The Claude assistant is not configured.");
    }

    public static WeatherAnswer refused() {
        return new WeatherAnswer(Status.REFUSED,
                "Claude declined to answer that question. Try rephrasing it.");
    }

    public static WeatherAnswer failed() {
        return new WeatherAnswer(Status.FAILED,
                "The assistant is temporarily unavailable. Please try again.");
    }

    public boolean answeredSuccessfully() {
        return status == Status.ANSWERED;
    }
}
