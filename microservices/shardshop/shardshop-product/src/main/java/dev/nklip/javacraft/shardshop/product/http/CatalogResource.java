package dev.nklip.javacraft.shardshop.product.http;

import dev.nklip.javacraft.shardshop.product.catalog.CatalogModel.Creation;
import dev.nklip.javacraft.shardshop.product.catalog.CatalogModel.Product;
import dev.nklip.javacraft.shardshop.product.catalog.CatalogModel.ProductCreation;
import dev.nklip.javacraft.shardshop.product.catalog.CatalogModel.Seller;
import dev.nklip.javacraft.shardshop.product.catalog.CatalogModel.SellerCreation;
import dev.nklip.javacraft.shardshop.product.catalog.CatalogService;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.util.Map;

@Path("/api/v1/sellers")
@Produces(MediaType.APPLICATION_JSON)
@Blocking
public class CatalogResource {
    private final CatalogService service;
    private final CatalogDeadline deadline;
    private final CatalogInput input = new CatalogInput();

    @Inject
    public CatalogResource(CatalogService service, CatalogDeadline deadline) {
        this.service = service;
        this.deadline = deadline;
    }

    @POST
    public Response createSeller(byte[] body, @Context HttpHeaders headers, @Context UriInfo uri) {
        input.noQuery(uri);
        String key = input.headerKey(headers);
        SellerCreation payload = input.seller(body, headers);
        deadline.check();
        Creation<Seller> result = service.createSeller(key, payload);
        return Response.status(result.created() ? 201 : 200).entity(sellerResponse(result.entity())).build();
    }

    @GET
    @Path("/by-key/{creationKey}")
    public SellerResponse sellerByKey(@PathParam("creationKey") String key, @Context UriInfo uri) {
        String validKey = input.key(key);
        String region = input.regionQuery(uri);
        deadline.check();
        return sellerResponse(service.sellerByKey(region, validKey));
    }

    @GET
    @Path("/{sellerId}")
    public SellerResponse seller(@PathParam("sellerId") String sellerId, @Context UriInfo uri) {
        input.noQuery(uri);
        long validSellerId = input.id(sellerId);
        deadline.check();
        return sellerResponse(service.seller(validSellerId));
    }

    @POST
    @Path("/{sellerId}/products")
    public Response createProduct(@PathParam("sellerId") String sellerId, byte[] body,
                                  @Context HttpHeaders headers, @Context UriInfo uri) {
        input.noQuery(uri);
        long validSellerId = input.id(sellerId);
        String key = input.headerKey(headers);
        ProductCreation payload = input.product(body, headers);
        deadline.check();
        Creation<Product> result = service.createProduct(validSellerId, key, payload);
        return Response.status(result.created() ? 201 : 200).entity(productResponse(result.entity())).build();
    }

    @GET
    @Path("/{sellerId}/products/by-key/{creationKey}")
    public ProductResponse productByKey(@PathParam("sellerId") String sellerId,
                                        @PathParam("creationKey") String key, @Context UriInfo uri) {
        input.noQuery(uri);
        long validSellerId = input.id(sellerId);
        String validKey = input.key(key);
        deadline.check();
        return productResponse(service.productByKey(validSellerId, validKey));
    }

    @GET
    @Path("/{sellerId}/products/{productId}")
    public ProductResponse product(@PathParam("sellerId") String sellerId, @PathParam("productId") String productId,
                                   @Context UriInfo uri) {
        input.noQuery(uri);
        long validSellerId = input.id(sellerId);
        long validProductId = input.id(productId);
        deadline.check();
        return productResponse(service.product(validSellerId, validProductId));
    }

    private SellerResponse sellerResponse(Seller seller) {
        return new SellerResponse(Long.toString(seller.sellerId()), seller.companyName(), seller.region(),
                seller.profitsEarned());
    }

    private ProductResponse productResponse(Product product) {
        ProductCreation creation = product.creation();
        return new ProductResponse(Long.toString(product.sellerId()), Long.toString(product.productId()),
                creation.name(), creation.description(), creation.price(), creation.unitCost(), creation.currency(),
                creation.initialStock(), product.stock());
    }

    public record SellerResponse(String sellerId, String companyName, String region, Map<String, String> profitsEarned) {
    }

    public record ProductResponse(String sellerId, String productId, String name, String description,
                                  String price, String unitCost, String currency, int initialStock, int stock) {
    }
}
