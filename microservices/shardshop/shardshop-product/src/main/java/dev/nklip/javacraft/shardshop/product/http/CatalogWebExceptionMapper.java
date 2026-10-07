package dev.nklip.javacraft.shardshop.product.http;

import dev.nklip.javacraft.shardshop.product.catalog.CatalogFailure;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/** Covers malformed HTTP metadata rejected by REST before resource invocation. */
@Provider
public class CatalogWebExceptionMapper implements ExceptionMapper<WebApplicationException> {
    @Override
    public Response toResponse(WebApplicationException failure) {
        if (failure.getResponse().getStatus() == Response.Status.BAD_REQUEST.getStatusCode()) {
            return new CatalogFailureMapper().toResponse(new CatalogFailure(CatalogFailure.Reason.INVALID_REQUEST));
        }
        return failure.getResponse();
    }
}
