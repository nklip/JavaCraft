package dev.nklip.javacraft.weather.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class AskRequestTest {

    @Test
    void acceptsAnOrdinaryQuestion() {
        assertTrue(new AskRequest("Which city is warmest?").valid());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "\n\t "})
    void rejectsMissingOrBlankQuestions(String question) {
        assertFalse(new AskRequest(question).valid());
    }

    @Test
    void acceptsAQuestionAtTheLengthLimit() {
        assertTrue(new AskRequest("a".repeat(AskRequest.MAX_LENGTH)).valid());
    }

    @Test
    void rejectsAQuestionOverTheLengthLimit() {
        assertFalse(new AskRequest("a".repeat(AskRequest.MAX_LENGTH + 1)).valid());
    }

    @Test
    void measuresLengthAfterTrimming() {
        String padded = "  " + "a".repeat(AskRequest.MAX_LENGTH) + "  ";

        assertTrue(new AskRequest(padded).valid());
    }
}
