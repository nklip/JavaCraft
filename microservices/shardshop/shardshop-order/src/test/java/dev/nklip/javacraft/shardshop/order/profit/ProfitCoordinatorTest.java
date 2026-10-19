package dev.nklip.javacraft.shardshop.order.profit;

import dev.nklip.javacraft.shardshop.order.profit.ProfitCoordinator.Progress.Status;
import dev.nklip.javacraft.shardshop.order.profit.ProfitStore.PendingCredit;
import dev.nklip.javacraft.shardshop.sharding.ShardRouter;
import dev.nklip.javacraft.shardshop.sharding.ShardTopology;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProfitCoordinatorTest {
    private final ProfitStore store = mock();
    private final ShardRouter router = mock();
    private final ShardTopology topology = ShardTopology.fromNamesAndRegions("home,seller", "US,EU");

    @Test
    void marksHomeOnlyAfterSellerCreditAndRetriesUnknownSellerCommitSafely() {
        var home = topology.shards().getFirst();
        var seller = topology.shards().getLast();
        var credit = new PendingCredit(41, 0, 51, 61, "USD", new BigDecimal("3.00"), 2);
        when(router.route(51)).thenReturn(seller);
        var coordinator = new ProfitCoordinator(store, router);
        doThrow(new ProfitFailure(ProfitFailure.Reason.UNAVAILABLE)).doNothing()
                .when(store).creditProfit(seller, credit);

        var deferred = coordinator.process(home, credit);
        assertEquals(Status.DEFERRED, deferred.status());
        assertEquals(seller, deferred.unavailable(), "The log names the seller shard that failed");
        verify(store).retryProfit(eq(home), eq(credit), argThat(delay ->
                delay.compareTo(Duration.ofSeconds(4)) >= 0 && delay.compareTo(Duration.ofSeconds(5)) <= 0));
        verify(store, never()).markProfitApplied(any(), any());

        assertEquals(Status.APPLIED, coordinator.process(home, credit).status());
        var order = inOrder(store);
        order.verify(store).creditProfit(seller, credit);
        order.verify(store).retryProfit(eq(home), eq(credit), any());
        order.verify(store).creditProfit(seller, credit);
        order.verify(store).markProfitApplied(home, credit);
    }

    @Test
    void permanentCreditConflictBlocksDurableIntent() {
        var home = topology.shards().getFirst();
        var seller = topology.shards().getLast();
        var credit = new PendingCredit(41, 0, 51, 61, "USD", new BigDecimal("3.00"), 0);
        when(router.route(51)).thenReturn(seller);
        doThrow(new ProfitFailure(ProfitFailure.Reason.CREDIT_CONFLICT))
                .when(store).creditProfit(seller, credit);
        assertEquals(Status.BLOCKED, new ProfitCoordinator(store, router).process(home, credit).status());
        verify(store).blockProfit(home, credit, ProfitFailure.Reason.CREDIT_CONFLICT);
        verify(store, never()).markProfitApplied(any(), any());
        verify(store, never()).retryProfit(any(), any(), any());
    }

    @Test
    void definitiveMissingCatalogReferenceBlocksWithoutRetry() {
        var home = topology.shards().getFirst();
        var seller = topology.shards().getLast();
        var credit = new PendingCredit(41, 0, 51, 61, "USD", new BigDecimal("3.00"), 0);
        when(router.route(51)).thenReturn(seller);
        doThrow(new ProfitFailure(ProfitFailure.Reason.MISSING_CATALOG_REFERENCE))
                .when(store).creditProfit(seller, credit);
        assertEquals(Status.BLOCKED, new ProfitCoordinator(store, router).process(home, credit).status());
        verify(store).blockProfit(home, credit, ProfitFailure.Reason.MISSING_CATALOG_REFERENCE);
        verify(store, never()).retryProfit(any(), any(), any());
    }

    @Test
    void failedHomeMarkAfterSellerCommitIsDeferredOnTheHomeShard() {
        var home = topology.shards().getFirst();
        var seller = topology.shards().getLast();
        var credit = new PendingCredit(41, 0, 51, 61, "USD", new BigDecimal("3.00"), 0);
        when(router.route(51)).thenReturn(seller);
        doThrow(new ProfitFailure(ProfitFailure.Reason.UNAVAILABLE)).when(store).markProfitApplied(home, credit);

        var progress = new ProfitCoordinator(store, router).process(home, credit);

        assertEquals(Status.DEFERRED, progress.status());
        assertEquals(home, progress.unavailable(), "The seller commit worked; the home mark failed");
        verify(store).retryProfit(eq(home), eq(credit), any());
        verify(store, never()).blockProfit(any(), any(), any());
    }
}
