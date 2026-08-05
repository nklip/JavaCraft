package dev.nklip.javacraft.weather.web;

import dev.nklip.javacraft.weather.assistant.ClaudeWeatherAssistant;
import dev.nklip.javacraft.weather.assistant.WeatherAnswer;
import dev.nklip.javacraft.weather.service.WeatherService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Asks Claude a question about the current observations.
 *
 * <p>HTTP mapping and input validation only — the grounding data and the model call belong to
 * {@link WeatherService} and {@link ClaudeWeatherAssistant}.
 */
@Path("/api/ask")
public class AssistantResource {

    private final WeatherService weatherService;
    private final ClaudeWeatherAssistant assistant;

    @Inject
    public AssistantResource(WeatherService weatherService, ClaudeWeatherAssistant assistant) {
        this.weatherService = weatherService;
        this.assistant = assistant;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response ask(AskRequest request) {
        if (request == null || !request.valid()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(AskResponse.invalidQuestion())
                    .build();
        }
        if (!assistant.configured()) {
            // 503 rather than 403: the capability is absent, not forbidden.
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(AskResponse.of(WeatherAnswer.disabled()))
                    .build();
        }
        WeatherAnswer answer =
                assistant.answer(request.question(), weatherService.currentWeather());
        return Response.ok(AskResponse.of(answer)).build();
    }
}
