package dev.nklip.javacraft.shardshop.order;

import io.quarkus.runtime.Quarkus;
import dev.nklip.javacraft.shardshop.order.config.StockWorker;
import dev.nklip.javacraft.shardshop.order.cdc.OrderCdcRelay;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class OrderLifecycleTest {
    @Test
    void serverWaitsForShutdownUnlessExplicitlyCheckingStartup() {
        StockWorker stock = mock(StockWorker.class);
        OrderCdcRelay cdc = mock(OrderCdcRelay.class);
        try (var runtime = mockStatic(Quarkus.class)) {
            assertEquals(0, new OrderApplication(stock, cdc).run());
            runtime.verify(Quarkus::waitForExit);
        }
        var lifecycle = inOrder(stock, cdc);
        lifecycle.verify(cdc).start();
        lifecycle.verify(stock).start();
        lifecycle.verify(stock).close();
        lifecycle.verify(cdc).close();
        try (var runtime = mockStatic(Quarkus.class)) {
            assertEquals(0, new OrderApplication(stock, cdc).run("--unknown"));
            runtime.verify(Quarkus::waitForExit);
        }
        clearInvocations(stock, cdc);
        assertEquals(0, new OrderApplication(stock, cdc).run("--check-startup"));
        verifyNoInteractions(stock, cdc);
    }
}
