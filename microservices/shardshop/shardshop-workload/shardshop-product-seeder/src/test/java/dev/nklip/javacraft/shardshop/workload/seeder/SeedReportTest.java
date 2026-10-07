package dev.nklip.javacraft.shardshop.workload.seeder;

import dev.nklip.javacraft.shardshop.workload.dataset.CatalogDataset;
import dev.nklip.javacraft.shardshop.workload.dataset.CatalogDataset.ProductDefinition;
import dev.nklip.javacraft.shardshop.workload.dataset.CatalogDataset.SellerDefinition;
import dev.nklip.javacraft.shardshop.workload.seeder.SeederDatasets.CreatedProduct;
import dev.nklip.javacraft.shardshop.workload.seeder.SeederDatasets.CreatedSeller;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SeedReportTest {
    private static final Map<String, String> PROFITS = Map.of("USD", "0.00", "EUR", "0.00");
    // Independently computed with: printf '<the five mapping lines>' | shasum -a 256
    private static final String MAPPING_DIGEST = "e6bbbdd5ccdcd1eac0a4ed30d22333deed5721c49b1bfd7714cae9baca56af7b";
    private static final String EMPTY_DIGEST = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private final CatalogDataset catalog = new CatalogDataset(CatalogDataset.VERSION);
    private final SellerDefinition us = catalog.sellersByRegion().get("US").getFirst();
    private final SellerDefinition eu = catalog.sellersByRegion().get("EU").getFirst();

    @Test
    void summarizesRegionsAndCurrenciesWithTheKeyToIdDigest() {
        List<ProductDefinition> usProducts = catalog.productsBySeller().get(us.key());
        ProductDefinition euUsd = catalog.productsBySeller().get(eu.key()).getFirst();
        SeedReport report = new SeedReport(CatalogDataset.VERSION,
                List.of(new CreatedSeller(us, "9007199254740993", PROFITS),
                        new CreatedSeller(eu, "9223372036854775807", Map.of("USD", "-1.25", "EUR", "0.00"))),
                List.of(new CreatedProduct(usProducts.getFirst(), "9007199254740993", "11", 1000),
                        new CreatedProduct(usProducts.getLast(), "9007199254740993", "12", 999),
                        new CreatedProduct(euUsd, "9223372036854775807", "13", 0)));
        assertEquals("catalog-v1.US.product.1", usProducts.getFirst().key());
        assertEquals("catalog-v1.EU.product.1", euUsd.key());
        assertEquals(MAPPING_DIGEST, report.mappingDigest());
        assertEquals("Seeded and verified catalog-v1 [US 1 sellers, 2 products {USD=1, EUR=1}; "
                + "EU 1 sellers, 1 products {USD=1}], key-to-ID SHA-256 " + MAPPING_DIGEST, report.summary());
    }

    @Test
    void retainsAnImmutableSnapshotOfItsLists() {
        List<CreatedSeller> sellers = new ArrayList<>();
        SeedReport report = new SeedReport(CatalogDataset.VERSION, sellers, List.of());
        sellers.add(new CreatedSeller(us, "1", PROFITS));
        assertEquals(List.of(), report.sellers());
        assertThrows(UnsupportedOperationException.class, () -> report.products().clear());
        assertEquals(EMPTY_DIGEST, report.mappingDigest());
        assertEquals("Seeded and verified catalog-v1 [], key-to-ID SHA-256 " + EMPTY_DIGEST, report.summary());
    }
}
