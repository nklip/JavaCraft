package dev.nklip.javacraft.shardshop.order.profit;

import dev.nklip.javacraft.shardshop.sharding.Shard;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

/** Durable home-shard intents and atomic seller-shard credits. */
public interface ProfitStore {
    record PendingCredit(long orderId, int itemPosition, long sellerId, long productId,
                         String currency, BigDecimal amount, int failures) { }

    List<PendingCredit> pendingProfit(Shard home, int limit);

    void creditProfit(Shard sellerShard, PendingCredit credit);

    void markProfitApplied(Shard home, PendingCredit credit);

    void retryProfit(Shard home, PendingCredit credit, Duration delay);

    void blockProfit(Shard home, PendingCredit credit, ProfitFailure.Reason reason);
}
