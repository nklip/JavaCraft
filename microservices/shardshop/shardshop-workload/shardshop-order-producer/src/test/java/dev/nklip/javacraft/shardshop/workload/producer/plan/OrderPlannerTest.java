package dev.nklip.javacraft.shardshop.workload.producer.plan;

import dev.nklip.javacraft.shardshop.common.CurrencyPolicy;
import dev.nklip.javacraft.shardshop.workload.dataset.BuyerDataset;
import dev.nklip.javacraft.shardshop.workload.dataset.CatalogDataset;
import dev.nklip.javacraft.shardshop.workload.producer.ProducerDatasets.CreatedBuyer;
import dev.nklip.javacraft.shardshop.workload.producer.ProducerDatasets.ProductDescriptor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderPlannerTest {
    private static final CatalogDataset CATALOG = new CatalogDataset(CatalogDataset.VERSION);
    private static final BuyerDataset BUYERS = new BuyerDataset(BuyerDataset.VERSION);

    @Test
    void reconstructsTheSameOrderFromUnorderedLookupResultsAndKeepsADistinctVersionedAllocation() {
        List<CreatedBuyer> buyers = buyers();
        List<ProductDescriptor> products = products();
        OrderPlanner first = planner("stable-run", -1, buyers, products);
        Collections.reverse(buyers);
        Collections.reverse(products);
        OrderPlanner restarted = planner("stable-run", -1, buyers, products);
        for (long ordinal : new long[]{0, 1, 2, 3, 2697, 2698, 2699, 2700, 2701, 5399, 5400,
                Long.MAX_VALUE - 2, Long.MAX_VALUE - 1, Long.MAX_VALUE}) {
            assertEquals(first.plan(ordinal, "9007199254740993"), restarted.plan(ordinal, "9007199254740993"));
        }
        assertEquals("stable-run:catalog-v2:regional-v1", first.allocationRunName());
        assertEquals(first.allocationRunName(), restarted.allocationRunName());
        assertNotEquals(first.allocationRunName(), planner("stable-run", 10, buyers, products).allocationRunName());
        String longRun = "x".repeat(200);
        assertEquals(planner(longRun, -1, buyers, products).allocationRunName(),
                planner(longRun, -1, buyers, products).allocationRunName());
        assertTrue(planner(longRun, -1, buyers, products).allocationRunName().length() <= 200);
    }

    @Test
    void everyBuyerRegionCanBuyEveryCurrencyWithDistinctItemsOnlyFromThatCurrency() {
        OrderPlanner planner = planner("all-currencies", -1, buyers(), products());
        Map<String, ProductDescriptor> products = new HashMap<>();
        products().forEach(product -> products.put(product.productId(), product));
        Map<String, String> regions = new HashMap<>();
        buyers().forEach(buyer -> regions.put(buyer.buyerId(), buyer.definition().region()));
        Set<String> combinations = new HashSet<>();
        boolean crossesRegions = false;
        // A complete regional cycle reaches every currency for every buyer region.
        for (int ordinal = 0; ordinal < 2700; ordinal++) {
            PlannedOrder order = planner.plan(ordinal, Long.toString(ordinal + 1));
            assertEquals(planner.buyer(ordinal).buyerId(), order.buyerId());
            combinations.add(regions.get(order.buyerId()) + ":" + order.currency());
            assertEquals(order.currency().equals("VND") ? 1 : 3, order.items().size());
            assertEquals(order.items().size(), order.items().stream().map(PlannedOrder.Item::productId).distinct().count());
            for (var item : order.items()) {
                var descriptor = products.get(item.productId());
                assertEquals(order.currency(), descriptor.definition().currency());
                assertEquals(descriptor.sellerId(), item.sellerId());
                assertTrue(item.quantity() >= 1 && item.quantity() <= 3);
                String sellerRegion = descriptor.definition().key().split("\\.")[1];
                crossesRegions |= !regions.get(order.buyerId()).equals(sellerRegion);
            }
        }
        assertEquals(3 * CurrencyPolicy.SUPPORTED_CURRENCIES.size(), combinations.size());
        assertTrue(crossesRegions);
    }

    @Test
    void normalTrafficHasTheSpecifiedRegionalCurrencyMixInEachCompleteCycle() {
        var planner = planner("regional-mix", -1, buyers(), products());
        Map<String, Map<String, Integer>> expected = Map.of(
                "US", Map.of("USD", 810, "EUR", 36, "GBP", 9, "CNY", 30, "KRW", 5, "JPY", 5, "VND", 5),
                "EU", Map.of("USD", 45, "EUR", 720, "GBP", 90, "CNY", 30, "KRW", 5, "JPY", 5, "VND", 5),
                "ASIA", Map.of("USD", 45, "EUR", 36, "GBP", 9, "CNY", 540, "KRW", 90, "JPY", 90, "VND", 90));
        for (int start = 0; start < 8100; start += 2700) {
            Map<String, Map<String, Integer>> counts = new HashMap<>();
            for (int ordinal = start; ordinal < start + 2700; ordinal++) {
                String region = planner.buyer(ordinal).definition().region();
                assertEquals(List.of("US", "EU", "ASIA").get(ordinal % 3), region);
                PlannedOrder order = planner.plan(ordinal, Long.toString(ordinal + 1));
                counts.computeIfAbsent(region, ignored -> new HashMap<>()).merge(order.currency(), 1, Integer::sum);
            }
            assertEquals(expected, counts, "Regional mix for cycle starting at " + start);
        }
    }

    @Test
    void shortPrefixesAlreadyFavorEachBuyersHomeRegion() {
        var planner = planner("home-bias", -1, buyers(), products());
        Map<String, Set<String>> homeCurrencies = Map.of(
                "US", Set.of("USD"), "EU", Set.of("EUR", "GBP"),
                "ASIA", Set.of("CNY", "KRW", "JPY", "VND"));
        Map<String, Integer> homeOrders = new HashMap<>();
        for (int ordinal = 0; ordinal < 90; ordinal++) {
            String region = planner.buyer(ordinal).definition().region();
            if (homeCurrencies.get(region).contains(planner.plan(ordinal, Long.toString(ordinal + 1)).currency())) {
                homeOrders.merge(region, 1, Integer::sum);
            }
        }
        for (String region : homeCurrencies.keySet()) {
            assertTrue(homeOrders.getOrDefault(region, 0) >= 24, "At least 80% home orders in first 30 for " + region);
        }
    }

    @Test
    void productsRotateFairlyWithinCurrencyAcrossBuyerRegionsAndCycleBoundaries() {
        List<ProductDescriptor> products = products();
        Map<String, Set<String>> expectedProducts = new HashMap<>();
        products.forEach(product -> expectedProducts.computeIfAbsent(product.definition().currency(),
                ignored -> new HashSet<>()).add(product.productId()));
        var planner = planner("rotation", -1, buyers(), products);
        Map<String, List<String>> firstItems = new HashMap<>();
        Map<String, Integer> allItems = new HashMap<>();
        for (int ordinal = 0; ordinal < 5400; ordinal++) {
            PlannedOrder order = planner.plan(ordinal, Long.toString(ordinal + 1));
            firstItems.computeIfAbsent(order.currency(), ignored -> new ArrayList<>())
                    .add(order.items().getFirst().productId());
            order.items().forEach(item -> allItems.merge(item.productId(), 1, Integer::sum));
        }
        for (var entry : expectedProducts.entrySet()) {
            String currency = entry.getKey();
            Set<String> group = entry.getValue();
            List<String> selected = firstItems.get(currency);
            assertTrue(selected.size() >= 2 * group.size(), "At least two full rotations for " + currency);
            List<String> firstRotation = selected.subList(0, group.size());
            assertEquals(group, Set.copyOf(firstRotation), "Full catalog coverage for " + currency);
            for (int index = group.size(); index < selected.size(); index++) {
                assertEquals(firstRotation.get(index % group.size()), selected.get(index),
                        "Uninterrupted rotation for " + currency + " at selection " + index);
            }
            int minimum = group.stream().mapToInt(id -> allItems.getOrDefault(id, 0)).min().orElseThrow();
            int maximum = group.stream().mapToInt(id -> allItems.getOrDefault(id, 0)).max().orElseThrow();
            assertTrue(maximum - minimum <= 3, "At most one partial rotation of three items for " + currency);
        }
        var otherRun = planner("other-rotation", -1, buyers(), products);
        List<String> original = new ArrayList<>();
        List<String> other = new ArrayList<>();
        for (int ordinal = 0; ordinal < 90; ordinal++) {
            original.add(planner.plan(ordinal, Long.toString(ordinal + 1)).items().getFirst().productId());
            other.add(otherRun.plan(ordinal, Long.toString(ordinal + 1)).items().getFirst().productId());
        }
        assertNotEquals(original, other, "Run names change the product permutation");
    }

    @Test
    void regionalInterleavingSupportsSingleAndSubsetBuyerFixtures() {
        List<CreatedBuyer> all = buyers();
        for (List<CreatedBuyer> available : List.of(List.of(all.get(1)), List.of(all.get(2), all.get(0)))) {
            var planner = planner("subset", -1, available, products());
            List<String> expectedRegions = available.size() == 1 ? List.of("EU") : List.of("US", "ASIA");
            for (long ordinal : new long[]{0, 1, 2, 3, 1799, 1800, Long.MAX_VALUE}) {
                assertEquals(expectedRegions.get((int) (ordinal % expectedRegions.size())),
                        planner.buyer(ordinal).definition().region());
                assertEquals(planner.buyer(ordinal).buyerId(), planner.plan(ordinal, "1").buyerId());
            }
        }
    }

    @Test
    void explicitRejectionDrillsSelectOnlyUsdOrEurWithoutChangingTheBuyer() {
        var usd = planner("drill", 0, buyers(), products());
        var eur = planner("drill", 100, buyers(), products());
        assertEquals("USD", usd.plan(0, "1").currency());
        assertEquals("EUR", eur.plan(0, "1").currency());
        assertEquals(usd.buyer(0), eur.buyer(0));
        var tenPercent = planner("drill", 10, buyers(), products());
        assertEquals("EUR", tenPercent.plan(0, "880803840000004432").currency());
        assertEquals("USD", tenPercent.plan(0, "880803840000004254").currency());
        // Independent SHA-256 fixtures for sorted buyer keys (ASIA, EU, US).
        long[] ordinals = {0, 1, 2, 3, 99, 2699, 2700, Long.MAX_VALUE};
        String[] buyerIds = {"1000", "1001", "1001", "1000", "1000", "1002", "1001", "1001"};
        for (int index = 0; index < ordinals.length; index++) {
            assertEquals(buyerIds[index], usd.buyer(ordinals[index]).buyerId());
            assertEquals(usd.buyer(ordinals[index]), tenPercent.buyer(ordinals[index]));
            assertEquals(usd.buyer(ordinals[index]), eur.buyer(ordinals[index]));
        }
    }

    @Test
    void rejectsInvalidCoordinatesAndAmbiguousReturnedIdsBeforeAnyPlanning() {
        for (String run : new String[]{" ", "x\u0000y"}) {
            assertThrows(IllegalArgumentException.class, () -> planner(run, -1, buyers(), products()));
        }
        assertThrows(IllegalArgumentException.class, () -> planner("run", 101, buyers(), products()));
        var planner = planner("stable", -1, buyers(), products());
        assertThrows(IllegalArgumentException.class, () -> planner.buyer(-1));
        assertThrows(IllegalArgumentException.class, () -> planner.plan(0, "01"));
        List<CreatedBuyer> buyers = buyers();
        buyers.set(1, new CreatedBuyer(buyers.get(1).definition(), buyers.getFirst().buyerId()));
        assertThrows(IllegalArgumentException.class, () -> planner("run", -1, buyers, products()));
        List<ProductDescriptor> products = products();
        var second = products.get(1);
        products.set(1, new ProductDescriptor(second.definition(), second.sellerId(), products.getFirst().productId(), 0));
        assertThrows(IllegalArgumentException.class, () -> planner("run", -1, buyers(), products));
        var ambiguousSeller = products();
        var first = ambiguousSeller.getFirst();
        var otherSeller = ambiguousSeller.get(1);
        ambiguousSeller.set(1, new ProductDescriptor(otherSeller.definition(), first.sellerId(),
                otherSeller.productId(), 0));
        assertThrows(IllegalArgumentException.class, () -> planner("run", -1, buyers(), ambiguousSeller));
    }

    @Test
    void validatesMembershipAgainstTheConfiguredDatasetAndRequiresEveryCurrency() {
        List<ProductDescriptor> products = products();
        CatalogDataset narrower = mock(CatalogDataset.class);
        when(narrower.sellers()).thenReturn(CATALOG.sellers());
        when(narrower.products()).thenReturn(CATALOG.products().subList(1, CATALOG.products().size()));
        assertThrows(IllegalArgumentException.class,
                () -> new OrderPlanner("run", -1, buyers(), products, narrower, BUYERS));
        assertThrows(IllegalArgumentException.class, () -> planner("run", -1, buyers(),
                products.stream().filter(p -> !p.definition().currency().equals("VND")).toList()));
        BuyerDataset other = mock(BuyerDataset.class);
        when(other.buyers()).thenReturn(BUYERS.buyers().subList(1, 2));
        assertThrows(IllegalArgumentException.class,
                () -> new OrderPlanner("run", -1, buyers(), products, CATALOG, other));
    }

    private static OrderPlanner planner(String run, int percent, List<CreatedBuyer> buyers, List<ProductDescriptor> products) {
        return new OrderPlanner(run, percent, buyers, products, CATALOG, BUYERS);
    }

    private static List<CreatedBuyer> buyers() {
        var result = new ArrayList<CreatedBuyer>();
        int id = 1000;
        for (String region : List.of("US", "EU", "ASIA")) {
            result.add(new CreatedBuyer(BUYERS.buyersByRegion().get(region).getFirst(), Integer.toString(id++)));
        }
        return result;
    }

    private static List<ProductDescriptor> products() {
        var result = new ArrayList<ProductDescriptor>();
        int sellerId = 2000;
        int productId = 3000;
        for (var seller : CATALOG.sellers()) {
            for (var product : CATALOG.productsBySeller().get(seller.key())) {
                result.add(new ProductDescriptor(product, Integer.toString(sellerId), Integer.toString(productId++), 0));
            }
            sellerId++;
        }
        return result;
    }
}
