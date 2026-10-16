package dev.nklip.javacraft.shardshop.order;

import io.quarkus.runtime.Quarkus;
import dev.nklip.javacraft.shardshop.order.config.StockWorker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class OrderLifecycleTest {
    @Test
    void serverWaitsForShutdownUnlessExplicitlyCheckingStartup() {
        StockWorker stock = mock(StockWorker.class);
        try (var runtime = mockStatic(Quarkus.class)) {
            assertEquals(0, new OrderApplication(stock).run());
            runtime.verify(Quarkus::waitForExit);
        }
        var lifecycle = inOrder(stock);
        lifecycle.verify(stock).start();
        lifecycle.verify(stock).close();
        try (var runtime = mockStatic(Quarkus.class)) {
            assertEquals(0, new OrderApplication(stock).run("--unknown"));
            runtime.verify(Quarkus::waitForExit);
        }
        clearInvocations(stock);
        assertEquals(0, new OrderApplication(stock).run("--check-startup"));
        verifyNoInteractions(stock);
    }
}
