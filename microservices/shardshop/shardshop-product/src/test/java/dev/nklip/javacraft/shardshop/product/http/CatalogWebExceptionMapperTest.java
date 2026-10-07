package dev.nklip.javacraft.shardshop.product.http;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

class CatalogWebExceptionMapperTest {
    private final CatalogWebExceptionMapper mapper = new CatalogWebExceptionMapper();

    @Test
    void convertsTheFrameworkEmptyBadRequestIntoTheInputContractError() {
        try (Response response = mapper.toResponse(new WebApplicationException(Response.status(400).build()))) {
            assertEquals(400, response.getStatus());
            assertEquals(MediaType.APPLICATION_JSON_TYPE, response.getMediaType());
            assertEquals(new CatalogFailureMapper.Error("INVALID_REQUEST", "Request does not match the input contract."),
                    assertInstanceOf(CatalogFailureMapper.Error.class, response.getEntity()));
        }
    }

    @Test
    void neverIncludesTheFrameworkExceptionMessageInTheErrorBody() {
        try (Response response = mapper.toResponse(new BadRequestException("private raw header value"))) {
            assertEquals(400, response.getStatus());
            assertEquals(new CatalogFailureMapper.Error("INVALID_REQUEST", "Request does not match the input contract."),
                    assertInstanceOf(CatalogFailureMapper.Error.class, response.getEntity()));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 405, 406, 415, 500})
    void preservesUnrelatedFrameworkResponsesAndTheirHeaders(int status) {
        try (Response original = Response.status(status).header("Allow", "GET").build()) {
            assertSame(original, mapper.toResponse(new WebApplicationException(original)));
            assertEquals("GET", original.getHeaderString("Allow"));
        }
    }
}
