package dev.nklip.javacraft.shardshop.product.http;

import dev.nklip.javacraft.shardshop.product.catalog.CatalogService;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;

import java.util.List;
import java.util.Map;

/** Public read-only view of arbitrary created products in a seller home region. */
@Path("/api/v1/products")
@Produces(MediaType.APPLICATION_JSON)
@Blocking
public class ProductBrowserResource {
    private final CatalogService service;
    private final CatalogDeadline deadline;
    private final CatalogInput input = new CatalogInput();

    @Inject
    public ProductBrowserResource(CatalogService service, CatalogDeadline deadline) {
        this.service = service;
        this.deadline = deadline;
    }

    @GET
    public ProductPageResponse products(@Context UriInfo uri) {
        CatalogInput.ListingQuery query = input.listingQuery(uri);
        deadline.check();
        var page = service.products(query.region(), query.limit(), query.after());
        deadline.check();
        List<ProductSummary> items = page.items().stream().map(product -> new ProductSummary(
                Long.toString(product.sellerId()), Long.toString(product.productId()), product.sellerName(),
                product.name(), product.region(), product.initialStock(), product.stock(), product.price(),
                product.currency(), product.profitsEarned())).toList();
        return new ProductPageResponse(page.region(), items,
                page.nextAfter() == null ? null : Long.toString(page.nextAfter()));
    }

    public record ProductPageResponse(String region, List<ProductSummary> items, String nextAfter) {
    }

    public record ProductSummary(String sellerId, String productId, String sellerName, String name, String region,
                                 int initialStock, int stock, String price, String currency,
                                 Map<String, String> profitsEarned) {
    }
}
