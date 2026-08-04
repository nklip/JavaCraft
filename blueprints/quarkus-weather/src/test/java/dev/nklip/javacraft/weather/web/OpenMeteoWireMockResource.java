package dev.nklip.javacraft.weather.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.github.tomakehurst.wiremock.WireMockServer;
import dev.nklip.javacraft.weather.domain.Cities;
import dev.nklip.javacraft.weather.domain.City;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.Map;

/**
 * Stands in for Open-Meteo so the page test never touches the network.
 *
 * <p>Nha Trang is deliberately stubbed to fail: one render then exercises both template branches
 * and proves a single unreachable city does not take the page down with it.
 */
public class OpenMeteoWireMockResource implements QuarkusTestResourceLifecycleManager {

    static final String GLASGOW_BODY = body("2026-08-04T12:00", 15.3, 72, 12.5, 3);
    static final String SAMARA_BODY = body("2026-08-04T17:00", 24.1, 40, 8.0, 0);

    private WireMockServer server;

    private static String body(
            String time, double temperature, int humidity, double wind, int weatherCode) {
        return """
                {
                  "current": {
                    "time": "%s",
                    "temperature_2m": %s,
                    "relative_humidity_2m": %d,
                    "wind_speed_10m": %s,
                    "weather_code": %d
                  }
                }
                """.formatted(time, temperature, humidity, wind, weatherCode);
    }

    @Override
    public Map<String, String> start() {
        server = new WireMockServer(options().dynamicPort());
        server.start();

        stubCity(Cities.GLASGOW, okJson(GLASGOW_BODY));
        stubCity(Cities.SAMARA, okJson(SAMARA_BODY));
        stubCity(Cities.NHA_TRANG, aResponse().withStatus(503));

        // Point the REST client at the stub instead of api.open-meteo.com.
        return Map.of("quarkus.rest-client.open-meteo.url", server.baseUrl());
    }

    private void stubCity(
            City city, com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder response) {
        server.stubFor(get(urlPathEqualTo("/v1/forecast"))
                .withQueryParam("latitude", equalTo(String.valueOf(city.latitude())))
                .willReturn(response));
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop();
        }
    }
}
