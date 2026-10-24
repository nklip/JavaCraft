package dev.nklip.javacraft.shardshop.order.reconciliation;

/** Infrastructure failure leaves the persisted saga schedule and replay identities intact. */
public final class ReconciliationFailure extends RuntimeException {
    public ReconciliationFailure(Throwable cause) {
        super("Saga reconciliation unavailable", cause);
    }
}
