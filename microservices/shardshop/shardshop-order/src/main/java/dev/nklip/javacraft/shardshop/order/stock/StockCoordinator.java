package dev.nklip.javacraft.shardshop.order.stock;

import dev.nklip.javacraft.shardshop.idgen.IdGenerationUnavailableException;
import dev.nklip.javacraft.shardshop.idgen.IdGenerator;
import dev.nklip.javacraft.shardshop.order.ordering.OrderModel.ItemSnapshot;
import dev.nklip.javacraft.shardshop.sharding.Shard;
import dev.nklip.javacraft.shardshop.sharding.ShardRouter;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Drives one durable saga at a time without keeping an order transaction open across catalog IO. */
public final class StockCoordinator {
    public enum Progress { IDLE, ADVANCED, DEFERRED }

    private final StockStore store;
    private final ShardRouter router;
    private final IdGenerator generator;
    private final Duration lease;

    public StockCoordinator(StockStore store, ShardRouter router, IdGenerator generator, Duration lease) {
        if (lease == null || lease.compareTo(Duration.ofSeconds(10)) < 0
                || lease.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Stock lease must be between 10s and 5m");
        }
        this.store = store;
        this.router = router;
        this.generator = generator;
        this.lease = lease;
    }

    public Progress processNext(Shard home) {
        var claimed = store.claim(home, lease);
        if (claimed.isEmpty()) {
            return Progress.IDLE;
        }
        StockStore.Saga saga = claimed.orElseThrow();
        Map<Shard, List<ItemSnapshot>> groups = groups(saga.items());
        try {
            if ("CANCELLED".equals(saga.order().status())) {
                return release(home, saga, groups);
            }
            try {
                for (var group : groups.entrySet()) {
                    if (!store.renew(home, saga, lease)) {
                        return Progress.DEFERRED;
                    }
                    store.reserve(group.getKey(), saga.order().orderId(), group.getValue());
                }
            } catch (StockFailure failure) {
                if (failure.reason() != StockFailure.Reason.OUT_OF_STOCK) {
                    throw failure;
                }
                // Cancellation must commit before any compensation can reach another shard.
                if (!store.cancel(home, saga)) {
                    return Progress.DEFERRED;
                }
                return release(home, saga, groups);
            }
            return store.pendingLedger(home, saga, generator::nextId) ? Progress.ADVANCED : Progress.DEFERRED;
        } catch (StockFailure | IdGenerationUnavailableException failure) {
            // A lost commit response is retried just like an unavailable shard. Never infer a business rejection.
            store.retry(home, saga, retryDelay(saga.failures()));
            return Progress.DEFERRED;
        }
    }

    private Progress release(Shard home, StockStore.Saga saga, Map<Shard, List<ItemSnapshot>> groups) {
        for (var group : groups.entrySet()) {
            if (!store.renew(home, saga, lease)) {
                return Progress.DEFERRED;
            }
            // Also close shards with no reservation: a paused worker must never reserve after release.
            store.release(group.getKey(), saga.order().orderId(), group.getValue());
        }
        return store.released(home, saga) ? Progress.ADVANCED : Progress.DEFERRED;
    }

    private Map<Shard, List<ItemSnapshot>> groups(List<ItemSnapshot> items) {
        Map<Shard, List<ItemSnapshot>> groups = new TreeMap<>(Comparator.comparing(Shard::clusterName));
        for (ItemSnapshot item : items) {
            groups.computeIfAbsent(router.route(item.sellerId()), ignored -> new java.util.ArrayList<>()).add(item);
        }
        groups.replaceAll((shard, values) -> values.stream()
                .sorted(Comparator.comparingLong(ItemSnapshot::productId)).toList());
        return groups;
    }

    static Duration retryDelay(int failures) {
        return Duration.ofSeconds(Math.min(30, 1L << Math.clamp(failures, 0, 5)));
    }
}
