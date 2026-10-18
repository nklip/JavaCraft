package dev.nklip.javacraft.shardshop.ledger.command;

/** A permanently invalid delivery. The reason is a fixed rule text that never includes untrusted payload data. */
public final class MalformedCommandException extends RuntimeException {
    private final String reason;

    public MalformedCommandException(String reason) {
        super("Malformed RecordOrder command: " + reason);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
