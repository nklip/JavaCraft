package dev.nklip.javacraft.shardshop.workload.dataset;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CatalogDatasetTest {

    @Test
    void loadsFiftyNamedCompaniesForEachRegion() {
        CatalogDataset dataset = new CatalogDataset("catalog-v1");

        assertEquals("catalog-v1", CatalogDataset.VERSION);
        assertEquals(150, dataset.sellers().size());
        assertEquals(List.of("US", "EU", "ASIA"), List.copyOf(dataset.sellersByRegion().keySet()));
        for (var group : dataset.sellersByRegion().entrySet()) {
            assertEquals(50, group.getValue().size());
            assertEquals(50, group.getValue().stream()
                    .map(CatalogDataset.SellerDefinition::companyName).distinct().count());
            for (int index = 0; index < group.getValue().size(); index++) {
                var seller = group.getValue().get(index);
                assertEquals(group.getKey(), seller.region());
                assertEquals("catalog-v1." + group.getKey() + ".seller." + (index + 1), seller.key());
                assertFalse(seller.companyName().isBlank());
            }
        }
        assertEquals(dataset.sellers(), dataset.sellersByRegion().values().stream().flatMap(List::stream).toList());
    }

    @Test
    void groupsTwoCompleteSyntheticProductsUnderEveryCompany() {
        CatalogDataset dataset = new CatalogDataset("catalog-v1");

        assertEquals(300, dataset.products().size());
        assertEquals(150, dataset.productsBySeller().size());
        assertEquals(300, dataset.products().stream().map(CatalogDataset.ProductDefinition::key).distinct().count());
        assertEquals(dataset.sellers().stream().map(CatalogDataset.SellerDefinition::key).toList(),
                List.copyOf(dataset.productsBySeller().keySet()));
        for (var group : dataset.sellersByRegion().entrySet()) {
            int productNumber = 1;
            for (var seller : group.getValue()) {
                var products = dataset.productsBySeller().get(seller.key());
                assertEquals(List.of("USD", "EUR"), products.stream()
                        .map(CatalogDataset.ProductDefinition::currency).toList());
                for (var product : products) {
                    assertEquals("catalog-v1." + group.getKey() + ".product." + productNumber++, product.key());
                    assertEquals(seller.key(), product.sellerKey());
                    assertEquals(seller.companyName() + " Sample Product " + product.currency(), product.name());
                    assertEquals("Synthetic " + product.currency() + " catalog item sold by "
                            + seller.companyName() + ".", product.description());
                    assertEquals("19.95", product.price());
                    assertEquals("12.50", product.unitCost());
                    assertEquals(1000, product.initialStock());
                }
            }
        }
        assertEquals(dataset.products(), dataset.productsBySeller().values().stream().flatMap(List::stream).toList());
    }

    @Test
    void recreatesTheSameImmutableListsAndNestedMapsAfterRestart() {
        CatalogDataset first = new CatalogDataset("catalog-v1");
        CatalogDataset restarted = new CatalogDataset("catalog-v1");

        assertEquals(first.sellers(), restarted.sellers());
        assertEquals(first.products(), restarted.products());
        assertEquals(first.sellersByRegion(), restarted.sellersByRegion());
        assertEquals(first.productsBySeller(), restarted.productsBySeller());
        assertThrows(UnsupportedOperationException.class, () -> first.sellers().clear());
        assertThrows(UnsupportedOperationException.class, () -> first.products().clear());
        assertThrows(UnsupportedOperationException.class, () -> first.sellersByRegion().clear());
        assertThrows(UnsupportedOperationException.class, () -> first.sellersByRegion().get("US").clear());
        assertThrows(UnsupportedOperationException.class, () -> first.productsBySeller().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> first.productsBySeller().get(first.sellers().getFirst().key()).clear());
    }

    @Test
    void rejectsUnknownMissingAndNoncanonicalVersions() {
        for (String version : new String[] { "catalog-v2", "buyers-v1", "CATALOG-V1", " catalog-v1", "", null }) {
            assertEquals("Unsupported catalog dataset version",
                    assertThrows(IllegalArgumentException.class, () -> new CatalogDataset(version)).getMessage());
        }
    }
}
