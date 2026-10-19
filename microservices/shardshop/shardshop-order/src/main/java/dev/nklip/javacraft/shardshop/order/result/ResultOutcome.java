package dev.nklip.javacraft.shardshop.order.result;

/** The committed disposition of a result delivery. Reason names a quarantine rule, if applicable. */
public record ResultOutcome(long messageId, long orderId, Disposition disposition, String reason) {
    public enum Disposition {
        APPLIED, DUPLICATE, ORPHANED, CONFLICT
    }
}
