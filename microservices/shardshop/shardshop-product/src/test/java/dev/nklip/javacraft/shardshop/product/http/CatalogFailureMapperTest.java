package dev.nklip.javacraft.shardshop.product.http;

import dev.nklip.javacraft.shardshop.product.catalog.CatalogFailure;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class CatalogFailureMapperTest {
    @ParameterizedTest
    @CsvSource({"INVALID_REQUEST,400,INVALID_REQUEST", "SELLER_NOT_FOUND,404,SELLER_NOT_FOUND",
            "PRODUCT_NOT_FOUND,404,PRODUCT_NOT_FOUND", "MISSING_PARENT,422,SELLER_NOT_FOUND",
            "SELLER_CONFLICT,409,SELLER_IDEMPOTENCY_CONFLICT", "PRODUCT_CONFLICT,409,PRODUCT_IDEMPOTENCY_CONFLICT",
            "UNAVAILABLE,503,CATALOG_UNAVAILABLE", "REPLICA_UNAVAILABLE,503,READ_REPLICA_UNAVAILABLE",
            "ID_UNAVAILABLE,503,ID_GENERATION_UNAVAILABLE"})
    void mapsEveryTypedFailureToAStableContractResponse(CatalogFailure.Reason reason, int status, String code) {
        try (Response response = new CatalogFailureMapper().toResponse(new CatalogFailure(reason))) {
            assertEquals(status, response.getStatus());
            assertEquals(MediaType.APPLICATION_JSON_TYPE, response.getMediaType());
            CatalogFailureMapper.Error error = assertInstanceOf(CatalogFailureMapper.Error.class, response.getEntity());
            assertEquals(code, error.code());
            assertFalse(error.message().isBlank());
        }
    }
}
