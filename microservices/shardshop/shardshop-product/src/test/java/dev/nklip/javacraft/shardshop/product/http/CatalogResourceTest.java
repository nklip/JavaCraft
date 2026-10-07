package dev.nklip.javacraft.shardshop.product.http;

import dev.nklip.javacraft.shardshop.product.catalog.CatalogFailure;
import dev.nklip.javacraft.shardshop.product.catalog.CatalogModel.Creation;
import dev.nklip.javacraft.shardshop.product.catalog.CatalogModel.Product;
import dev.nklip.javacraft.shardshop.product.catalog.CatalogModel.ProductCreation;
import dev.nklip.javacraft.shardshop.product.catalog.CatalogModel.Seller;
import dev.nklip.javacraft.shardshop.product.catalog.CatalogModel.SellerCreation;
import dev.nklip.javacraft.shardshop.product.catalog.CatalogService;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CatalogResourceTest {
    private static final long SELLER_ID = 9007199254740993L;
    private static final long PRODUCT_ID = Long.MAX_VALUE;
    private static final ProductCreation PRODUCT_CREATION = new ProductCreation("Mug", "A mug", "19.95", "12.45", "EUR", 2);
    private static final Product PRODUCT = new Product(SELLER_ID, PRODUCT_ID, PRODUCT_CREATION, 1);
    private static final Seller SELLER = new Seller(SELLER_ID, "Company", "EU", Map.of("USD", "-1.00", "EUR", "15.00"));
    private final CatalogService service = mock(CatalogService.class);
    private final CatalogDeadline deadline = mock(CatalogDeadline.class);
    private final CatalogResource resource = new CatalogResource(service, deadline);

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void returnsCreationOrRecoveryWithCurrentSellerProfits(boolean created) {
        when(service.createSeller("Key", new SellerCreation("Company", "EU"))).thenReturn(new Creation<>(SELLER, created));
        try (Response response = resource.createSeller(sellerBody(), headers(), uri())) {
            assertEquals(created ? 201 : 200, response.getStatus());
            assertEquals(sellerResponse(), assertInstanceOf(CatalogResource.SellerResponse.class, response.getEntity()));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void returnsCreationOrRecoveryWithCurrentProductStock(boolean created) {
        when(service.createProduct(SELLER_ID, "Key", PRODUCT_CREATION)).thenReturn(new Creation<>(PRODUCT, created));
        try (Response response = resource.createProduct(Long.toString(SELLER_ID), productBody(), headers(), uri())) {
            assertEquals(created ? 201 : 200, response.getStatus());
            assertEquals(productResponse(), assertInstanceOf(CatalogResource.ProductResponse.class, response.getEntity()));
        }
    }

    @Test
    void readsSellerByIdAndScopedCreationKey() {
        when(service.seller(SELLER_ID)).thenReturn(SELLER);
        when(service.sellerByKey("EU", "Key")).thenReturn(SELLER);
        assertEquals(sellerResponse(), resource.seller(Long.toString(SELLER_ID), uri()));
        UriInfo byKeyUri = mock(UriInfo.class);
        when(byKeyUri.getQueryParameters()).thenReturn(new MultivaluedHashMap<>(Map.of("region", "EU")));
        assertEquals(sellerResponse(), resource.sellerByKey("Key", byKeyUri));
    }

    @Test
    void readsProductByParentAndIdOrCreationKey() {
        when(service.product(SELLER_ID, PRODUCT_ID)).thenReturn(PRODUCT);
        when(service.productByKey(SELLER_ID, "Key")).thenReturn(PRODUCT);
        assertEquals(productResponse(), resource.product(Long.toString(SELLER_ID), Long.toString(PRODUCT_ID), uri()));
        assertEquals(productResponse(), resource.productByKey(Long.toString(SELLER_ID), "Key", uri()));
    }

    @Test
    void invalidInputAcrossAllRoutesNeverCallsTheService() {
        assertInvalid(() -> resource.createSeller(sellerBody(), mock(HttpHeaders.class), uri()));
        assertInvalid(() -> resource.sellerByKey("Key", uri()));
        assertInvalid(() -> resource.seller("01", uri()));
        assertInvalid(() -> resource.createProduct("01", productBody(), headers(), uri()));
        assertInvalid(() -> resource.productByKey(Long.toString(SELLER_ID), "-bad", uri()));
        assertInvalid(() -> resource.product(Long.toString(SELLER_ID), "01", uri()));
        verifyNoInteractions(service);
    }

    @Test
    void rejectsUnknownQueryOnEveryRouteBeforeCallingTheService() {
        UriInfo query = mock(UriInfo.class);
        when(query.getQueryParameters()).thenReturn(new MultivaluedHashMap<>(Map.of("shard", "a")));
        assertInvalid(() -> resource.createSeller(sellerBody(), headers(), query));
        assertInvalid(() -> resource.sellerByKey("Key", query));
        assertInvalid(() -> resource.seller("1", query));
        assertInvalid(() -> resource.createProduct("1", productBody(), headers(), query));
        assertInvalid(() -> resource.productByKey("1", "Key", query));
        assertInvalid(() -> resource.product("1", "2", query));
        verifyNoInteractions(service);
    }

    @Test
    void letsTheTypedFailureMapperHandleServiceErrors() {
        CatalogFailure missing = new CatalogFailure(CatalogFailure.Reason.SELLER_NOT_FOUND);
        when(service.seller(1)).thenThrow(missing);
        assertSame(missing, assertThrows(CatalogFailure.class, () -> resource.seller("1", uri())));
    }

    @Test
    void queuedExpiredRequestsNeverReachTheDatabaseService() {
        doThrow(new CatalogFailure(CatalogFailure.Reason.UNAVAILABLE)).when(deadline).check();
        UriInfo byKeyUri = mock(UriInfo.class);
        when(byKeyUri.getQueryParameters()).thenReturn(new MultivaluedHashMap<>(Map.of("region", "EU")));
        assertThrows(CatalogFailure.class, () -> resource.createSeller(sellerBody(), headers(), uri()));
        assertThrows(CatalogFailure.class, () -> resource.sellerByKey("Key", byKeyUri));
        assertThrows(CatalogFailure.class, () -> resource.seller("1", uri()));
        assertThrows(CatalogFailure.class, () -> resource.createProduct("1", productBody(), headers(), uri()));
        assertThrows(CatalogFailure.class, () -> resource.productByKey("1", "Key", uri()));
        assertThrows(CatalogFailure.class, () -> resource.product("1", "2", uri()));
        verifyNoInteractions(service);
    }

    private CatalogResource.SellerResponse sellerResponse() {
        return new CatalogResource.SellerResponse(Long.toString(SELLER_ID), "Company", "EU", SELLER.profitsEarned());
    }

    private CatalogResource.ProductResponse productResponse() {
        return new CatalogResource.ProductResponse(Long.toString(SELLER_ID), Long.toString(PRODUCT_ID),
                "Mug", "A mug", "19.95", "12.45", "EUR", 2, 1);
    }

    private HttpHeaders headers() {
        HttpHeaders headers = mock(HttpHeaders.class);
        when(headers.getRequestHeader("Idempotency-Key")).thenReturn(List.of("Key"));
        when(headers.getRequestHeader(HttpHeaders.CONTENT_TYPE)).thenReturn(List.of(MediaType.APPLICATION_JSON));
        when(headers.getMediaType()).thenReturn(MediaType.APPLICATION_JSON_TYPE);
        return headers;
    }

    private UriInfo uri() {
        UriInfo uri = mock(UriInfo.class);
        when(uri.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        return uri;
    }

    private byte[] sellerBody() {
        return "{\"companyName\":\"Company\",\"region\":\"EU\"}".getBytes(StandardCharsets.UTF_8);
    }

    private byte[] productBody() {
        return ("{\"name\":\"Mug\",\"description\":\"A mug\",\"price\":\"19.95\","
                + "\"unitCost\":\"12.45\",\"currency\":\"EUR\",\"initialStock\":2.0}").getBytes(StandardCharsets.UTF_8);
    }

    private void assertInvalid(org.junit.jupiter.api.function.Executable request) {
        assertEquals(CatalogFailure.Reason.INVALID_REQUEST, assertThrows(CatalogFailure.class, request).reason());
    }
}
