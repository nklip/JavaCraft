package dev.nklip.javacraft.weather.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import dev.nklip.javacraft.weather.service.WeatherService;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * Boots the application and renders the dashboard against a stubbed Open-Meteo.
 *
 * <p>This is the wiring test: REST client configuration, JSON binding, CDI construction and Qute
 * rendering only exist together at runtime, so unit tests cannot cover this seam.
 */
@QuarkusTest
@WithTestResource(OpenMeteoWireMockResource.class)
class WeatherResourceTest {

    @Test
    void rendersACardPerCity() {
        given()
                .when().get("/")
                .then()
                .statusCode(200)
                .contentType(containsString("text/html"))
                .body(containsString("Glasgow"))
                .body(containsString("United Kingdom"))
                .body(containsString("Samara"))
                .body(containsString("Russia"))
                .body(containsString("Nha Trang"))
                .body(containsString("Vietnam"));
    }

    @Test
    void rendersMeasurementsForReachableCities() {
        given()
                .when().get("/")
                .then()
                .statusCode(200)
                .body(containsString("15.3"))       // Glasgow temperature
                .body(containsString("Overcast"))   // WMO code 3
                .body(containsString("72"))         // Glasgow humidity
                .body(containsString("24.1"))       // Samara temperature
                .body(containsString("Clear sky")); // WMO code 0
    }

    @Test
    void degradesOnlyTheUnreachableCity() {
        given()
                .when().get("/")
                .then()
                .statusCode(200)
                .body(containsString(WeatherService.UNAVAILABLE_MESSAGE))
                .body(containsString("Glasgow"))
                .body(containsString("Samara"));
    }

    @Test
    void statesTheDataSource() {
        given()
                .when().get("/")
                .then()
                .statusCode(200)
                .body(containsString("Open-Meteo"));
    }
}
