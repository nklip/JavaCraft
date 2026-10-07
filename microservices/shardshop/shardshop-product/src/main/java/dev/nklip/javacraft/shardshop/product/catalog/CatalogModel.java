package dev.nklip.javacraft.shardshop.product.catalog;

import java.util.Map;

/** Catalog values; HTTP representations are owned by the adapter. */
public final class CatalogModel {
    private CatalogModel() {
    }

    public record SellerCreation(String companyName, String region) {
    }

    public record ProductCreation(String name, String description, String price, String unitCost,
                                  String currency, int initialStock) {
    }

    public record Seller(long sellerId, String companyName, String region, Map<String, String> profitsEarned) {
        public Seller {
            profitsEarned = Map.copyOf(profitsEarned);
        }
    }

    public record Product(long sellerId, long productId, ProductCreation creation, int stock) {
    }

    public record Creation<T>(T entity, boolean created) {
    }
}
