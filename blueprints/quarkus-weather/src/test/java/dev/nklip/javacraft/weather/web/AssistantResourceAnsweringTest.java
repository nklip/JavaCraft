package dev.nklip.javacraft.weather.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import dev.nklip.javacraft.weather.assistant.ClaudeWeatherAssistant;
import dev.nklip.javacraft.weather.assistant.WeatherAnswer;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

/**
 * The configured-assistant path, with Claude mocked out.
 *
 * <p>Separate from {@link AssistantResourceTest}, which covers the unconfigured path: the two
 * need opposite states of the same bean, and a mock is the only way to reach this one without a
 * live API key.
 */
@QuarkusTest
@WithTestResource(OpenMeteoWireMockResource.class)
class AssistantResourceAnsweringTest {

    @InjectMock
    ClaudeWeatherAssistant assistant;

    @Test
    void returnsClaudesAnswer() {
        when(assistant.configured()).thenReturn(true);
        when(assistant.answer(eq("Where is it raining?"), any()))
                .thenReturn(WeatherAnswer.answered("Glasgow — it is drizzling there."));

        given()
                .contentType(ContentType.JSON)
                .body("{\"question\":\"Where is it raining?\"}")
                .when().post("/api/ask")
                .then()
                .statusCode(200)
                .body("status", is("answered"))
                .body("text", is("Glasgow — it is drizzling there."));
    }

    @Test
    void surfacesARefusalAsItsOwnStatus() {
        when(assistant.configured()).thenReturn(true);
        when(assistant.answer(any(), any())).thenReturn(WeatherAnswer.refused());

        given()
                .contentType(ContentType.JSON)
                .body("{\"question\":\"Something disallowed\"}")
                .when().post("/api/ask")
                .then()
                .statusCode(200)
                .body("status", is("refused"));
    }

    @Test
    void surfacesAnUpstreamFailureWithoutA500() {
        when(assistant.configured()).thenReturn(true);
        when(assistant.answer(any(), any())).thenReturn(WeatherAnswer.failed());

        given()
                .contentType(ContentType.JSON)
                .body("{\"question\":\"Where is it raining?\"}")
                .when().post("/api/ask")
                .then()
                // The page renders this inline; a 500 would surface as a browser error instead.
                .statusCode(200)
                .body("status", is("failed"));
    }
}
