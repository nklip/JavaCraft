package dev.nklip.javacraft.shardshop.workload.producer.load;

import dev.nklip.javacraft.shardshop.common.HttpResponseException;
import dev.nklip.javacraft.shardshop.common.JsonResponses;
import dev.nklip.javacraft.shardshop.workload.dataset.BuyerDataset;
import dev.nklip.javacraft.shardshop.workload.dataset.CatalogDataset;
import dev.nklip.javacraft.shardshop.workload.producer.ProducerDatasets.CreatedBuyer;
import dev.nklip.javacraft.shardshop.workload.producer.ProducerDatasets.ProductDescriptor;
import dev.nklip.javacraft.shardshop.workload.producer.http.OrderStatus;
import dev.nklip.javacraft.shardshop.workload.producer.plan.OrderPlanner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class WorkloadFixtures {
    static HttpResponseException httpFailure(int status, String code) {
        try {
            JsonResponses.decode(status, ("{\"code\":\"" + code + "\"}").getBytes(StandardCharsets.UTF_8),
                    200, node -> node);
            throw new AssertionError("Expected rejected response");
        } catch (HttpResponseException failure) {
            return failure;
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }

    static List<CreatedBuyer> buyers() {
        return List.of(new CreatedBuyer(new BuyerDataset(BuyerDataset.VERSION).buyers().getFirst(),
                "9007199254740993"));
    }

    static List<ProductDescriptor> products() {
        var catalog = new CatalogDataset(CatalogDataset.VERSION);
        var result = new ArrayList<ProductDescriptor>();
        int ordinal = 0;
        for (String region : List.of("US", "EU", "ASIA")) {
            var seller = catalog.sellersByRegion().get(region).getFirst();
            for (var product : catalog.productsBySeller().get(seller.key())) {
                result.add(new ProductDescriptor(product, Long.toString(100 + ordinal / 2),
                        Long.toString(1000 + ordinal++), 1000));
            }
        }
        return result;
    }

    static OrderPlanner planner(String runName, int rejectedOrderPercent) {
        return new OrderPlanner(runName, rejectedOrderPercent, buyers(), products(),
                new CatalogDataset(CatalogDataset.VERSION), new BuyerDataset(BuyerDataset.VERSION));
    }

    static OrderWorkload.Completion completion(String status) {
        return new OrderWorkload.Completion(status(status), false);
    }

    static OrderStatus status(String status) {
        return new OrderStatus("9007199254740993", "880803840000004254", status,
                "/api/v1/buyers/9007199254740993/orders/880803840000004254",
                status.equals("CANCELLED") ? "UNSUPPORTED_CURRENCY" : null);
    }
}
