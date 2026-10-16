package dev.nklip.javacraft.shardshop.order.ordering;

import dev.nklip.javacraft.shardshop.sharding.Shard;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static dev.nklip.javacraft.shardshop.order.ordering.OrderModel.*;

/** Bounded primary database phases. A failed write callback must roll back every mutation. */
public interface OrderStore {
    <T> T read(Shard shard, Function<Session, T> work);

    <T> T write(Shard shard, Function<Session, T> work);

    /** Primary-only catalog lookup; all acquisition/query work must fit the remaining shared deadline. */
    Optional<Product> product(Shard sellerShard, long sellerId, long productId, Duration remaining);

    interface Session {
        void lock(long key);

        Optional<Buyer> buyerByKey(String region, String key);

        Optional<Buyer> buyer(long buyerId);

        void insertBuyer(long buyerId, String key, BuyerCreation creation);

        Optional<Allocation> allocation(long buyerId, String runName, long requestOrdinal);

        boolean allocatedTo(long orderId, long buyerId);

        void insertAllocation(Allocation allocation);

        boolean quarantined(long orderId);

        Optional<Order> order(long buyerId, long orderId);

        /** Inserts order, immutable item snapshots and initial PENDING_STOCK saga in this transaction. */
        void insertOrder(Order order, List<ItemSnapshot> items, long sagaId, long commandId);
    }
}
