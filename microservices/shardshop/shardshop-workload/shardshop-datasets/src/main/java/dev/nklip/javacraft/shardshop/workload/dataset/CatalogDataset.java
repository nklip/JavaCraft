package dev.nklip.javacraft.shardshop.workload.dataset;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable workload definitions; keys identify retries, while product creates entity IDs. */
public final class CatalogDataset {

    public static final String VERSION = "catalog-v1";
    private static final List<String> REGIONS = List.of("US", "EU", "ASIA");
    private static final List<String> CURRENCIES = List.of("USD", "EUR");
    private static final int SELLERS_PER_REGION = 50;
    private static final String SELLER_HEADER = "companyName\tcountryCode\trank";

    private final List<SellerDefinition> sellers;
    private final List<ProductDefinition> products;
    private final Map<String, List<SellerDefinition>> sellersByRegion;
    private final Map<String, List<ProductDefinition>> productsBySeller;

    public CatalogDataset(String version) {
        if (!VERSION.equals(version)) {
            throw new IllegalArgumentException("Unsupported catalog dataset version");
        }
        List<SellerDefinition> sellerDefinitions = new ArrayList<>();
        List<ProductDefinition> productDefinitions = new ArrayList<>();
        Map<String, List<SellerDefinition>> regionalSellers = new LinkedHashMap<>();
        Map<String, List<ProductDefinition>> sellerProducts = new LinkedHashMap<>();
        for (String region : REGIONS) {
            List<List<String>> rows = DatasetResources.load("/datasets/" + VERSION + "/" + region + ".tsv",
                    SELLER_HEADER, SELLERS_PER_REGION);
            List<SellerDefinition> regionSellers = new ArrayList<>();
            int productNumber = 1;
            for (int index = 0; index < rows.size(); index++) {
                String companyName = rows.get(index).getFirst();
                String sellerKey = VERSION + "." + region + ".seller." + (index + 1);
                regionSellers.add(new SellerDefinition(sellerKey, companyName, region));
                List<ProductDefinition> ownedProducts = new ArrayList<>();
                for (String currency : CURRENCIES) {
                    String productKey = VERSION + "." + region + ".product." + productNumber++;
                    ownedProducts.add(new ProductDefinition(productKey, sellerKey,
                            companyName + " Sample Product " + currency,
                            "Synthetic " + currency + " catalog item sold by " + companyName + ".",
                            "19.95", "12.50", currency, 1000));
                }
                sellerProducts.put(sellerKey, List.copyOf(ownedProducts));
                productDefinitions.addAll(ownedProducts);
            }
            regionalSellers.put(region, List.copyOf(regionSellers));
            sellerDefinitions.addAll(regionSellers);
        }
        sellers = List.copyOf(sellerDefinitions);
        products = List.copyOf(productDefinitions);
        sellersByRegion = Collections.unmodifiableMap(regionalSellers);
        productsBySeller = Collections.unmodifiableMap(sellerProducts);
    }

    public List<SellerDefinition> sellers() {
        return sellers;
    }

    public List<ProductDefinition> products() {
        return products;
    }

    public Map<String, List<SellerDefinition>> sellersByRegion() {
        return sellersByRegion;
    }

    public Map<String, List<ProductDefinition>> productsBySeller() {
        return productsBySeller;
    }

    public record SellerDefinition(String key, String companyName, String region) { }

    public record ProductDefinition(String key, String sellerKey, String name, String description,
                                    String price, String unitCost, String currency, int initialStock) { }
}
