package dev.nklip.javacraft.shardshop.workload.control;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/** Covers malformed HTTP metadata rejected by REST before resource invocation. */
@Provider
public class ControlWebExceptionMapper implements ExceptionMapper<WebApplicationException> {
    @Override
    public Response toResponse(WebApplicationException failure) {
        if (failure.getResponse().getStatus() == Response.Status.BAD_REQUEST.getStatusCode()) {
            return new ControlFailureMapper().toResponse(new ControlFailure(ControlFailure.Reason.INVALID_REQUEST));
        }
        return failure.getResponse();
    }
}
