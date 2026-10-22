package dev.nklip.javacraft.shardshop.workload.control;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class ControlFailureMapper implements ExceptionMapper<ControlFailure> {
    @Override
    public Response toResponse(ControlFailure failure) {
        return switch (failure.reason()) {
            case INVALID_REQUEST -> error(400, "INVALID_REQUEST", "Request does not match the control contract.");
            case INVALID_RATE -> error(400, "INVALID_RATE",
                    "Rate must be a number from 0 to the maximum rate with at most three decimal places. "
                            + "The previous setting is unchanged.");
            case RUN_ACTIVE -> error(409, "RUN_ACTIVE", "A seed run is already in progress.");
        };
    }

    private static Response error(int status, String code, String message) {
        return Response.status(status).type(MediaType.APPLICATION_JSON_TYPE).entity(new Error(code, message)).build();
    }

    public record Error(String code, String message) {
    }
}
