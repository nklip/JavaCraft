package dev.nklip.javacraft.shardshop.order.reconciliation;

import dev.nklip.javacraft.shardshop.idgen.IdGenerator;
import dev.nklip.javacraft.shardshop.order.ordering.OrderModel.Order;
import dev.nklip.javacraft.shardshop.order.ordering.OrderService;
import dev.nklip.javacraft.shardshop.sharding.Shard;
import dev.nklip.javacraft.shardshop.sharding.ShardTopology;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReconciliationCoordinatorTest {
    private static final Instant CREATED = Instant.parse("2026-10-24T00:00:00Z");
    private final Shard shard = ShardTopology.fromNamesAndRegions("a", "US").shards().getFirst();
    private final MutableClock clock = new MutableClock(CREATED);
    private final FakeStore store = new FakeStore();
    private final IdGenerator generator = mock(IdGenerator.class);
    private final AtomicLong nextId = new AtomicLong(1000);

    private ReconciliationCoordinator coordinator() {
        doAnswer(call -> nextId.incrementAndGet()).when(generator).nextId();
        return new ReconciliationCoordinator(store, generator, clock);
    }

    @Test
    void exactWaitsAndRestartKeepTheFiveAttemptBudgetAfterTransportCleanup() {
        var state = store.add(10, 20);
        var coordinator = coordinator();
        long[] waits = {60, 120, 240, 300, 300};
        Instant eligible = CREATED.plusSeconds(60);
        for (int attempt = 1; attempt <= 5; attempt++) {
            clock.at(eligible.minusNanos(1));
            coordinator.scan(shard, 100);
            assertEquals(attempt - 1, state.attempts);
            clock.at(eligible);
            coordinator.scan(shard, 100);
            assertEquals(attempt, state.attempts);
            assertEquals(eligible.plusSeconds(attempt == 5 ? 0 : waits[attempt]), state.next);
            eligible = state.next;
            state.unpublished = false; // Published transport may subsequently be removed.
        }
        var restarted = coordinator();
        clock.at(eligible.plusSeconds(3600));
        restarted.scan(shard, 100);
        assertEquals(5, state.attempts);
        assertEquals(5, store.enqueues);
        assertEquals(10, nextId.get() - 1000);
        assertEquals(1, restarted.exhausted(shard));
    }

    @Test
    void unpublishedCommandConsumesNoIdsOrScheduleAndLaterSagaMakesProgress() {
        var first = store.add(10, 20);
        first.unpublished = true;
        var second = store.add(11, 21);
        var coordinator = coordinator();
        clock.at(CREATED.plusSeconds(60));
        coordinator.scan(shard, 1);
        assertEquals(0, first.attempts);
        assertEquals(CREATED.plusSeconds(60), first.next);
        verifyNoInteractions(generator);
        coordinator.scan(shard, 1);
        assertEquals(1, second.attempts);
        assertEquals(0, first.attempts);
        verify(generator, times(2)).nextId();
    }

    @Test
    void lockAndFreshStateAreCheckedBeforeGeneratingIds() {
        var state = store.add(10, 20);
        var coordinator = coordinator();
        clock.at(CREATED.plusSeconds(60));
        state.afterLock = () -> state.status = "CONFIRMED";
        coordinator.scan(shard, 100);
        assertEquals(OrderService.orderLock(20), store.lastLock);
        assertTrue(store.lockBeforeSaga);
        assertEquals(0, state.attempts);
        verifyNoInteractions(generator);
    }

    @Test
    void exhaustedBudgetObservedUnderTheLockPreventsASixthEnqueue() {
        var state = store.add(10, 20);
        var coordinator = coordinator();
        clock.at(CREATED.plusSeconds(60));
        state.afterLock = () -> state.attempts = 5;
        coordinator.scan(shard, 100);
        assertEquals(0, store.enqueues);
        verifyNoInteractions(generator);
    }

    @Test
    void unresolvedOrphanPreventsReplayAndIdAllocation() {
        var state = store.add(10, 20);
        state.orphaned = true;
        clock.at(CREATED.plusSeconds(60));
        coordinator().scan(shard, 100);
        assertEquals(0, state.attempts);
        assertEquals(0, store.enqueues);
        verifyNoInteractions(generator);
    }

    @Test
    void scheduleMovedWhileWaitingForTheLockIsRechecked() {
        var state = store.add(10, 20);
        var coordinator = coordinator();
        clock.at(CREATED.plusSeconds(60));
        state.afterLock = () -> state.next = CREATED.plusSeconds(61);
        coordinator.scan(shard, 100);
        assertEquals(0, state.attempts);
        verifyNoInteractions(generator);
        clock.at(CREATED.plusSeconds(61));
        coordinator.scan(shard, 100);
        assertEquals(1, state.attempts);
    }

    @Test
    void failedCandidateDoesNotBlockLaterOne() {
        store.add(10, 20);
        var second = store.add(11, 21);
        store.failSaga = 10;
        clock.at(CREATED.plusSeconds(60));
        var result = coordinator().scan(shard, 100);
        assertTrue(result.passComplete());
        assertTrue(result.passFailed());
        assertEquals(10, result.firstFailure().sagaId());
        assertEquals(1, second.attempts);
    }

    @Test
    void failedFullPageRetainsFailureAcrossEmptyWrapThenCleanPassRecovers() {
        store.add(10, 20);
        store.add(11, 21);
        store.failSaga = 10;
        clock.at(CREATED.plusSeconds(60));
        var coordinator = coordinator();
        var failedPage = coordinator.scan(shard, 2);
        assertFalse(failedPage.passComplete());
        assertTrue(failedPage.passFailed());
        assertEquals(10, failedPage.firstFailure().sagaId());
        var emptyWrap = coordinator.scan(shard, 2);
        assertTrue(emptyWrap.passComplete());
        assertTrue(emptyWrap.passFailed());
        assertNull(emptyWrap.firstFailure());
        store.failSaga = -1;
        var cleanPass = coordinator.scan(shard, 2);
        assertTrue(cleanPass.passComplete());
        assertFalse(cleanPass.passFailed());
        assertEquals(1, store.states.get(10L).attempts);
    }

    @Test
    void newDueHigherIdsDoNotExtendAnActivePassAndStarveAnOlderSaga() {
        var old = store.add(10, 20);
        old.unpublished = true;
        var coordinator = coordinator();
        clock.at(CREATED.plusSeconds(60));
        assertFalse(coordinator.scan(shard, 1).passComplete());

        clock.at(CREATED.plusSeconds(61));
        var newcomer = store.add(11, 21);
        newcomer.next = CREATED.plusSeconds(61);
        assertTrue(coordinator.scan(shard, 1).passComplete(),
                "the active pass must wrap instead of chasing a newly due higher ID");

        old.unpublished = false;
        coordinator.scan(shard, 1);
        assertEquals(1, old.attempts, "the next pass must revisit the oldest eligible saga");
        assertEquals(0, newcomer.attempts);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        MutableClock(Instant instant) { this.instant = instant; }
        void at(Instant next) { instant = next; }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }

    private static final class State {
        final long sagaId;
        final long orderId;
        int attempts;
        Instant next = CREATED.plusSeconds(60);
        String status = "PENDING_LEDGER";
        boolean unpublished;
        boolean orphaned;
        Runnable afterLock = () -> { };
        State(long sagaId, long orderId) { this.sagaId = sagaId; this.orderId = orderId; }
        ReconciliationStore.Saga snapshot() {
            return new ReconciliationStore.Saga(sagaId, 30,
                    new Order(1, orderId, "fingerprint", "USD", BigDecimal.ONE, status, null),
                    List.of(), 1, status, attempts, next);
        }
    }

    private static final class FakeStore implements ReconciliationStore {
        final Map<Long, State> states = new HashMap<>();
        long lastLock;
        boolean lockBeforeSaga;
        boolean locked;
        int enqueues;
        long failSaga = -1;

        State add(long sagaId, long orderId) {
            var state = new State(sagaId, orderId);
            states.put(sagaId, state);
            return state;
        }

        @Override public List<Candidate> pendingReconciliation(Shard shard, Instant now, long afterSagaId, int limit) {
            return states.values().stream().filter(s -> s.sagaId > afterSagaId && s.attempts < 5
                            && "PENDING_LEDGER".equals(s.status) && !now.isBefore(s.next))
                    .sorted(Comparator.comparingLong(s -> s.sagaId)).limit(limit)
                    .map(s -> new Candidate(s.sagaId, s.orderId)).toList();
        }

        @Override public long exhaustedReconciliations(Shard shard) {
            return states.values().stream().filter(s -> s.attempts >= 5 && "PENDING_LEDGER".equals(s.status)).count();
        }

        @Override public <T> T reconciliation(Shard shard, Function<Session, T> work) {
            locked = false;
            return work.apply(new Session() {
                @Override public void lock(long key) { lastLock = key; locked = true; }
                @Override public Optional<Saga> saga(long sagaId) {
                    lockBeforeSaga = locked;
                    if (sagaId == failSaga) throw new ReconciliationFailure(new IllegalStateException());
                    State state = states.get(sagaId);
                    state.afterLock.run();
                    return Optional.of(state.snapshot());
                }
                @Override public boolean orphaned(long orderId) {
                    return states.values().stream().anyMatch(s -> s.orderId == orderId && s.orphaned);
                }
                @Override public boolean unpublished(long sagaId) { return states.get(sagaId).unpublished; }
                @Override public void enqueue(Saga saga, long messageId, long resultMessageId,
                                              Instant occurredAt, Instant nextReconciliationAt) {
                    State state = states.get(saga.sagaId());
                    state.attempts++;
                    state.next = nextReconciliationAt;
                    enqueues++;
                }
            });
        }
    }
}
