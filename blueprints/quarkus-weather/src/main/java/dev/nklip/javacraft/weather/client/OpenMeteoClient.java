package dev.nklip.javacraft.weather.client;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * Open-Meteo's free forecast API. No key, no auth, no rate-limit headers to honour.
 *
 * <p>Declarative only: the implementation is generated at build time. Base URL and the connect /
 * read timeouts live under the {@code open-meteo} config key in {@code application.properties}.
 */
@Path("/v1")
@RegisterRestClient(configKey = "open-meteo")
public interface OpenMeteoClient {

    @GET
    @Path("/forecast")
    @Produces(MediaType.APPLICATION_JSON)
    OpenMeteoForecast currentWeather(
            @QueryParam("latitude") double latitude,
            @QueryParam("longitude") double longitude,
            @QueryParam("current") String currentFields,
            @QueryParam("timezone") String timezone);
}
