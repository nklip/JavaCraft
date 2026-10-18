package dev.nklip.javacraft.shardshop.ledger.result;

import java.util.List;

/** Short database operations; neither call keeps a transaction open across publication. */
public interface ResultOutbox {
    List<PendingResult> pending(int limit);

    /** Marks only the observed attempt; false means a concurrent rearm superseded it. */
    boolean markPublished(long messageId, long publicationAttempt);
}
