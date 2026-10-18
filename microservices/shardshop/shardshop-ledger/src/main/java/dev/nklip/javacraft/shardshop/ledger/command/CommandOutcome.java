package dev.nklip.javacraft.shardshop.ledger.command;

/** The committed result of one delivery. The conflict names the quarantine reason, and it is null otherwise. */
public record CommandOutcome(CommandDisposition disposition, long messageId, long orderId, String conflict) {
}
