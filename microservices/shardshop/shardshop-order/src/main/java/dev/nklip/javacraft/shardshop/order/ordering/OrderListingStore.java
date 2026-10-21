package dev.nklip.javacraft.shardshop.order.ordering;

import dev.nklip.javacraft.shardshop.sharding.Shard;

import java.util.List;

import static dev.nklip.javacraft.shardshop.order.ordering.OrderModel.*;

/** All regional primary reads share one bounded phase; any shard failure fails the result. */
public interface OrderListingStore {
    List<ListedOrder> orders(List<Shard> shards, String region, Long productId, String productName,
                             SortDirection sort, int limit, OrderCursor after);
}
