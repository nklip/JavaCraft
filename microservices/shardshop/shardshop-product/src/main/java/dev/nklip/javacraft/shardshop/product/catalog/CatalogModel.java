package dev.nklip.javacraft.shardshop.product.catalog;

import java.util.List;
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

    public record ListedProduct(long sellerId, long productId, String sellerName, String name, String region,
                                int initialStock, int stock, String price, String currency,
                                Map<String, String> profitsEarned) {
        public ListedProduct {
            profitsEarned = Map.copyOf(profitsEarned);
        }
    }

    public record ProductPage(String region, List<ListedProduct> items, Long nextAfter) {
        public ProductPage {
            items = List.copyOf(items);
        }
    }

    public record Creation<T>(T entity, boolean created) {
    }
}
