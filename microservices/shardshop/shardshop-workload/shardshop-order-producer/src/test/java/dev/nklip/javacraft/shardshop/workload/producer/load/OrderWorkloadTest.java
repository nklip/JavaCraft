package dev.nklip.javacraft.shardshop.workload.producer.load;

import com.fasterxml.jackson.core.JsonParseException;
import dev.nklip.javacraft.shardshop.workload.control.ControlStatus;
import dev.nklip.javacraft.shardshop.workload.control.WorkloadControl;
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
    private final WorkloadControl control = WorkloadFixtures.control("1");
    private final RetryPolicy retry = new RetryPolicy(3, Duration.ofMillis(100), Duration.ofSeconds(2), waits::add, () -> 0,
            control.traffic());
    private final OrderWorkload workload = new OrderWorkload(datasets, orders, retry, waits::add, () -> 0,
            Duration.ofMillis(250));

    @Test
    void lostResponsesKeepTheSameAllocationAndExactOrderUntilTerminalStatus() throws Exception {
        var planner = WorkloadFixtures.planner("stable", 10);
        String buyer = "9007199254740993";
        String order = "880803840000004254";
        when(orders.allocate(buyer, planner.allocationRunName(), 0)).thenThrow(new IOException()).thenReturn(order);
        when(orders.submitWithReplay(any())).thenThrow(WorkloadFixtures.httpFailure(503, "ORDER_STORE_UNAVAILABLE"))
                .thenReturn(new OrderRequests.Submission(WorkloadFixtures.status("PENDING_STOCK"), false));
        when(orders.status(buyer, order)).thenReturn(WorkloadFixtures.status("PENDING_LEDGER"))
                .thenThrow(new IOException()).thenReturn(WorkloadFixtures.status("CANCELLED"));
        List<String> accepted = new ArrayList<>();
        var progress = new OrderWorkload.Progress(() -> accepted.add("accepted"));
        var written = workload.write(planner, "stable", 0, progress);
        assertEquals("PENDING_STOCK", written.status().status(), "Writing returns without waiting for the saga");
        verify(orders, never()).status(anyString(), anyString());
        var observed = workload.observe(written.status(), progress);
        assertEquals("PENDING_LEDGER", observed.status());
        var completion = new OrderWorkload.Completion(workload.observe(observed, progress), written.recovered());
        assertEquals("CANCELLED", completion.status().status());
        assertEquals(List.of("accepted"), accepted, "A pending first answer is one new order");
        assertEquals(List.of(new ControlStatus.TrafficCategory("startup", 0, 0, 0),
                new ControlStatus.TrafficCategory("allocation", 2, 1, 1),
                new ControlStatus.TrafficCategory("submission", 2, 1, 1),
                new ControlStatus.TrafficCategory("polling", 3, 1, 1)), control.status().traffic());
        assertFalse(completion.recovered(), "A pending first answer is work of this process");
        assertEquals("ordinal 0, buyer " + buyer + ", order " + order + ", last status CANCELLED", progress.describe(0));
        verify(orders, times(2)).allocate(buyer, planner.allocationRunName(), 0);
        ArgumentCaptor<PlannedOrder> sent = ArgumentCaptor.forClass(PlannedOrder.class);
        verify(orders, times(2)).submitWithReplay(sent.capture());
        assertSame(sent.getAllValues().getFirst(), sent.getAllValues().getLast());
        assertEquals(order, sent.getValue().orderId());
        assertEquals("USD", sent.getValue().currency());
        assertEquals(3, sent.getValue().items().size());
        assertEquals(0, waits.stream().filter(Duration.ofMillis(250)::equals).count(),
                "The observer scheduler owns polling intervals");
    }

    @Test
    void aRecoveredTerminalPutNeedsNoPollingOrNewAllocation() throws Exception {
        var planner = WorkloadFixtures.planner("stable", 10);
        when(orders.allocate(anyString(), eq(planner.allocationRunName()), eq(0L))).thenReturn("880803840000004254");
        when(orders.submitWithReplay(any())).thenReturn(new OrderRequests.Submission(WorkloadFixtures.status("CONFIRMED"), true));
        List<String> accepted = new ArrayList<>();
        var completion = workload.write(planner, "stable", 0, new OrderWorkload.Progress(() -> accepted.add("new")));
        assertEquals("CONFIRMED", completion.status().status());
        assertTrue(completion.recovered(), "A terminal first answer is an order from before this process");
        assertEquals(List.of(), accepted, "A recovered order is not a new order");
        verify(orders, never()).status(anyString(), anyString());
        assertTrue(waits.isEmpty());
    }

    @Test
    void replayedPendingOrdersAreRecoveredAndStillNeedIndependentObservation() throws Exception {
        var planner = WorkloadFixtures.planner("stable", 10);
        when(orders.allocate(anyString(), anyString(), anyLong())).thenReturn("880803840000004254");
        when(orders.submitWithReplay(any())).thenReturn(
                new OrderRequests.Submission(WorkloadFixtures.status("PENDING_LEDGER"), true));
        List<String> accepted = new ArrayList<>();
        var written = workload.write(planner, "stable", 0, new OrderWorkload.Progress(() -> accepted.add("new")));
        assertTrue(written.recovered());
        assertFalse(written.status().terminal());
        assertTrue(accepted.isEmpty());
        verify(orders, never()).status(anyString(), anyString());
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
        assertEquals(new ControlStatus.TrafficCategory("startup", 2, 0, 2), control.status().traffic().getFirst(),
                "Each catalog lookup is one startup request");
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
