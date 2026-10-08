package dev.nklip.javacraft.shardshop.workload.reader.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import dev.nklip.javacraft.shardshop.common.IdParser;
import dev.nklip.javacraft.shardshop.common.JsonFields;
import dev.nklip.javacraft.shardshop.workload.dataset.CatalogDataset;
import dev.nklip.javacraft.shardshop.workload.dataset.CatalogDataset.ProductDefinition;
import dev.nklip.javacraft.shardshop.workload.dataset.CatalogDataset.SellerDefinition;
import dev.nklip.javacraft.shardshop.workload.reader.http.Read;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Builds product reads for workload definitions and checks each response against its definition. The reader resolves
 * service-issued IDs through creation-key lookups; it never generates or derives IDs.
 */
public final class ReaderDatasets {
    private final String catalogVersion;
    private final CatalogDataset catalog;
    private final Set<SellerDefinition> knownSellers;
    private final Set<ProductDefinition> knownProducts;

    public ReaderDatasets(String catalogVersion) {
        this.catalogVersion = catalogVersion;
        this.catalog = new CatalogDataset(catalogVersion);
        this.knownSellers = Set.copyOf(catalog.sellers());
        this.knownProducts = Set.copyOf(catalog.products());
    }

    public String catalogVersion() {
        return catalogVersion;
    }

    public List<SellerDefinition> sellers() {
        return catalog.sellers();
    }

    public List<ProductDefinition> products() {
        return catalog.products();
    }

    public Read<SellerDescriptor> lookupSeller(SellerDefinition definition) {
        if (!knownSellers.contains(definition)) {
            throw new IllegalArgumentException("Seller does not belong to the selected dataset");
        }
        return new Read<>("/api/v1/sellers/by-key/" + definition.key() + "?region=" + definition.region(), response -> {
            JsonFields.requireFields(response, "sellerId", "companyName", "region", "profitsEarned");
            if (!definition.companyName().equals(JsonFields.text(response, "companyName"))
                    || !definition.region().equals(JsonFields.text(response, "region"))) {
                throw new IllegalArgumentException("Seller response does not match its definition");
            }
            return new SellerDescriptor(definition, JsonFields.id(response, "sellerId"),
                    JsonFields.signedMoneyMap(response, "profitsEarned", "USD", "EUR"));
        });
    }

    public Read<ProductDescriptor> lookupProduct(ProductDefinition definition, SellerDescriptor seller) {
        if (!knownProducts.contains(definition) || !knownSellers.contains(seller.definition())
                || !definition.sellerKey().equals(seller.definition().key())) {
            throw new IllegalArgumentException("Product does not belong to the selected seller and dataset");
        }
        String sellerId = seller.sellerId();
        new IdParser().parse(sellerId);
        return new Read<>("/api/v1/sellers/" + sellerId + "/products/by-key/" + definition.key(),
                productDecoder(definition, sellerId, null));
    }

    /** Reads a resolved product by its issued IDs. The response must keep both IDs and the definition payload. */
    public Read<ProductDescriptor> readProduct(ProductDescriptor product) {
        if (!knownProducts.contains(product.definition())) {
            throw new IllegalArgumentException("Product does not belong to the selected dataset");
        }
        new IdParser().parse(product.sellerId());
        new IdParser().parse(product.productId());
        return new Read<>("/api/v1/sellers/" + product.sellerId() + "/products/" + product.productId(),
                productDecoder(product.definition(), product.sellerId(), product.productId()));
    }

    private static Function<JsonNode, ProductDescriptor> productDecoder(ProductDefinition definition, String sellerId,
                                                                        String productId) {
        return response -> {
            JsonFields.requireFields(response, "sellerId", "productId", "name", "description",
                    "price", "unitCost", "currency", "initialStock", "stock");
            String returnedProductId = JsonFields.id(response, "productId");
            if (!sellerId.equals(JsonFields.id(response, "sellerId"))
                    || (productId != null && !productId.equals(returnedProductId))
                    || !definition.name().equals(JsonFields.text(response, "name"))
                    || !definition.description().equals(JsonFields.text(response, "description"))
                    || !definition.unitCost().equals(JsonFields.text(response, "unitCost"))
                    || !definition.price().equals(JsonFields.text(response, "price"))
                    || !definition.currency().equals(JsonFields.text(response, "currency"))
                    || definition.initialStock() != JsonFields.nonnegativeInteger(response, "initialStock")) {
                throw new IllegalArgumentException("Product response does not match its definition");
            }
            int stock = JsonFields.nonnegativeInteger(response, "stock");
            if (stock > definition.initialStock()) {
                throw new IllegalArgumentException("Product stock exceeds its initial stock");
            }
            return new ProductDescriptor(definition, sellerId, returnedProductId, stock);
        };
    }

    public record SellerDescriptor(SellerDefinition definition, String sellerId, Map<String, String> profitsEarned) {
        public SellerDescriptor {
            profitsEarned = Map.copyOf(profitsEarned);
        }
    }

    public record ProductDescriptor(ProductDefinition definition, String sellerId, String productId, int stock) { }
}
