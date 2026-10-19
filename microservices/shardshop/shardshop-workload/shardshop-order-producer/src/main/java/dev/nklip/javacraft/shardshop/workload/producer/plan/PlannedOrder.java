package dev.nklip.javacraft.shardshop.workload.producer.plan;

import java.util.List;
import java.util.Objects;

/** The complete retry-stable order payload, retaining IDs issued by the services. */
public record PlannedOrder(String buyerId, String orderId, String currency, List<Item> items) {
    public PlannedOrder {
        Objects.requireNonNull(buyerId);
        Objects.requireNonNull(orderId);
        Objects.requireNonNull(currency);
        items = List.copyOf(items);
    }

    public record Item(String sellerId, String productId, int quantity) { }
}
