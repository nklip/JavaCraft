package dev.nklip.javacraft.shardshop.workload.seeder;

import dev.nklip.javacraft.shardshop.common.Sha256;
import dev.nklip.javacraft.shardshop.workload.seeder.SeederDatasets.CreatedProduct;
import dev.nklip.javacraft.shardshop.workload.seeder.SeederDatasets.CreatedSeller;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/** The verified service-issued IDs of one seeding run, in seeding order. */
public record SeedReport(String catalogVersion, List<CreatedSeller> sellers, List<CreatedProduct> products) {
    public SeedReport {
        sellers = List.copyOf(sellers);
        products = List.copyOf(products);
    }

    /**
     * Lowercase hex SHA-256 of one "key=sellerId" line per seller and one "key=sellerId/productId" line per
     * product, each ended by LF. Equal digests show that two runs recovered the same IDs.
     */
    public String mappingDigest() {
        StringBuilder mapping = new StringBuilder();
        for (CreatedSeller seller : sellers) {
            mapping.append(seller.definition().key()).append('=').append(seller.sellerId()).append('\n');
        }
        for (CreatedProduct product : products) {
            mapping.append(product.definition().key()).append('=').append(product.sellerId()).append('/')
                    .append(product.productId()).append('\n');
        }
        return String.format("%064x", Sha256.unsignedDigest(mapping.toString()));
    }

    public String summary() {
        Map<String, String> regionsBySellerKey = new HashMap<>();
        Map<String, Integer> sellerCounts = new LinkedHashMap<>();
        for (CreatedSeller seller : sellers) {
            regionsBySellerKey.put(seller.definition().key(), seller.definition().region());
            sellerCounts.merge(seller.definition().region(), 1, Integer::sum);
        }
        Map<String, Map<String, Integer>> productCounts = new HashMap<>();
        for (CreatedProduct product : products) {
            productCounts.computeIfAbsent(regionsBySellerKey.get(product.definition().sellerKey()),
                    region -> new LinkedHashMap<>()).merge(product.definition().currency(), 1, Integer::sum);
        }
        StringJoiner regions = new StringJoiner("; ", "[", "]");
        sellerCounts.forEach((region, count) -> {
            Map<String, Integer> currencies = productCounts.getOrDefault(region, Map.of());
            int total = currencies.values().stream().mapToInt(Integer::intValue).sum();
            regions.add(region + " " + count + " sellers, " + total + " products " + currencies);
        });
        return "Seeded and verified " + catalogVersion + " " + regions + ", key-to-ID SHA-256 " + mappingDigest();
    }
}
