package dev.nklip.javacraft.shardshop.workload.control;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.math.BigDecimal;

/**
 * The workload-owned control API. Each workload sets its own REST root path, for example
 * {@code /api/v1/product-reader}, thus the resource is {@code <root>/control}.
 */
@Path("/control")
@Produces(MediaType.APPLICATION_JSON)
public class ControlResource {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final WorkloadControl control;

    @Inject
    public ControlResource(WorkloadControl control) {
        this.control = control;
    }

    @GET
    public ControlStatus status() {
        return control.status();
    }

    /** Body {@code {"rate": <number>}}; the number uses the workload's unit. */
    @PUT
    @Path("/rate")
    @Consumes(MediaType.APPLICATION_JSON)
    public ControlStatus rate(String body) {
        return control.rate(requestedRate(body));
    }

    @POST
    @Path("/pause")
    public ControlStatus pause() {
        return control.pause();
    }

    @POST
    @Path("/resume")
    public ControlStatus resume() {
        return control.resume();
    }

    static BigDecimal requestedRate(String body) {
        JsonNode request;
        try {
            request = body == null ? null : JSON.readTree(body);
        } catch (JsonProcessingException malformed) {
            throw new ControlFailure(ControlFailure.Reason.INVALID_REQUEST);
        }
        if (request == null || !request.isObject() || request.size() != 1 || !request.has("rate")) {
            throw new ControlFailure(ControlFailure.Reason.INVALID_REQUEST);
        }
        JsonNode rate = request.get("rate");
        if (!rate.isNumber()) {
            throw new ControlFailure(ControlFailure.Reason.INVALID_RATE);
        }
        return rate.decimalValue();
    }
}
