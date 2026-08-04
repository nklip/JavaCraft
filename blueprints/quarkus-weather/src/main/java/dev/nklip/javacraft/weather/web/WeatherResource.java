package dev.nklip.javacraft.weather.web;

import dev.nklip.javacraft.weather.service.WeatherService;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** Serves the dashboard: HTTP mapping and template selection only, no weather logic. */
@Path("/")
public class WeatherResource {

    private final WeatherService weatherService;

    @Inject
    public WeatherResource(WeatherService weatherService) {
        this.weatherService = weatherService;
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance index() {
        return Templates.weather(weatherService.currentWeather());
    }
}
