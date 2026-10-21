package dev.nklip.javacraft.shardshop.testing.cucumber.world;

import dev.nklip.javacraft.shardshop.testing.cucumber.config.CatalogRouting;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.ShardShopFiles;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The catalog-v2 rows on the shards. The expected payloads come from the dataset TSV files and the step 3.5 rules,
 * written again here, independently of the Java dataset definitions.
 */
public final class StoredCatalog {
    public static final List<String> REGIONS = List.of("US", "EU", "ASIA");
    private static final List<Definition> DEFINITIONS = definitions();

    /** The payload of one dataset entity; {@code sellerKey} is null for a seller. */
    public record Definition(String key, String sellerKey, String region, String companyName, String name,
                             String description, String price, String unitCost, String currency, int initialStock, String countryCode) {
        public boolean seller() {
            return sellerKey == null;
        }
    }

    /** One stored entity with its shard; the product fields are null for a seller. */
    public record Row(String key, String shard, String sellerId, String productId, String region, String companyName,
                      String name, String description, String price, String unitCost, String currency,
                      Integer initialStock, Integer stock, String countryCode) {
    }

    private StoredCatalog() {
    }

    private static List<Definition> definitions() {
        List<Definition> sellers = new ArrayList<>();
        List<Definition> products = new ArrayList<>();
        for (String region : REGIONS) {
            List<String> rows;
            try {
                rows = Files.readAllLines(ShardShopFiles.resolve("shardshop-workload/shardshop-datasets/src/main/"
                        + "resources/datasets/catalog-v2/" + region + ".tsv"), StandardCharsets.UTF_8);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
            assertEquals("companyName\tcountryCode\trank", rows.getFirst(), "Header of " + region);
            assertEquals(region.equals("ASIA") ? 52 : 51, rows.size(), "Rows of " + region);
            int productOrdinal = 0;
            for (int ordinal = 1; ordinal < rows.size(); ordinal++) {
                String company = rows.get(ordinal).split("\t")[0];
                String country = rows.get(ordinal).split("\t")[1];
                String sellerKey = "catalog-v2." + region + ".seller." + ordinal;
                sellers.add(new Definition(sellerKey, null, region, company, null, null, null, null, null, 0, country));
                List<String> currencies = switch (region) {
                    case "US" -> List.of("USD");
                    case "EU" -> List.of("EUR", "GBP");
                    case "ASIA" -> List.of(switch (country) { case "CN" -> "CNY"; case "VN" -> "VND"; case "KR" -> "KRW"; case "JP" -> "JPY"; default -> throw new AssertionError(country); });
                    default -> throw new AssertionError(region);
                };
                for (int offset = 1; offset <= currencies.size(); offset++) {
                    String currency = currencies.get(offset - 1);
                    products.add(new Definition("catalog-v2." + region + ".product." + (++productOrdinal),
                            sellerKey, region, null, company + " Sample Product " + currency,
                            "Synthetic " + currency + " catalog item sold by " + company + ".",
                            List.of("JPY", "KRW", "VND").contains(currency) ? "20.00" : "19.95",
                            List.of("JPY", "KRW", "VND").contains(currency) ? "12.00" : "12.50", currency, 1000, country));
                }
            }
        }
        List<Definition> all = new ArrayList<>(sellers);
        all.addAll(products);
        return List.copyOf(all);
    }

    /** All definitions in dataset order: the sellers first, then the products. */
    public static List<Definition> definitions(boolean sellers) {
        return DEFINITIONS.stream().filter(definition -> definition.seller() == sellers).toList();
    }

    /** Reads the sellers and products of all shards by creation key, and checks their zero profits and placement. */
    public static Map<String, Row> read(CatalogShards shards) throws SQLException {
        Map<String, Row> rows = new LinkedHashMap<>();
        for (CatalogShards.Shard shard : shards.all()) {
            try (Connection connection = shards.superuser(shard); Statement statement = connection.createStatement()) {
                Map<String, String> sellerIds = new HashMap<>();
                try (ResultSet sellers = statement.executeQuery("SELECT creation_key, seller_id, region, company_name, "
                        + "(SELECT string_agg(currency || '=' || amount, ',' ORDER BY currency) FROM catalog.seller_profits p "
                        + "WHERE p.seller_id = s.seller_id) AS profits, country_code FROM catalog.sellers s")) {
                    while (sellers.next()) {
                        Row row = new Row(sellers.getString(1), shard.name(), sellers.getString(2), null,
                                sellers.getString(3), sellers.getString(4), null, null, null, null, null, null, null, sellers.getString(6));
                        String expectedProfits = switch (row.region()) {
                            case "US" -> "USD=0.00"; case "EU" -> "EUR=0.00,GBP=0.00";
                            case "ASIA" -> switch (row.countryCode()) { case "CN" -> "CNY=0.00"; case "VN" -> "VND=0.00"; case "KR" -> "KRW=0.00"; case "JP" -> "JPY=0.00"; default -> throw new AssertionError(row.countryCode()); };
                            default -> throw new AssertionError(row.region());
                        };
                        assertEquals(expectedProfits, sellers.getString(5), "Profits of " + row.key());
                        assertNull(rows.put(row.key(), row), "A creation key is stored more than once: " + row.key());
                        sellerIds.put(row.sellerId(), row.key());
                    }
                }
                try (ResultSet products = statement.executeQuery("SELECT creation_key, seller_id, product_id, name, "
                        + "description, price, unit_cost, currency, initial_stock, stock FROM catalog.products")) {
                    while (products.next()) {
                        Row row = new Row(products.getString(1), shard.name(), products.getString(2),
                                products.getString(3), null, null, products.getString(4), products.getString(5),
                                products.getString(6), products.getString(7), products.getString(8),
                                products.getInt(9), products.getInt(10), null);
                        assertTrue(sellerIds.containsKey(row.sellerId()), "Product " + row.key() + " is apart from its seller");
                        assertNull(rows.put(row.key(), row), "A creation key is stored more than once: " + row.key());
                    }
                }
            }
        }
        return rows;
    }

    /** Checks the payload, the ID route and the key home of each stored entity against its definition. */
    public static void assertDataset(CatalogShards shards, Map<String, Row> rows, int sellers, int products) {
        Map<String, Definition> definitions = new HashMap<>();
        DEFINITIONS.forEach(definition -> definitions.put(definition.key(), definition));
        int storedSellers = 0;
        int storedProducts = 0;
        for (Row row : rows.values()) {
            Definition definition = definitions.get(row.key());
            assertNotNull(definition, "An unexpected creation key is stored: " + row.key());
            if (definition.seller()) {
                storedSellers++;
                assertEquals(definition.region(), row.region(), "Region of " + row.key());
                assertEquals(definition.countryCode(), row.countryCode(), "Country of " + row.key());
                assertEquals(definition.companyName(), row.companyName(), "Company of " + row.key());
                assertEquals(shards.ofKey(definition.region(), row.key()).name(), row.shard(), "Key home of " + row.key());
                assertEquals(shards.ofSeller(row.sellerId()).name(), row.shard(), "ID route of " + row.key());
            } else {
                storedProducts++;
                Row seller = rows.get(definition.sellerKey());
                assertTrue(seller != null && seller.sellerId().equals(row.sellerId()) && seller.shard().equals(row.shard()),
                        "Seller binding and placement of " + row.key());
                assertEquals(List.of(definition.name(), definition.description(), definition.price(),
                                definition.unitCost(), definition.currency(), definition.initialStock(), definition.initialStock()),
                        List.of(row.name(), row.description(), row.price(), row.unitCost(), row.currency(),
                                row.initialStock(), row.stock()), "Payload of " + row.key());
            }
        }
        assertEquals(List.of(sellers, products), List.of(storedSellers, storedProducts), "Stored sellers and products");
    }

    /**
     * The key-to-ID digest of the seeder and the reader: one "key=sellerId" line for each seller and one
     * "key=sellerId/productId" line for each product, each with LF, in dataset order.
     */
    public static String digest(Map<String, Row> rows) {
        StringBuilder lines = new StringBuilder();
        for (Definition definition : DEFINITIONS) {
            Row row = Objects.requireNonNull(rows.get(definition.key()), definition.key());
            lines.append(definition.key()).append('=').append(row.sellerId());
            if (!definition.seller()) {
                lines.append('/').append(row.productId());
            }
            lines.append('\n');
        }
        return String.format("%064x", CatalogRouting.digest(lines.toString()));
    }
}
