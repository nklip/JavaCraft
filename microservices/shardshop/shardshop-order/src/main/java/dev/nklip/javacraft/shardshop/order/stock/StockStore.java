package dev.nklip.javacraft.shardshop.order.stock;

import dev.nklip.javacraft.shardshop.order.ordering.OrderModel;
import dev.nklip.javacraft.shardshop.sharding.Shard;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;

/** Short, independent transactions for durable stock coordination. */
public interface StockStore {
    record Saga(long sagaId, long commandId, OrderModel.Order order, List<OrderModel.ItemSnapshot> items,
                long leaseVersion, int failures, int currencyPolicyVersion) {
        public Saga(long sagaId, long commandId, OrderModel.Order order, List<OrderModel.ItemSnapshot> items,
                    long leaseVersion, int failures) {
            this(sagaId, commandId, order, items, leaseVersion, failures, 1);
        }

        public Saga {
            items = List.copyOf(items);
        }
    }

    Optional<Saga> claim(Shard shard, Duration lease);

    boolean renew(Shard shard, Saga saga, Duration lease);

    void reserve(Shard sellerShard, long orderId, List<OrderModel.ItemSnapshot> items);

    void release(Shard sellerShard, long orderId, List<OrderModel.ItemSnapshot> items);

    boolean cancel(Shard orderShard, Saga saga);

    boolean pendingLedger(Shard orderShard, Saga saga, LongSupplier ids);

    boolean released(Shard orderShard, Saga saga);

    void retry(Shard orderShard, Saga saga, Duration delay);
}
