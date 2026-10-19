package dev.nklip.javacraft.shardshop.order;

import io.quarkus.runtime.Quarkus;
import dev.nklip.javacraft.shardshop.order.config.StockWorker;
import dev.nklip.javacraft.shardshop.order.cdc.OrderCdcRelay;
import dev.nklip.javacraft.shardshop.order.config.ProfitWorker;
import dev.nklip.javacraft.shardshop.order.messaging.OrderResultConsumer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class OrderLifecycleTest {
    @Test
    void serverWaitsForShutdownUnlessExplicitlyCheckingStartup() {
        StockWorker stock = mock(StockWorker.class);
        OrderCdcRelay cdc = mock(OrderCdcRelay.class);
        OrderResultConsumer results = mock(OrderResultConsumer.class);
        ProfitWorker profit = mock(ProfitWorker.class);
        try (var runtime = mockStatic(Quarkus.class)) {
            assertEquals(0, new OrderApplication(stock, cdc, results, profit).run());
            runtime.verify(Quarkus::waitForExit);
        }
        var lifecycle = inOrder(stock, cdc, results, profit);
        lifecycle.verify(cdc).start();
        lifecycle.verify(results).start();
        lifecycle.verify(stock).start();
        lifecycle.verify(profit).start();
        lifecycle.verify(results).close();
        lifecycle.verify(profit).close();
        lifecycle.verify(stock).close();
        lifecycle.verify(cdc).close();
        try (var runtime = mockStatic(Quarkus.class)) {
            assertEquals(0, new OrderApplication(stock, cdc, results, profit).run("--unknown"));
            runtime.verify(Quarkus::waitForExit);
        }
        clearInvocations(stock, cdc, results, profit);
        assertEquals(0, new OrderApplication(stock, cdc, results, profit).run("--check-startup"));
        verifyNoInteractions(stock, cdc, results, profit);
    }
}
