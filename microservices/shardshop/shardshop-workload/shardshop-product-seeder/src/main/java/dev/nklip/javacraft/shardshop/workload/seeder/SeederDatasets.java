package dev.nklip.javacraft.shardshop.workload.seeder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.nklip.javacraft.shardshop.common.IdParser;
import dev.nklip.javacraft.shardshop.common.JsonFields;
import dev.nklip.javacraft.shardshop.common.JsonHttpClient;
import dev.nklip.javacraft.shardshop.workload.dataset.CatalogDataset;
import dev.nklip.javacraft.shardshop.workload.dataset.CatalogDataset.ProductDefinition;
import dev.nklip.javacraft.shardshop.workload.dataset.CatalogDataset.SellerDefinition;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Sends workload-owned creation payloads and retains the IDs issued by product. */
public final class SeederDatasets {
    private final JsonHttpClient product;
    private final CatalogDataset catalog;

    public SeederDatasets(JsonHttpClient product, String catalogVersion) {
        this.product = Objects.requireNonNull(product);
        this.catalog = new CatalogDataset(catalogVersion);
    }

    public List<SellerDefinition> sellers() {
        return catalog.sellers();
    }

    public List<ProductDefinition> products() {
        return catalog.products();
    }

    public CreatedSeller createSeller(SellerDefinition definition) throws IOException, InterruptedException {
        if (!sellers().contains(definition)) {
            throw new IllegalArgumentException("Seller does not belong to the selected dataset");
        }
        JsonNode payload = JsonNodeFactory.instance.objectNode()
                .put("companyName", definition.companyName()).put("region", definition.region());
        return product.post("/api/v1/sellers", definition.key(), payload, response -> {
            JsonFields.requireFields(response, "sellerId", "companyName", "region", "profitsEarned");
            if (!definition.companyName().equals(JsonFields.text(response, "companyName"))
                    || !definition.region().equals(JsonFields.text(response, "region"))) {
                throw new IllegalArgumentException("Seller response does not match its definition");
            }
            return new CreatedSeller(definition, JsonFields.id(response, "sellerId"),
                    JsonFields.signedMoneyMap(response, "profitsEarned", "USD", "EUR"));
        });
    }

    public CreatedProduct createProduct(ProductDefinition definition, CreatedSeller seller)
            throws IOException, InterruptedException {
        if (!products().contains(definition) || !sellers().contains(seller.definition())
                || !definition.sellerKey().equals(seller.definition().key())) {
            throw new IllegalArgumentException("Product does not belong to the selected seller and dataset");
        }
        new IdParser().parse(seller.sellerId());
        JsonNode payload = JsonNodeFactory.instance.objectNode().put("name", definition.name())
                .put("description", definition.description()).put("price", definition.price())
                .put("unitCost", definition.unitCost()).put("currency", definition.currency())
                .put("initialStock", definition.initialStock());
        return product.post("/api/v1/sellers/" + seller.sellerId() + "/products", definition.key(), payload, response -> {
            JsonFields.requireFields(response, "sellerId", "productId", "name", "description",
                    "price", "unitCost", "currency", "initialStock", "stock");
            if (!seller.sellerId().equals(JsonFields.id(response, "sellerId"))
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
            return new CreatedProduct(definition, seller.sellerId(), JsonFields.id(response, "productId"), stock);
        });
    }

    public record CreatedSeller(SellerDefinition definition, String sellerId, Map<String, String> profitsEarned) {
        public CreatedSeller {
            profitsEarned = Map.copyOf(profitsEarned);
        }
    }

    public record CreatedProduct(ProductDefinition definition, String sellerId, String productId, int stock) { }
}
