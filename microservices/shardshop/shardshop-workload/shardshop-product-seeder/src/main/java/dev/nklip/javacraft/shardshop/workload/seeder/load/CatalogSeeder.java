package dev.nklip.javacraft.shardshop.workload.seeder.load;

import dev.nklip.javacraft.shardshop.workload.control.WorkloadControl;
import dev.nklip.javacraft.shardshop.workload.dataset.CatalogDataset.ProductDefinition;
import dev.nklip.javacraft.shardshop.workload.dataset.CatalogDataset.SellerDefinition;
import dev.nklip.javacraft.shardshop.workload.seeder.catalog.SeederDatasets;
import dev.nklip.javacraft.shardshop.workload.seeder.catalog.SeederDatasets.CreatedProduct;
import dev.nklip.javacraft.shardshop.workload.seeder.catalog.SeederDatasets.CreatedSeller;

import java.math.BigDecimal;
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
    /** Upper bound of the target rate in outgoing HTTP requests per second. */
    public static final BigDecimal MAX_REQUEST_RATE = new BigDecimal("1000");
    /** Seller and product POSTs. */
    public static final String CREATION = "creation";
    /** Reads by ID and lookups by creation key. */
    public static final String VERIFICATION = "verification";
    private final SeederDatasets datasets;
    private final RetryPolicy retry;

    public CatalogSeeder(SeederDatasets datasets, RetryPolicy retry) {
        this.datasets = Objects.requireNonNull(datasets);
        this.retry = Objects.requireNonNull(retry);
    }

    /** The control settings of the seeder: outgoing HTTP requests per second, one request in flight. */
    public static WorkloadControl.Settings controlSettings(BigDecimal requestRate) {
        return new WorkloadControl.Settings("product-seeder", "HTTP requests/s", requestRate, MAX_REQUEST_RATE,
                BigDecimal.TEN, 1, List.of(CREATION, VERIFICATION));
    }

    /** One creation and two verification requests for each entity of the dataset, without retries. */
    public long plannedOperations() {
        return 3L * (datasets.sellers().size() + datasets.products().size());
    }

    public SeedReport seed() throws SeedingException, InterruptedException {
        return seed(() -> { });
    }

    /** As {@link #seed()}; {@code completed} runs after each successful operation, including its retries. */
    public SeedReport seed(Runnable completed) throws SeedingException, InterruptedException {
        List<CreatedSeller> sellers = new ArrayList<>();
        List<CreatedProduct> products = new ArrayList<>();
        for (SellerDefinition definition : datasets.sellers()) {
            CreatedSeller seller = retry.call(CREATION, "Create seller " + definition.key(),
                    () -> datasets.createSeller(definition));
            completed.run();
            sellers.add(seller);
            for (ProductDefinition product : datasets.productsOf(definition)) {
                products.add(retry.call(CREATION, "Create product " + product.key(),
                        () -> datasets.createProduct(product, seller)));
                completed.run();
            }
        }
        Map<String, CreatedSeller> sellersByKey = new HashMap<>();
        for (CreatedSeller seller : sellers) {
            String key = seller.definition().key();
            requireSameId("Read seller " + key, seller.sellerId(),
                    retry.call(VERIFICATION, "Read seller " + key, () -> datasets.readSeller(seller)).sellerId());
            completed.run();
            requireSameId("Look up seller " + key, seller.sellerId(), retry.call(VERIFICATION, "Look up seller " + key,
                    () -> datasets.lookupSeller(seller.definition())).sellerId());
            completed.run();
            sellersByKey.put(key, seller);
        }
        for (CreatedProduct product : products) {
            String key = product.definition().key();
            CreatedSeller seller = sellersByKey.get(product.definition().sellerKey());
            requireSameId("Read product " + key, product.productId(),
                    retry.call(VERIFICATION, "Read product " + key, () -> datasets.readProduct(product)).productId());
            completed.run();
            requireSameId("Look up product " + key, product.productId(), retry.call(VERIFICATION,
                    "Look up product " + key, () -> datasets.lookupProduct(product.definition(), seller)).productId());
            completed.run();
        }
        return new SeedReport(datasets.catalogVersion(), sellers, products);
    }

    private static void requireSameId(String operation, String acknowledged, String read) throws SeedingException {
        if (!acknowledged.equals(read)) {
            throw new SeedingException(operation + " returned a different ID than its acknowledged creation", null);
        }
    }
}
