package dev.nklip.javacraft.shardshop.product.http;

import dev.nklip.javacraft.shardshop.product.catalog.CatalogFailure;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class CatalogFailureMapper implements ExceptionMapper<CatalogFailure> {
    @Override
    public Response toResponse(CatalogFailure failure) {
        return switch (failure.reason()) {
            case INVALID_REQUEST -> error(400, "INVALID_REQUEST", "Request does not match the input contract.");
            case SELLER_NOT_FOUND -> error(404, "SELLER_NOT_FOUND", "Seller was not found.");
            case PRODUCT_NOT_FOUND -> error(404, "PRODUCT_NOT_FOUND", "Product was not found under this seller.");
            case MISSING_PARENT -> error(422, "SELLER_NOT_FOUND", "Seller was not found.");
            case SELLER_CONFLICT -> error(409, "SELLER_IDEMPOTENCY_CONFLICT",
                    "Seller idempotency key already has a different creation payload.");
            case PRODUCT_CONFLICT -> error(409, "PRODUCT_IDEMPOTENCY_CONFLICT",
                    "Product idempotency key already has a different creation payload.");
            case UNAVAILABLE -> error(503, "CATALOG_UNAVAILABLE",
                    "Catalog outcome is unknown; retry the same key and payload for creation requests.");
            case REPLICA_UNAVAILABLE -> error(503, "READ_REPLICA_UNAVAILABLE",
                    "Catalog read replica is temporarily unavailable.");
            case ID_UNAVAILABLE -> error(503, "ID_GENERATION_UNAVAILABLE",
                    "No ID could be generated within the bounded budget; retry the same key and payload.");
        };
    }

    private Response error(int status, String code, String message) {
        return Response.status(status).type(MediaType.APPLICATION_JSON_TYPE).entity(new Error(code, message)).build();
    }

    public record Error(String code, String message) {
    }
}
