package dev.nklip.javacraft.weather.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

/**
 * The shared test resource overrides any inherited {@code ANTHROPIC_API_KEY}, so this covers the
 * unconfigured path end to end — the one a contributor without a key actually hits.
 */
@QuarkusTest
@WithTestResource(OpenMeteoWireMockResource.class)
class AssistantResourceTest {

    @Test
    void reportsUnavailableWhenNoApiKeyIsConfigured() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"question\":\"Where is it raining?\"}")
                .when().post("/api/ask")
                .then()
                .statusCode(503)
                .body("status", is("disabled"))
                .body("text", containsString("not configured"));
    }

    @Test
    void rejectsABlankQuestionBeforeReachingTheAssistant() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"question\":\"   \"}")
                .when().post("/api/ask")
                .then()
                .statusCode(400)
                .body("status", is("invalid"));
    }

    @Test
    void rejectsAnOverlongQuestion() {
        String tooLong = "a".repeat(AskRequest.MAX_LENGTH + 1);

        given()
                .contentType(ContentType.JSON)
                .body("{\"question\":\"" + tooLong + "\"}")
                .when().post("/api/ask")
                .then()
                .statusCode(400)
                .body("status", is("invalid"));
    }

    @Test
    void rejectsAMissingQuestionField() {
        given()
                .contentType(ContentType.JSON)
                .body("{}")
                .when().post("/api/ask")
                .then()
                .statusCode(400)
                .body("status", is("invalid"));
    }
}
