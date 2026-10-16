package dev.nklip.javacraft.shardshop.order.http;

import dev.nklip.javacraft.shardshop.order.ordering.OrderFailure;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/** Covers malformed HTTP metadata rejected by REST before resource invocation. */
@Provider
public class OrderWebExceptionMapper implements ExceptionMapper<WebApplicationException> {
    @Override
    public Response toResponse(WebApplicationException failure) {
        if (failure.getResponse().getStatus() == Response.Status.BAD_REQUEST.getStatusCode()) {
            return new OrderFailureMapper().toResponse(new OrderFailure(OrderFailure.Reason.INVALID_REQUEST));
        }
        return failure.getResponse();
    }
}
