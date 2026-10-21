package dev.nklip.javacraft.shardshop.product.catalog;

import dev.nklip.javacraft.shardshop.common.CurrencyPolicy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Catalog values; HTTP representations are owned by the adapter. */
public final class CatalogModel {
    private CatalogModel() {
    }

    public record SellerCreation(String companyName, String region, String countryCode) {
        public SellerCreation(String companyName, String region) {
            this(companyName, region, null);
        }
    }

    public record ProductCreation(String name, String description, String price, String unitCost,
                                  String currency, int initialStock) {
    }

    public record Seller(long sellerId, String companyName, String region, Map<String, String> profitsEarned, String countryCode) {
        public Seller(long sellerId, String companyName, String region, Map<String, String> profitsEarned) {
            this(sellerId, companyName, region, profitsEarned, null);
        }
        public Seller {
            profitsEarned = Map.copyOf(profitsEarned);
        }
    }

    public record Product(long sellerId, long productId, ProductCreation creation, int stock,
                          String region, String countryCode) {
        public Product(long sellerId, long productId, ProductCreation creation, int stock) {
            this(sellerId, productId, creation, stock, null, null);
        }

        public String legacyReason() {
            return eligibilityReason(region, countryCode, creation.currency(), creation.price(), creation.unitCost());
        }

        public boolean newOrderEligible() {
            return legacyReason() == null;
        }
    }

    public record ListedProduct(long sellerId, long productId, String sellerName, String name, String region,
                                int initialStock, int stock, String price, String currency,
                                Map<String, String> profitsEarned, String countryCode, String unitCost) {
        public ListedProduct(long sellerId, long productId, String sellerName, String name, String region,
                             int initialStock, int stock, String price, String currency, Map<String, String> profitsEarned) {
            this(sellerId, productId, sellerName, name, region, initialStock, stock, price, currency, profitsEarned, null, "0.00");
        }

        public String legacyReason() {
            return eligibilityReason(region, countryCode, currency, price, unitCost);
        }

        public boolean newOrderEligible() {
            return legacyReason() == null;
        }

        public ListedProduct {
            profitsEarned = Map.copyOf(profitsEarned);
        }
    }

    public record ProductPage(String region, List<ListedProduct> items, Long nextAfter) {
        public ProductPage {
            items = List.copyOf(items);
        }
    }

    private static String eligibilityReason(String region, String countryCode, String currency, String price, String unitCost) {
        if (countryCode == null) {
            return "UNRESOLVED_SELLER_COUNTRY";
        }
        if (!CurrencyPolicy.permits(region, countryCode, currency)) {
            return "DISALLOWED_SELLER_CURRENCY";
        }
        if (!CurrencyPolicy.validAmount(new BigDecimal(price), currency)
                || !CurrencyPolicy.validAmount(new BigDecimal(unitCost), currency)) {
            return "INVALID_CURRENCY_PRECISION";
        }
        return null;
    }

    public record Creation<T>(T entity, boolean created) {
    }
}
