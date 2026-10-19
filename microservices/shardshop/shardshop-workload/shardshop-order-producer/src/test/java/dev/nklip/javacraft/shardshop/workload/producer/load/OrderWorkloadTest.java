package dev.nklip.javacraft.shardshop.workload.producer.load;

import com.fasterxml.jackson.core.JsonParseException;
import dev.nklip.javacraft.shardshop.workload.dataset.BuyerDataset;
import dev.nklip.javacraft.shardshop.workload.dataset.CatalogDataset;
import dev.nklip.javacraft.shardshop.workload.producer.ProducerDatasets;
import dev.nklip.javacraft.shardshop.workload.producer.http.OrderRequests;
import dev.nklip.javacraft.shardshop.workload.producer.plan.PlannedOrder;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderWorkloadTest {
    private final ProducerDatasets datasets = mock();
    private final OrderRequests orders = mock();
    private final List<Duration> waits = new ArrayList<>();
    private final RetryPolicy retry = new RetryPolicy(3, Duration.ofMillis(100), Duration.ofSeconds(2), waits::add, () -> 0);
    private final OrderWorkload workload = new OrderWorkload(datasets, orders, retry, waits::add, () -> 0,
            Duration.ofMillis(250));

    @Test
    void lostResponsesKeepTheSameAllocationAndExactOrderUntilTerminalStatus() throws Exception {
        var planner = WorkloadFixtures.planner("stable", 10);
        String buyer = "9007199254740993";
        String order = "880803840000004254";
        when(orders.allocate(buyer, "stable", 0)).thenThrow(new IOException()).thenReturn(order);
        when(orders.submit(any())).thenThrow(WorkloadFixtures.httpFailure(503, "ORDER_STORE_UNAVAILABLE"))
                .thenReturn(WorkloadFixtures.status("PENDING_STOCK"));
        when(orders.status(buyer, order)).thenReturn(WorkloadFixtures.status("PENDING_LEDGER"))
                .thenThrow(new IOException()).thenReturn(WorkloadFixtures.status("CANCELLED"));
        var progress = new OrderWorkload.Progress();
        var completion = workload.complete(planner, "stable", 0, progress);
        assertEquals("CANCELLED", completion.status().status());
        assertFalse(completion.recovered(), "A pending first answer is work of this process");
        assertEquals("ordinal 0, buyer " + buyer + ", order " + order + ", last status CANCELLED", progress.describe(0));
        verify(orders, times(2)).allocate(buyer, "stable", 0);
        ArgumentCaptor<PlannedOrder> sent = ArgumentCaptor.forClass(PlannedOrder.class);
        verify(orders, times(2)).submit(sent.capture());
        assertSame(sent.getAllValues().getFirst(), sent.getAllValues().getLast());
        assertEquals(order, sent.getValue().orderId());
        assertEquals("USD", sent.getValue().currency());
        assertEquals(3, sent.getValue().items().size());
        assertEquals(2, waits.stream().filter(Duration.ofMillis(250)::equals).count());
    }

    @Test
    void aRecoveredTerminalPutNeedsNoPollingOrNewAllocation() throws Exception {
        var planner = WorkloadFixtures.planner("stable", 10);
        when(orders.allocate(anyString(), eq("stable"), eq(0L))).thenReturn("880803840000004254");
        when(orders.submit(any())).thenReturn(WorkloadFixtures.status("CONFIRMED"));
        var completion = workload.complete(planner, "stable", 0, new OrderWorkload.Progress());
        assertEquals("CONFIRMED", completion.status().status());
        assertTrue(completion.recovered(), "A terminal first answer is an order from before this process");
        verify(orders, never()).status(anyString(), anyString());
        assertTrue(waits.isEmpty());
    }

    @Test
    void catalogWaitsForMissingDataButAnInvalidDefinitionOrJsonFailsImmediately() throws Exception {
        var definition = new CatalogDataset(CatalogDataset.VERSION).sellers().getFirst();
        when(datasets.sellers()).thenReturn(List.of(definition));
        when(datasets.lookupSeller(definition)).thenThrow(WorkloadFixtures.httpFailure(404, "SELLER_NOT_FOUND"))
                .thenThrow(new IllegalArgumentException("private response"));
        assertThrows(ProducerException.class, workload::resolveCatalog);
        assertEquals(List.of(Duration.ofSeconds(1)), waits);
        verifyNoInteractions(orders);
        waits.clear();
        doThrow(new JsonParseException(null, "private response")).when(datasets).lookupSeller(definition);
        assertThrows(ProducerException.class, workload::resolveCatalog);
        assertTrue(waits.isEmpty());
    }

    @Test
    void buyerRetriesRecoverSameDefinitionButDuplicateIssuedIdsStopStartup() throws Exception {
        var definitions = new BuyerDataset(BuyerDataset.VERSION).buyers().subList(0, 2);
        when(datasets.buyers()).thenReturn(definitions);
        when(datasets.createBuyer(definitions.getFirst())).thenThrow(new IOException())
                .thenReturn(new ProducerDatasets.CreatedBuyer(definitions.getFirst(), "9007199254740993"));
        when(datasets.createBuyer(definitions.getLast()))
                .thenReturn(new ProducerDatasets.CreatedBuyer(definitions.getLast(), "9007199254740993"));
        ProducerException failure = assertThrows(ProducerException.class, workload::createBuyers);
        assertEquals("Buyer response IDs are not unique", failure.getMessage());
        verify(datasets, times(2)).createBuyer(definitions.getFirst());
        verifyNoInteractions(orders);
    }
}
