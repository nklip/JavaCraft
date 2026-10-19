package dev.nklip.javacraft.shardshop.workload.producer.plan;

import dev.nklip.javacraft.shardshop.workload.dataset.BuyerDataset;
import dev.nklip.javacraft.shardshop.workload.dataset.CatalogDataset;
import dev.nklip.javacraft.shardshop.workload.producer.ProducerDatasets.CreatedBuyer;
import dev.nklip.javacraft.shardshop.workload.producer.ProducerDatasets.ProductDescriptor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderPlannerTest {
    private static final CatalogDataset CATALOG = new CatalogDataset(CatalogDataset.VERSION);
    private static final BuyerDataset BUYERS = new BuyerDataset(BuyerDataset.VERSION);
    private static final String BASE = "900719925474";

    @Test
    void reconstructsTheSameMixedRegionOrderFromUnorderedLookupResults() {
        List<CreatedBuyer> buyers = buyers();
        List<ProductDescriptor> products = products();
        OrderPlanner first = new OrderPlanner("stable-run", 0, buyers, products, CATALOG, BUYERS);
        Collections.reverse(buyers);
        Collections.reverse(products);
        OrderPlanner restarted = new OrderPlanner("stable-run", 0, buyers, products, CATALOG, BUYERS);

        PlannedOrder expected = new PlannedOrder(BASE + "1220", BASE + "0993", "USD", List.of(
                new PlannedOrder.Item(BASE + "1001", BASE + "1101", 1),
                new PlannedOrder.Item(BASE + "1010", BASE + "1110", 2),
                new PlannedOrder.Item(BASE + "1020", BASE + "1120", 2)));
        // Expected indices and quantities were calculated independently with Python hashlib SHA-256.
        assertEquals(expected, first.plan(0, BASE + "0993"));
        assertEquals(expected, restarted.plan(0, BASE + "0993"));
        assertEquals(BASE + "1220", first.buyer(0).buyerId());
        assertEquals(BASE + "1201", first.buyer(17).buyerId());
        assertEquals(List.of(3, 3, 2), first.plan(17, BASE + "0993").items().stream()
                .map(PlannedOrder.Item::quantity).toList());
    }

    @Test
    void currencyDependsOnlyOnReturnedOrderIdAndNeverChangesTheBuyer() {
        OrderPlanner planner = new OrderPlanner("stable-run", 100, buyers(), products(), CATALOG, BUYERS);
        PlannedOrder order = planner.plan(0, BASE + "0993");
        assertEquals("EUR", order.currency());
        assertEquals(planner.buyer(0).buyerId(), order.buyerId());
        assertEquals(List.of("US", "EU", "ASIA"), order.items().stream()
                .map(item -> region(item.productId())).toList());
    }

    @Test
    void rejectsInvalidCoordinatesAndAmbiguousReturnedIdsBeforeAnyPlanning() {
        assertThrows(IllegalArgumentException.class, () -> new OrderPlanner(" ", 0, buyers(), products(), CATALOG, BUYERS));
        assertThrows(IllegalArgumentException.class, () -> new OrderPlanner("x\u0000y", 0, buyers(), products(), CATALOG, BUYERS));
        assertThrows(IllegalArgumentException.class, () -> new OrderPlanner("x", 101, buyers(), products(), CATALOG, BUYERS));
        OrderPlanner planner = new OrderPlanner("stable-run", 0, buyers(), products(), CATALOG, BUYERS);
        assertThrows(IllegalArgumentException.class, () -> planner.buyer(-1));
        assertThrows(IllegalArgumentException.class, () -> planner.plan(0, "01"));
        List<CreatedBuyer> duplicateBuyers = buyers();
        duplicateBuyers.set(1, new CreatedBuyer(duplicateBuyers.get(1).definition(),
                duplicateBuyers.get(0).buyerId()));
        assertThrows(IllegalArgumentException.class,
                () -> new OrderPlanner("stable-run", 0, duplicateBuyers, products(), CATALOG, BUYERS));
        List<ProductDescriptor> duplicateProducts = products();
        ProductDescriptor first = duplicateProducts.get(0);
        ProductDescriptor second = duplicateProducts.get(1);
        duplicateProducts.set(1, new ProductDescriptor(second.definition(), second.sellerId(),
                first.productId(), second.stock()));
        assertThrows(IllegalArgumentException.class,
                () -> new OrderPlanner("stable-run", 0, buyers(), duplicateProducts, CATALOG, BUYERS));
        List<ProductDescriptor> ambiguousSeller = products();
        ProductDescriptor differentSeller = ambiguousSeller.get(1);
        ambiguousSeller.set(1, new ProductDescriptor(differentSeller.definition(), first.sellerId(),
                differentSeller.productId(), differentSeller.stock()));
        assertThrows(IllegalArgumentException.class,
                () -> new OrderPlanner("stable-run", 0, buyers(), ambiguousSeller, CATALOG, BUYERS));
    }

    private static List<CreatedBuyer> buyers() {
        BuyerDataset dataset = new BuyerDataset(BuyerDataset.VERSION);
        List<CreatedBuyer> result = new ArrayList<>();
        List<String> regions = List.of("US", "EU", "ASIA");
        for (int region = 0; region < 3; region++) {
            for (int index = 0; index < 2; index++) {
                result.add(new CreatedBuyer(dataset.buyersByRegion().get(regions.get(region)).get(index),
                        BASE + (1200 + region * 10 + index)));
            }
        }
        return result;
    }

    private static List<ProductDescriptor> products() {
        CatalogDataset dataset = new CatalogDataset(CatalogDataset.VERSION);
        List<ProductDescriptor> result = new ArrayList<>();
        List<String> regions = List.of("US", "EU", "ASIA");
        for (int region = 0; region < 3; region++) {
            String regionName = regions.get(region);
            List<CatalogDataset.ProductDefinition> definitions = dataset.products().stream()
                    .filter(p -> p.key().startsWith("catalog-v1." + regionName + ".product."))
                    .toList();
            for (int currency = 0; currency < 2; currency++) {
                for (int index = 0; index < 2; index++) {
                    CatalogDataset.ProductDefinition definition = definitions.get(index * 2 + currency);
                    result.add(new ProductDescriptor(definition, BASE + (1000 + region * 10 + index),
                            BASE + (1100 + region * 10 + currency * 2 + index), 0));
                }
            }
        }
        return result;
    }

    private static String region(String productId) {
        int suffix = Integer.parseInt(productId.substring(BASE.length()));
        return List.of("US", "EU", "ASIA").get((suffix - 1100) / 10);
    }

    /** A configured dataset version decides membership, never a fixed default version. */
    @Test
    void validatesDescriptorsAgainstTheConfiguredDatasets() {
        List<ProductDescriptor> products = products();
        CatalogDataset narrower = mock(CatalogDataset.class);
        when(narrower.sellers()).thenReturn(CATALOG.sellers());
        when(narrower.products()).thenReturn(CATALOG.products().stream()
                .filter(product -> !product.equals(products.getFirst().definition())).toList());
        assertThrows(IllegalArgumentException.class,
                () -> new OrderPlanner("stable-run", 0, buyers(), products, narrower, BUYERS));
        BuyerDataset otherBuyers = mock(BuyerDataset.class);
        when(otherBuyers.buyers()).thenReturn(BUYERS.buyers().subList(1, 2));
        assertThrows(IllegalArgumentException.class,
                () -> new OrderPlanner("stable-run", 0, buyers(), products, CATALOG, otherBuyers));
    }
}
