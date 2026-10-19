package dev.nklip.javacraft.shardshop.workload.producer.plan;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.nklip.javacraft.shardshop.common.IdParser;
import dev.nklip.javacraft.shardshop.common.JsonFields;
import dev.nklip.javacraft.shardshop.common.Sha256;
import dev.nklip.javacraft.shardshop.workload.dataset.BuyerDataset;
import dev.nklip.javacraft.shardshop.workload.dataset.CatalogDataset;
import dev.nklip.javacraft.shardshop.workload.producer.CurrencySelector;
import dev.nklip.javacraft.shardshop.workload.producer.ProducerDatasets.CreatedBuyer;
import dev.nklip.javacraft.shardshop.workload.producer.ProducerDatasets.ProductDescriptor;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Rebuilds a run's order payload from fixed definitions and returned IDs, with no mutable state. */
public final class OrderPlanner {
    private static final List<String> REGIONS = List.of("US", "EU", "ASIA");
    private static final List<String> CURRENCIES = List.of("USD", "EUR");

    private final String runName;
    private final CurrencySelector currencies;
    private final List<CreatedBuyer> buyers;
    private final Map<String, List<ProductDescriptor>> products;

    /** The datasets must be the configured versions that resolved the buyer and product descriptors. */
    public OrderPlanner(String runName, int rejectedOrderPercent, List<CreatedBuyer> buyers,
                        List<ProductDescriptor> products, CatalogDataset catalog, BuyerDataset buyerDataset) {
        this.runName = validateRunName(runName);
        this.currencies = new CurrencySelector(rejectedOrderPercent);
        this.buyers = validateBuyers(buyers, Objects.requireNonNull(buyerDataset));
        this.products = validateProducts(products, Objects.requireNonNull(catalog));
    }

    public static String validateRunName(String runName) {
        if (runName == null) {
            throw new IllegalArgumentException("Invalid run name");
        }
        try {
            return JsonFields.nonblankText(JsonNodeFactory.instance.objectNode().put("runName", runName),
                    "runName", 200);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Invalid run name", failure);
        }
    }

    public CreatedBuyer buyer(long ordinal) {
        checkOrdinal(ordinal);
        return buyers.get(index(ordinal, "buyer", buyers.size()));
    }

    public PlannedOrder plan(long ordinal, String orderId) {
        checkOrdinal(ordinal);
        new IdParser().parse(orderId);
        String currency = currencies.select(orderId);
        List<PlannedOrder.Item> items = new ArrayList<>(REGIONS.size());
        for (String region : REGIONS) {
            List<ProductDescriptor> choices = products.get(region + ":" + currency);
            ProductDescriptor chosen = choices.get(index(ordinal, "product:" + region, choices.size()));
            int quantity = 1 + index(ordinal, "quantity:" + region, 3);
            items.add(new PlannedOrder.Item(chosen.sellerId(), chosen.productId(), quantity));
        }
        return new PlannedOrder(buyer(ordinal).buyerId(), orderId, currency, items);
    }

    private int index(long ordinal, String role, int size) {
        return Sha256.unsignedDigest(runName + "\0" + ordinal + "\0" + role)
                .mod(BigInteger.valueOf(size)).intValueExact();
    }

    private static void checkOrdinal(long ordinal) {
        if (ordinal < 0) {
            throw new IllegalArgumentException("Request ordinal must be nonnegative");
        }
    }

    private static List<CreatedBuyer> validateBuyers(List<CreatedBuyer> source, BuyerDataset dataset) {
        Objects.requireNonNull(source);
        if (source.isEmpty()) {
            throw new IllegalArgumentException("Buyer dataset is empty");
        }
        Set<BuyerDataset.BuyerDefinition> definitions = Set.copyOf(dataset.buyers());
        Set<String> keys = new HashSet<>();
        Set<String> ids = new HashSet<>();
        List<CreatedBuyer> sorted = new ArrayList<>(source);
        for (CreatedBuyer buyer : sorted) {
            Objects.requireNonNull(buyer);
            Objects.requireNonNull(buyer.definition());
            if (!definitions.contains(buyer.definition())) {
                throw new IllegalArgumentException("Buyer does not belong to selected dataset");
            }
            String key = Objects.requireNonNull(buyer.definition().key());
            new IdParser().parse(buyer.buyerId());
            if (!keys.add(key) || !ids.add(buyer.buyerId())) {
                throw new IllegalArgumentException("Ambiguous buyer descriptor");
            }
        }
        sorted.sort(Comparator.comparing(b -> b.definition().key()));
        return List.copyOf(sorted);
    }

    private static Map<String, List<ProductDescriptor>> validateProducts(List<ProductDescriptor> source,
                                                                         CatalogDataset catalog) {
        Objects.requireNonNull(source);
        Set<CatalogDataset.ProductDefinition> definitions = Set.copyOf(catalog.products());
        Map<String, String> sellerRegions = new HashMap<>();
        for (CatalogDataset.SellerDefinition seller : catalog.sellers()) {
            sellerRegions.put(seller.key(), seller.region());
        }
        Map<String, List<ProductDescriptor>> grouped = new HashMap<>();
        Map<String, String> sellerKeysById = new HashMap<>();
        Map<String, String> sellerIdsByKey = new HashMap<>();
        Set<String> keys = new HashSet<>();
        Set<String> ids = new HashSet<>();
        for (ProductDescriptor product : source) {
            Objects.requireNonNull(product);
            Objects.requireNonNull(product.definition());
            String key = Objects.requireNonNull(product.definition().key());
            String sellerKey = Objects.requireNonNull(product.definition().sellerKey());
            String currency = Objects.requireNonNull(product.definition().currency());
            if (!definitions.contains(product.definition())) {
                throw new IllegalArgumentException("Product does not belong to selected dataset");
            }
            new IdParser().parse(product.sellerId());
            new IdParser().parse(product.productId());
            if (!keys.add(key) || !ids.add(product.productId())) {
                throw new IllegalArgumentException("Ambiguous product descriptor");
            }
            String priorSellerKey = sellerKeysById.putIfAbsent(product.sellerId(), sellerKey);
            if (priorSellerKey != null && !priorSellerKey.equals(sellerKey)) {
                throw new IllegalArgumentException("Ambiguous seller descriptor");
            }
            String priorSellerId = sellerIdsByKey.putIfAbsent(sellerKey, product.sellerId());
            if (priorSellerId != null && !priorSellerId.equals(product.sellerId())) {
                throw new IllegalArgumentException("Ambiguous seller descriptor");
            }
            String region = sellerRegions.get(sellerKey);
            if (region == null || !REGIONS.contains(region)) {
                throw new IllegalArgumentException("Product seller has no supported region");
            }
            if (!CURRENCIES.contains(currency)) {
                throw new IllegalArgumentException("Unsupported dataset currency");
            }
            grouped.computeIfAbsent(region + ":" + currency, ignored -> new ArrayList<>()).add(product);
        }
        Map<String, List<ProductDescriptor>> result = new HashMap<>();
        for (String region : REGIONS) {
            for (String currency : CURRENCIES) {
                List<ProductDescriptor> group = grouped.get(region + ":" + currency);
                if (group == null || group.isEmpty()) {
                    throw new IllegalArgumentException("Missing regional currency product");
                }
                group.sort(Comparator.comparing(p -> p.definition().key()));
                result.put(region + ":" + currency, List.copyOf(group));
            }
        }
        return Map.copyOf(result);
    }
}
