package dev.nklip.javacraft.shardshop.order.http;

import dev.nklip.javacraft.shardshop.order.ordering.OrderFailure;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class OrderFailureMapper implements ExceptionMapper<OrderFailure> {
    @Override
    public Response toResponse(OrderFailure failure) {
        return switch (failure.reason()) {
            case INVALID_REQUEST -> error(400, failure.reason().name(), "Request does not match the input contract.");
            case BUYER_NOT_FOUND -> error(404, failure.reason().name(), "Buyer was not found.");
            case MISSING_BUYER -> error(422, "BUYER_NOT_FOUND", "Buyer was not found.");
            case ORDER_NOT_FOUND -> error(404, failure.reason().name(), "Order was not found under this buyer.");
            case BUYER_IDEMPOTENCY_CONFLICT -> error(409, failure.reason().name(),
                    "Buyer idempotency key already has a different creation payload.");
            case ORDER_ID_CONFLICT -> error(409, failure.reason().name(), "Order ID already has a different creation payload.");
            case ORDER_RECONCILIATION_REQUIRED -> error(409, failure.reason().name(),
                    "Operator reconciliation is required before reusing this order ID.");
            case PRODUCT_NOT_FOUND -> error(422, failure.reason().name(), "Product was not found under its seller.");
            case MIXED_CURRENCIES -> error(422, failure.reason().name(), "All products must use the same currency.");
            case PRODUCT_CURRENCY_POLICY -> error(422, failure.reason().name(), "Product is unavailable under the seller country currency policy.");
            case CURRENCY_MISMATCH -> error(422, failure.reason().name(), "Order currency must match the products.");
            case ORDER_STORE_UNAVAILABLE -> error(503, failure.reason().name(),
                    "Order store is temporarily unavailable; retry the same request.");
            case CATALOG_UNAVAILABLE -> error(503, failure.reason().name(), "Catalog lookup is temporarily unavailable.");
            case ID_GENERATION_UNAVAILABLE -> error(503, failure.reason().name(),
                    "No ID could be generated within the bounded budget; retry the same request.");
        };
    }

    private Response error(int status, String code, String message) {
        return Response.status(status).type(MediaType.APPLICATION_JSON_TYPE).entity(new Error(code, message)).build();
    }

    public record Error(String code, String message) {
    }
}
