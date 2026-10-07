package dev.nklip.javacraft.shardshop.workload.producer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nklip.javacraft.shardshop.common.IdParser;
import dev.nklip.javacraft.shardshop.common.JsonFields;
import dev.nklip.javacraft.shardshop.common.JsonHttpClient;
import dev.nklip.javacraft.shardshop.workload.dataset.BuyerDataset;
import dev.nklip.javacraft.shardshop.workload.dataset.BuyerDataset.Address;
import dev.nklip.javacraft.shardshop.workload.dataset.BuyerDataset.BuyerDefinition;
import dev.nklip.javacraft.shardshop.workload.dataset.CatalogDataset;
import dev.nklip.javacraft.shardshop.workload.dataset.CatalogDataset.ProductDefinition;
import dev.nklip.javacraft.shardshop.workload.dataset.CatalogDataset.SellerDefinition;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Resolves workload definitions to service-issued IDs without deriving IDs or starting load traffic. */
public final class ProducerDatasets {
    private final JsonHttpClient product;
    private final CatalogDataset catalog;
    private final JsonHttpClient order;
    private final BuyerDataset buyers;

    public ProducerDatasets(JsonHttpClient product, String catalogVersion, JsonHttpClient order, String buyerVersion) {
        this.product = Objects.requireNonNull(product);
        this.catalog = new CatalogDataset(catalogVersion);
        this.order = Objects.requireNonNull(order);
        this.buyers = new BuyerDataset(buyerVersion);
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

    public List<BuyerDefinition> buyers() {
        return buyers.buyers();
    }

    public CreatedBuyer createBuyer(BuyerDefinition definition) throws IOException, InterruptedException {
        if (!buyers().contains(definition)) {
            throw new IllegalArgumentException("Buyer does not belong to the selected dataset");
        }
        Address address = definition.address();
        ObjectNode payload = JsonNodeFactory.instance.objectNode().put("firstName", definition.firstName())
                .put("surname", definition.surname()).put("email", definition.email()).put("phone", definition.phone())
                .put("region", definition.region());
        payload.set("address", JsonNodeFactory.instance.objectNode().put("line1", address.line1())
                .put("city", address.city()).put("postalCode", address.postalCode()).put("countryCode", address.countryCode()));
        return order.post("/api/v1/buyers", definition.key(), payload, response -> {
            JsonFields.requireFields(response, "buyerId", "firstName", "surname", "email", "phone", "address", "region");
            if (!definition.firstName().equals(JsonFields.text(response, "firstName"))
                    || !definition.surname().equals(JsonFields.text(response, "surname"))
                    || !definition.email().equals(JsonFields.text(response, "email"))
                    || !definition.phone().equals(JsonFields.text(response, "phone"))
                    || !definition.region().equals(JsonFields.text(response, "region"))) {
                throw new IllegalArgumentException("Buyer response does not match its definition");
            }
            JsonNode responseAddress = response.get("address");
            JsonFields.requireFields(responseAddress, "line1", "city", "postalCode", "countryCode");
            if (!address.line1().equals(JsonFields.text(responseAddress, "line1"))
                    || !address.city().equals(JsonFields.text(responseAddress, "city"))
                    || !address.postalCode().equals(JsonFields.text(responseAddress, "postalCode"))
                    || !address.countryCode().equals(JsonFields.text(responseAddress, "countryCode"))) {
                throw new IllegalArgumentException("Buyer address does not match its definition");
            }
            return new CreatedBuyer(definition, JsonFields.id(response, "buyerId"));
        });
    }

    public record CreatedBuyer(BuyerDefinition definition, String buyerId) { }

    public record SellerDescriptor(SellerDefinition definition, String sellerId, Map<String, String> profitsEarned) {
        public SellerDescriptor {
            profitsEarned = Map.copyOf(profitsEarned);
        }
    }

    public record ProductDescriptor(ProductDefinition definition, String sellerId, String productId, int stock) { }
}
