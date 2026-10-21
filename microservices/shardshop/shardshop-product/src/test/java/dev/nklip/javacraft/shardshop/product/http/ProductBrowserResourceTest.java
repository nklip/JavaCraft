package dev.nklip.javacraft.shardshop.product.http;

import dev.nklip.javacraft.shardshop.product.catalog.CatalogFailure;
import dev.nklip.javacraft.shardshop.product.catalog.CatalogModel.ListedProduct;
import dev.nklip.javacraft.shardshop.product.catalog.CatalogModel.ProductPage;
import dev.nklip.javacraft.shardshop.product.catalog.CatalogService;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductBrowserResourceTest {
    private final CatalogService service = mock();
    private final CatalogDeadline deadline = mock();
    private final ProductBrowserResource resource = new ProductBrowserResource(service, deadline);

    @Test
    void mapsExactIdsSignedEarningsAndLookaheadCursorToStrings() {
        var product = new ListedProduct(9007199254740993L, Long.MAX_VALUE, "Company", "Mug", "EU", 10, 7,
                "99999999999999999.99", "EUR", Map.of("EUR", "-100000000000000000.01"));
        when(service.products("EU", 1, 9007199254740993L))
                .thenReturn(new ProductPage("EU", List.of(product), Long.MAX_VALUE));
        var query = uri(Map.of("region", "EU", "limit", "1", "after", "9007199254740993"));

        var response = resource.products(query);

        assertEquals("EU", response.region());
        assertEquals("9223372036854775807", response.nextAfter());
        assertEquals(List.of(new ProductBrowserResource.ProductSummary("9007199254740993", "9223372036854775807",
                "Company", "Mug", "EU", 10, 7, "99999999999999999.99", "EUR", product.profitsEarned(), null, false, "UNRESOLVED_SELLER_COUNTRY")), response.items());
        var order = inOrder(deadline, service);
        order.verify(deadline).check();
        order.verify(service).products("EU", 1, 9007199254740993L);
        order.verify(deadline).check();
    }

    @Test
    void emptyPageRetainsNullCursor() {
        when(service.products("US", 25, 0)).thenReturn(new ProductPage("US", List.of(), null));
        assertEquals(new ProductBrowserResource.ProductPageResponse("US", List.of(), null),
                resource.products(uri(Map.of("region", "US"))));
    }

    @Test
    void invalidAndExpiredRequestsNeverReadTheCatalog() {
        assertEquals(CatalogFailure.Reason.INVALID_REQUEST,
                assertThrows(CatalogFailure.class, () -> resource.products(uri(Map.of()))).reason());
        doThrow(new CatalogFailure(CatalogFailure.Reason.REPLICA_UNAVAILABLE)).when(deadline).check();
        assertEquals(CatalogFailure.Reason.REPLICA_UNAVAILABLE,
                assertThrows(CatalogFailure.class, () -> resource.products(uri(Map.of("region", "US")))).reason());
        verifyNoInteractions(service);
    }

    private UriInfo uri(Map<String, String> query) {
        UriInfo uri = mock();
        when(uri.getQueryParameters()).thenReturn(new MultivaluedHashMap<>(query));
        return uri;
    }
}
