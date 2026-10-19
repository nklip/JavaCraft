package dev.nklip.javacraft.shardshop.order.result;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nklip.javacraft.shardshop.order.ordering.OrderModel.ItemSnapshot;
import dev.nklip.javacraft.shardshop.sharding.Shard;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** A result and its durable follow-up intents commit together on the buyer's primary. */
public interface ResultStore {
    record State(long orderId, long buyerId, String fingerprint, String currency, BigDecimal amount,
                 String orderStatus, long sagaId, long commandId, String sagaStatus,
                 String cancellationReason, ObjectNode ledgerResult) {
    }

    record Binding(long sagaId, ObjectNode receivedEnvelope) {
    }

    record Credit(ItemSnapshot item, BigDecimal amount) {
    }

    <T> T result(Shard shard, Function<Session, T> work);

    interface Session {
        /** Takes a transaction advisory lock; results use the order lock of order creation. */
        void lock(long key);
        Optional<State> state(long orderId);
        boolean orphaned(long orderId);
        Optional<Binding> binding(long messageId);
        List<ItemSnapshot> items(long orderId);
        void quarantine(LedgerResult result, String reason, ObjectNode expected, boolean orphan);
        void apply(State state, LedgerResult result, List<Credit> credits);
        void receive(LedgerResult result);
    }
}
