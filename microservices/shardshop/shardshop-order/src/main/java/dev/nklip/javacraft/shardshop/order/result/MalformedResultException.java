package dev.nklip.javacraft.shardshop.order.result;

/** A permanently invalid ledger result. Reasons are fixed rule texts, never payload content. */
public final class MalformedResultException extends RuntimeException {
    private final String reason;

    public MalformedResultException(String reason) {
        super("Malformed ledger result: " + reason);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
