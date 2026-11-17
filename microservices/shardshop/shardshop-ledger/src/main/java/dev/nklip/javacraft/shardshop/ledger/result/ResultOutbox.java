package dev.nklip.javacraft.shardshop.ledger.result;

import java.util.List;

/** Short database operations; neither call keeps a transaction open across publication. */
public interface ResultOutbox {
    List<PendingResult> pending(int limit);

    /** Atomically marks matching observed attempts in a nonempty window of at most 16 results. */
    int markPublished(List<PendingResult> results);

    /** Marks only the observed attempt; false means a concurrent rearm superseded it. */
    boolean markPublished(long messageId, long publicationAttempt);
}
