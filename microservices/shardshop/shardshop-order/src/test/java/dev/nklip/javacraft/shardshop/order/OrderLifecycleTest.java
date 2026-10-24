package dev.nklip.javacraft.shardshop.order;

import io.quarkus.runtime.Quarkus;
import dev.nklip.javacraft.shardshop.order.config.OrderConfiguration;
import dev.nklip.javacraft.shardshop.order.config.StockWorker;
import dev.nklip.javacraft.shardshop.order.cdc.OrderCdcRelay;
import dev.nklip.javacraft.shardshop.order.config.ProfitWorker;
import dev.nklip.javacraft.shardshop.order.config.ReconciliationWorker;
import dev.nklip.javacraft.shardshop.order.messaging.OrderResultConsumer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class OrderLifecycleTest {
    @Test
    void serverWaitsForShutdownUnlessExplicitlyCheckingStartup() {
        OrderConfiguration config = mock(OrderConfiguration.class);
        when(config.process()).thenReturn(OrderConfiguration.Process.SAGA);
        StockWorker stock = mock(StockWorker.class);
        OrderCdcRelay cdc = mock(OrderCdcRelay.class);
        OrderResultConsumer results = mock(OrderResultConsumer.class);
        ProfitWorker profit = mock(ProfitWorker.class);
        ReconciliationWorker reconciliation = mock(ReconciliationWorker.class);
        try (var runtime = mockStatic(Quarkus.class)) {
            assertEquals(0, new OrderApplication(stock, cdc, results, profit, reconciliation, config).run());
            runtime.verify(Quarkus::waitForExit);
        }
        var lifecycle = inOrder(stock, cdc, results, profit, reconciliation);
        lifecycle.verify(cdc).start();
        lifecycle.verify(results).start();
        lifecycle.verify(stock).start();
        lifecycle.verify(profit).start();
        lifecycle.verify(reconciliation).start();
        lifecycle.verify(reconciliation).close();
        lifecycle.verify(results).close();
        lifecycle.verify(profit).close();
        lifecycle.verify(stock).close();
        lifecycle.verify(cdc).close();
        try (var runtime = mockStatic(Quarkus.class)) {
            assertEquals(0, new OrderApplication(stock, cdc, results, profit, reconciliation, config).run("--unknown"));
            runtime.verify(Quarkus::waitForExit);
        }
        clearInvocations(stock, cdc, results, profit, reconciliation);
        when(config.process()).thenReturn(OrderConfiguration.Process.HTTP);
        try (var runtime = mockStatic(Quarkus.class)) {
            assertEquals(0, new OrderApplication(stock, cdc, results, profit, reconciliation, config).run());
            runtime.verify(Quarkus::waitForExit);
        }
        verify(stock, never()).start();
        verify(cdc, never()).start();
        verify(results, never()).start();
        verify(profit, never()).start();
        verify(reconciliation, never()).start();
        clearInvocations(stock, cdc, results, profit, reconciliation);
        assertEquals(0, new OrderApplication(stock, cdc, results, profit, reconciliation, config).run("--check-startup"));
        verifyNoInteractions(stock, cdc, results, profit, reconciliation);
    }
}
