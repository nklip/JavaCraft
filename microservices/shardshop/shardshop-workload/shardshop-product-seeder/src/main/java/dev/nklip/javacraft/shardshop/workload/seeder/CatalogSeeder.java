package dev.nklip.javacraft.shardshop.workload.seeder;

import dev.nklip.javacraft.shardshop.workload.dataset.CatalogDataset.ProductDefinition;
import dev.nklip.javacraft.shardshop.workload.dataset.CatalogDataset.SellerDefinition;
import dev.nklip.javacraft.shardshop.workload.seeder.SeederDatasets.CreatedProduct;
import dev.nklip.javacraft.shardshop.workload.seeder.SeederDatasets.CreatedSeller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Creates every seller and then its products. Then it reads each acknowledged entity by ID and by creation
 * key. The seeder has no local state: a new run repeats the same keys and payloads and recovers the IDs.
 */
public final class CatalogSeeder {
    private final SeederDatasets datasets;
    private final RetryPolicy retry;

    public CatalogSeeder(SeederDatasets datasets, RetryPolicy retry) {
        this.datasets = Objects.requireNonNull(datasets);
        this.retry = Objects.requireNonNull(retry);
    }

    public SeedReport seed() throws SeedingException, InterruptedException {
        List<CreatedSeller> sellers = new ArrayList<>();
        List<CreatedProduct> products = new ArrayList<>();
        for (SellerDefinition definition : datasets.sellers()) {
            CreatedSeller seller = retry.call("Create seller " + definition.key(),
                    () -> datasets.createSeller(definition));
            sellers.add(seller);
            for (ProductDefinition product : datasets.productsOf(definition)) {
                products.add(retry.call("Create product " + product.key(),
                        () -> datasets.createProduct(product, seller)));
            }
        }
        Map<String, CreatedSeller> sellersByKey = new HashMap<>();
        for (CreatedSeller seller : sellers) {
            String key = seller.definition().key();
            requireSameId("Read seller " + key, seller.sellerId(),
                    retry.call("Read seller " + key, () -> datasets.readSeller(seller)).sellerId());
            requireSameId("Look up seller " + key, seller.sellerId(),
                    retry.call("Look up seller " + key, () -> datasets.lookupSeller(seller.definition())).sellerId());
            sellersByKey.put(key, seller);
        }
        for (CreatedProduct product : products) {
            String key = product.definition().key();
            CreatedSeller seller = sellersByKey.get(product.definition().sellerKey());
            requireSameId("Read product " + key, product.productId(),
                    retry.call("Read product " + key, () -> datasets.readProduct(product)).productId());
            requireSameId("Look up product " + key, product.productId(), retry.call("Look up product " + key,
                    () -> datasets.lookupProduct(product.definition(), seller)).productId());
        }
        return new SeedReport(datasets.catalogVersion(), sellers, products);
    }

    private static void requireSameId(String operation, String acknowledged, String read) throws SeedingException {
        if (!acknowledged.equals(read)) {
            throw new SeedingException(operation + " returned a different ID than its acknowledged creation", null);
        }
    }
}
