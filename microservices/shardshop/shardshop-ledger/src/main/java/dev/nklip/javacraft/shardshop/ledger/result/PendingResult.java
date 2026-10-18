package dev.nklip.javacraft.shardshop.ledger.result;

import java.util.Objects;

/** A committed result outbox row and the attempt that a publication may settle. */
public record PendingResult(long messageId, long publicationAttempt, String envelope) {
    public PendingResult {
        if (messageId <= 0 || publicationAttempt <= 0) {
            throw new IllegalArgumentException("Result identity and publication attempt must be positive");
        }
        Objects.requireNonNull(envelope, "envelope");
        if (envelope.isEmpty()) {
            throw new IllegalArgumentException("Result envelope is empty");
        }
    }
}
