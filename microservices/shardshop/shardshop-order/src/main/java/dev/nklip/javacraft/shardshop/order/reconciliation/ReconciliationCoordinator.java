package dev.nklip.javacraft.shardshop.order.reconciliation;

import dev.nklip.javacraft.shardshop.idgen.IdGenerator;
import dev.nklip.javacraft.shardshop.order.ordering.OrderService;
import dev.nklip.javacraft.shardshop.sharding.Shard;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;
import java.util.List;

/** Advances one bounded keyset page per shard, without letting one bad saga block later ones. */
public final class ReconciliationCoordinator {
    private static final int MAX_ATTEMPTS = 5;
    private static final Duration[] NEXT_WAIT = {
            Duration.ofSeconds(120), Duration.ofSeconds(240), Duration.ofSeconds(300),
            Duration.ofSeconds(300), Duration.ZERO
    };

    private final ReconciliationStore store;
    private final IdGenerator generator;
    private final Clock clock;
    private final Map<String, Long> cursors = new HashMap<>();
    private final Map<String, Instant> passCutoffs = new HashMap<>();
    private final Set<String> failedPasses = new HashSet<>();

    public record CandidateFailure(long sagaId, RuntimeException cause) { }

    /** Completion refers to one full keyset traversal, including all pages since the last wrap. */
    public record ScanResult(boolean passComplete, boolean passFailed, CandidateFailure firstFailure) { }

    public ReconciliationCoordinator(ReconciliationStore store, IdGenerator generator, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.generator = Objects.requireNonNull(generator);
        this.clock = Objects.requireNonNull(clock);
    }

    /** The persisted attempt count, not this in-memory cursor, is the replay budget. */
    public ScanResult scan(Shard shard, int batchSize) {
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("Reconciliation batch size must be between 1 and 1000");
        }
        String name = shard.clusterName();
        long after = cursors.getOrDefault(name, 0L);
        Instant cutoff = passCutoffs.computeIfAbsent(name, ignored -> clock.instant());
        List<ReconciliationStore.Candidate> candidates;
        try {
            candidates = store.pendingReconciliation(shard, cutoff, after, batchSize);
        } catch (RuntimeException failure) {
            failedPasses.add(name);
            throw failure;
        }
        CandidateFailure firstFailure = null;
        for (var candidate : candidates) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            // Move past failed or unpublished candidates, then wrap after the last short page.
            after = candidate.sagaId();
            cursors.put(name, after);
            try {
                reconcile(shard, candidate);
            } catch (RuntimeException failure) {
                // A later scan re-reads durable state, including a commit whose response was lost.
                failedPasses.add(name);
                if (firstFailure == null) {
                    firstFailure = new CandidateFailure(candidate.sagaId(), failure);
                }
            }
        }
        boolean complete = candidates.size() < batchSize && !Thread.currentThread().isInterrupted();
        boolean failed = failedPasses.contains(name);
        if (complete) {
            cursors.put(name, 0L);
            passCutoffs.remove(name);
            failedPasses.remove(name);
        }
        return new ScanResult(complete, failed, firstFailure);
    }

    public long exhausted(Shard shard) {
        return store.exhaustedReconciliations(shard);
    }

    private void reconcile(Shard shard, ReconciliationStore.Candidate candidate) {
        store.reconciliation(shard, session -> {
            session.lock(OrderService.orderLock(candidate.orderId()));
            var found = session.saga(candidate.sagaId());
            if (found.isEmpty()) {
                return null;
            }
            var saga = found.orElseThrow();
            Instant now = clock.instant(); // Re-check the schedule after both locks have been acquired.
            if (saga.sagaId() != candidate.sagaId() || saga.order().orderId() != candidate.orderId()
                    || !"PENDING_LEDGER".equals(saga.status())
                    || !"PENDING_LEDGER".equals(saga.order().status()) || saga.attempts() >= MAX_ATTEMPTS
                    || now.isBefore(saga.nextReconciliationAt()) || session.orphaned(candidate.orderId())
                    || session.unpublished(candidate.sagaId())) {
                return null;
            }
            long messageId = generator.nextId();
            long resultMessageId = generator.nextId();
            if (messageId <= 0 || resultMessageId <= 0 || messageId == resultMessageId) {
                throw new IllegalStateException("Generator returned non-distinct positive transport IDs");
            }
            session.enqueue(saga, messageId, resultMessageId, now, now.plus(NEXT_WAIT[saga.attempts()]));
            return null;
        });
    }
}
