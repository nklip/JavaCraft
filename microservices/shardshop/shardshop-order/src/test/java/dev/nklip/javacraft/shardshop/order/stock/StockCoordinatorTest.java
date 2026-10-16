package dev.nklip.javacraft.shardshop.order.stock;

import dev.nklip.javacraft.shardshop.idgen.IdGenerationUnavailableException;
import dev.nklip.javacraft.shardshop.idgen.IdGenerator;
import dev.nklip.javacraft.shardshop.order.ordering.OrderModel.ItemSnapshot;
import dev.nklip.javacraft.shardshop.order.ordering.OrderModel.Order;
import dev.nklip.javacraft.shardshop.sharding.Shard;
import dev.nklip.javacraft.shardshop.sharding.ShardRouter;
import dev.nklip.javacraft.shardshop.sharding.ShardTopology;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;

import static dev.nklip.javacraft.shardshop.order.stock.StockCoordinator.Progress.*;
import static dev.nklip.javacraft.shardshop.order.stock.StockFailure.Reason.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StockCoordinatorTest {
    private static final Duration LEASE = Duration.ofSeconds(30);
    private final StockStore store = mock(StockStore.class);
    private final IdGenerator generator = mock(IdGenerator.class);
    private final ShardTopology topology = ShardTopology.fromNamesAndRegions("a,b,c", "US,EU,ASIA");
    private final ShardRouter router = mock(ShardRouter.class);
    private final Shard first = topology.shards().getFirst();
    private final Shard second = topology.shards().get(1);
    private final Shard home = topology.shards().get(2);
    private final ItemSnapshot large = item(0, 10, 300);
    private final ItemSnapshot remote = item(1, 20, 200);
    private final ItemSnapshot small = item(2, 10, 100);
    private final StockCoordinator coordinator = new StockCoordinator(store, router, generator, LEASE);

    @BeforeEach
    void routing() {
        when(router.route(10)).thenReturn(first);
        when(router.route(20)).thenReturn(second);
        when(store.renew(eq(home), any(), eq(LEASE))).thenReturn(true);
    }

    @Test
    void reservesAllSellerShardsInProductOrderBeforeAtomicLedgerTransition() {
        var saga = claim("PENDING_STOCK", 0);
        when(store.pendingLedger(eq(home), eq(saga), any())).thenAnswer(call -> {
            assertEquals(9007199254740993L, call.getArgument(2, LongSupplier.class).getAsLong());
            return true;
        });
        when(generator.nextId()).thenReturn(9007199254740993L);

        assertEquals(ADVANCED, coordinator.processNext(home));

        var sequence = inOrder(store);
        sequence.verify(store).claim(home, LEASE);
        sequence.verify(store).renew(home, saga, LEASE);
        sequence.verify(store).reserve(first, 42, List.of(small, large));
        sequence.verify(store).renew(home, saga, LEASE);
        sequence.verify(store).reserve(second, 42, List.of(remote));
        sequence.verify(store).pendingLedger(eq(home), eq(saga), any());
        verify(store, never()).release(any(), anyLong(), any());
        verify(store, never()).cancel(any(), any());
    }

    @Test
    void outOfStockCommitsCancellationBeforeClosingEverySellerShard() {
        var saga = claim("PENDING_STOCK", 0);
        doThrow(new StockFailure(OUT_OF_STOCK)).when(store).reserve(second, 42, List.of(remote));
        when(store.cancel(home, saga)).thenReturn(true);
        when(store.released(home, saga)).thenReturn(true);

        assertEquals(ADVANCED, coordinator.processNext(home));

        var sequence = inOrder(store);
        sequence.verify(store).cancel(home, saga);
        sequence.verify(store).release(first, 42, List.of(small, large));
        sequence.verify(store).release(second, 42, List.of(remote));
        sequence.verify(store).released(home, saga);
        verify(store, never()).pendingLedger(any(), any(), any());
        verifyNoInteractions(generator);
    }

    @Test
    void unavailableOrUnknownReserveOutcomeLeavesSagaPendingAndSchedulesRetry() {
        var saga = claim("PENDING_STOCK", 3);
        doThrow(new StockFailure(UNAVAILABLE)).when(store).reserve(second, 42, List.of(remote));

        assertEquals(DEFERRED, coordinator.processNext(home));

        verify(store).retry(home, saga, Duration.ofSeconds(8));
        verify(store, never()).cancel(any(), any());
        verify(store, never()).release(any(), anyLong(), any());
        verify(store, never()).pendingLedger(any(), any(), any());
    }

    @Test
    void cancelledSagaResumesUnfinishedReleaseAndMarksCompletionLast() {
        var saga = claim("CANCELLED", 4);
        doThrow(new StockFailure(UNAVAILABLE)).doNothing().when(store).release(second, 42, List.of(remote));
        assertEquals(DEFERRED, coordinator.processNext(home));
        verify(store).retry(home, saga, Duration.ofSeconds(16));
        verify(store, never()).released(any(), any());

        when(store.released(home, saga)).thenReturn(true);
        assertEquals(ADVANCED, coordinator.processNext(home));
        verify(store, times(2)).release(first, 42, List.of(small, large));
        verify(store).released(home, saga);
        verify(store, never()).reserve(any(), anyLong(), any());
        verify(store, never()).pendingLedger(any(), any(), any());
    }

    @Test
    void lostLeaseStopsBeforeTheNextCatalogWrite() {
        var saga = claim("PENDING_STOCK", 0);
        when(store.renew(home, saga, LEASE)).thenReturn(true, false);
        assertEquals(DEFERRED, coordinator.processNext(home));
        verify(store).reserve(first, 42, List.of(small, large));
        verify(store, never()).reserve(eq(second), anyLong(), any());
        verify(store, never()).pendingLedger(any(), any(), any());
        verify(store, never()).retry(any(), any(), any());
    }

    @Test
    void staleWorkerCannotReleaseWithoutCommittingCancellation() {
        var saga = claim("PENDING_STOCK", 0);
        doThrow(new StockFailure(OUT_OF_STOCK)).when(store).reserve(first, 42, List.of(small, large));
        when(store.cancel(home, saga)).thenReturn(false);
        assertEquals(DEFERRED, coordinator.processNext(home));
        verify(store, never()).release(any(), anyLong(), any());
        verify(store, never()).released(any(), any());
    }

    @Test
    void reservationConflictNeverBecomesAnOutOfStockCancellation() {
        var saga = claim("PENDING_STOCK", 0);
        doThrow(new StockFailure(RESERVATION_CONFLICT)).when(store).reserve(first, 42, List.of(small, large));
        assertEquals(DEFERRED, coordinator.processNext(home));
        verify(store).retry(home, saga, Duration.ofSeconds(1));
        verify(store, never()).cancel(any(), any());
    }

    @Test
    void unknownLedgerTransitionOutcomeRetriesWithoutReleasingStock() {
        var saga = claim("PENDING_STOCK", 0);
        when(store.pendingLedger(eq(home), eq(saga), any())).thenThrow(new StockFailure(UNAVAILABLE));
        assertEquals(DEFERRED, coordinator.processNext(home));
        verify(store).retry(home, saga, Duration.ofSeconds(1));
        verify(store, never()).release(any(), anyLong(), any());
    }

    @Test
    void unavailableTransportIdsLeaveReservationsHeldForRetry() {
        var saga = claim("PENDING_STOCK", 0);
        when(generator.nextId()).thenThrow(mock(IdGenerationUnavailableException.class));
        when(store.pendingLedger(eq(home), eq(saga), any())).thenAnswer(call -> {
            call.getArgument(2, LongSupplier.class).getAsLong();
            return true;
        });
        assertEquals(DEFERRED, coordinator.processNext(home));
        verify(store).retry(home, saga, Duration.ofSeconds(1));
        verify(store, never()).release(any(), anyLong(), any());
        verify(store, never()).cancel(any(), any());
    }

    @Test
    void noClaimDoesNoOtherWork() {
        when(store.claim(home, LEASE)).thenReturn(Optional.empty());
        assertEquals(IDLE, coordinator.processNext(home));
        verify(store).claim(home, LEASE);
        verifyNoMoreInteractions(store);
        verifyNoInteractions(generator);
    }

    @ParameterizedTest
    @CsvSource({"0,1", "1,2", "4,16", "5,30", "31,30", "2147483647,30"})
    void retriesHaveBoundedExponentialBackoff(int failures, long seconds) {
        assertEquals(Duration.ofSeconds(seconds), StockCoordinator.retryDelay(failures));
    }

    private StockStore.Saga claim(String status, int failures) {
        var order = new Order(3, 42, "fingerprint", "USD", new BigDecimal("6.00"), status,
                "CANCELLED".equals(status) ? "OUT_OF_STOCK" : null);
        var saga = new StockStore.Saga(50, 51, order, List.of(large, remote, small), 1, failures);
        when(store.claim(home, LEASE)).thenReturn(Optional.of(saga));
        return saga;
    }

    private static ItemSnapshot item(int position, long seller, long product) {
        return new ItemSnapshot(position, seller, product, 1, "Product", new BigDecimal("2.00"),
                new BigDecimal("1.00"), "USD");
    }
}
