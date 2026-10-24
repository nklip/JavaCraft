package dev.nklip.javacraft.shardshop.ledger.cleanup;

import java.time.Instant;

/** Removes only expired disposable transport rows in a bounded transaction. */
public interface TransportCleanupStore {
    Counts cleanup(Instant before, int batchSize);

    record Counts(int inbox, int outbox) {
    }
}
