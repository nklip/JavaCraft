package dev.nklip.javacraft.shardshop.order.reconciliation;

import dev.nklip.javacraft.shardshop.order.ordering.OrderModel.ItemSnapshot;
import dev.nklip.javacraft.shardshop.order.ordering.OrderModel.Order;
import dev.nklip.javacraft.shardshop.sharding.Shard;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** Replay commands, permanent result identities and the retry budget commit in one primary transaction. */
public interface ReconciliationStore {
    record Candidate(long sagaId, long orderId) { }

    record Saga(long sagaId, long commandId, Order order, List<ItemSnapshot> items,
                int currencyPolicyVersion, String status, int attempts, Instant nextReconciliationAt) {
        public Saga {
            items = List.copyOf(items);
        }
    }

    /** A bounded keyset page; the cursor advances even past sagas waiting for CDC publication. */
    List<Candidate> pendingReconciliation(Shard shard, Instant now, long afterSagaId, int limit);

    long exhaustedReconciliations(Shard shard);

    <T> T reconciliation(Shard shard, Function<Session, T> work);

    interface Session {
        void lock(long key);
        /** Locks the saga before loading its durable state and immutable order snapshot. */
        Optional<Saga> saga(long sagaId);
        boolean orphaned(long orderId);
        boolean unpublished(long sagaId);
        void enqueue(Saga saga, long messageId, long resultMessageId, Instant occurredAt,
                     Instant nextReconciliationAt);
    }
}
