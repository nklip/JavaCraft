package dev.nklip.javacraft.weather.web;

import com.fasterxml.jackson.annotation.JsonProperty;

/** A question typed into the dashboard's Claude panel. */
public record AskRequest(@JsonProperty("question") String question) {

    /** Long enough for a real question, short enough that the endpoint can't be used as a pipe. */
    public static final int MAX_LENGTH = 500;

    public boolean valid() {
        return question != null
                && !question.isBlank()
                && question.strip().length() <= MAX_LENGTH;
    }
}
