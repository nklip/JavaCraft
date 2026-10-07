package dev.nklip.javacraft.shardshop.workload.reader;

import dev.nklip.javacraft.shardshop.common.IdParser;
import dev.nklip.javacraft.shardshop.common.JsonFields;
import dev.nklip.javacraft.shardshop.common.JsonHttpClient;
import dev.nklip.javacraft.shardshop.workload.dataset.CatalogDataset;
import dev.nklip.javacraft.shardshop.workload.dataset.CatalogDataset.ProductDefinition;
import dev.nklip.javacraft.shardshop.workload.dataset.CatalogDataset.SellerDefinition;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Resolves workload definitions to service-issued IDs without deriving IDs or starting load traffic. */
public final class ReaderDatasets {
    private final JsonHttpClient product;
    private final CatalogDataset catalog;

    public ReaderDatasets(JsonHttpClient product, String catalogVersion) {
        this.product = Objects.requireNonNull(product);
        this.catalog = new CatalogDataset(catalogVersion);
    }

    public List<SellerDefinition> sellers() {
        return catalog.sellers();
    }

    public List<ProductDefinition> products() {
        return catalog.products();
    }

    public SellerDescriptor lookupSeller(SellerDefinition definition) throws IOException, InterruptedException {
        if (!sellers().contains(definition)) {
            throw new IllegalArgumentException("Seller does not belong to the selected dataset");
        }
        return product.get("/api/v1/sellers/by-key/" + definition.key() + "?region=" + definition.region(), response -> {
            JsonFields.requireFields(response, "sellerId", "companyName", "region", "profitsEarned");
            if (!definition.companyName().equals(JsonFields.text(response, "companyName"))
                    || !definition.region().equals(JsonFields.text(response, "region"))) {
                throw new IllegalArgumentException("Seller response does not match its definition");
            }
            return new SellerDescriptor(definition, JsonFields.id(response, "sellerId"),
                    JsonFields.signedMoneyMap(response, "profitsEarned", "USD", "EUR"));
        });
    }

    public ProductDescriptor lookupProduct(ProductDefinition definition, SellerDescriptor seller)
            throws IOException, InterruptedException {
        if (!products().contains(definition) || !sellers().contains(seller.definition())
                || !definition.sellerKey().equals(seller.definition().key())) {
            throw new IllegalArgumentException("Product does not belong to the selected seller and dataset");
        }
        String sellerId = seller.sellerId();
        new IdParser().parse(sellerId);
        return product.get("/api/v1/sellers/" + sellerId + "/products/by-key/" + definition.key(), response -> {
            JsonFields.requireFields(response, "sellerId", "productId", "name", "description",
                    "price", "unitCost", "currency", "initialStock", "stock");
            if (!sellerId.equals(JsonFields.id(response, "sellerId"))
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
            return new ProductDescriptor(definition, sellerId, JsonFields.id(response, "productId"), stock);
        });
    }

    public HttpRequest readProduct(String sellerId, String productId) {
        new IdParser().parse(sellerId);
        new IdParser().parse(productId);
        return product.get("/api/v1/sellers/" + sellerId + "/products/" + productId);
    }

    public record SellerDescriptor(SellerDefinition definition, String sellerId, Map<String, String> profitsEarned) {
        public SellerDescriptor {
            profitsEarned = Map.copyOf(profitsEarned);
        }
    }

    public record ProductDescriptor(ProductDefinition definition, String sellerId, String productId, int stock) { }
}
