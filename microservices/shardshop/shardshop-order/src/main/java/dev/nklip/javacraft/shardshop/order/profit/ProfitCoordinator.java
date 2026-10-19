package dev.nklip.javacraft.shardshop.order.profit;

import dev.nklip.javacraft.shardshop.sharding.Shard;
import dev.nklip.javacraft.shardshop.sharding.ShardRouter;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/** Applies each durable intent on its seller shard before marking home-shard completion. */
public final class ProfitCoordinator {
    /** The status of one intent. A deferred intent names the shard that failed; a blocked one keeps the failure. */
    public record Progress(Status status, Shard unavailable, ProfitFailure failure) {
        public enum Status { APPLIED, DEFERRED, BLOCKED }
    }

    private final ProfitStore store;
    private final ShardRouter router;

    public ProfitCoordinator(ProfitStore store, ShardRouter router) {
        this.store = store;
        this.router = router;
    }

    public Progress process(Shard home, ProfitStore.PendingCredit credit) {
        Shard seller = router.route(credit.sellerId());
        Shard current = seller;
        try {
            store.creditProfit(seller, credit);
            current = home;
            store.markProfitApplied(home, credit);
            return new Progress(Progress.Status.APPLIED, null, null);
        } catch (ProfitFailure failure) {
            if (failure.reason() != ProfitFailure.Reason.UNAVAILABLE) {
                store.blockProfit(home, credit, failure.reason());
                return new Progress(Progress.Status.BLOCKED, null, failure);
            }
            store.retryProfit(home, credit, retryDelay(credit.failures()));
            return new Progress(Progress.Status.DEFERRED, current, failure);
        }
    }

    static Duration retryDelay(int failures) {
        long base = Math.min(30_000L, 1_000L << Math.clamp(failures, 0, 5));
        long jitter = ThreadLocalRandom.current().nextLong(base / 4 + 1);
        return Duration.ofMillis(Math.min(30_000L, base + jitter));
    }
}
