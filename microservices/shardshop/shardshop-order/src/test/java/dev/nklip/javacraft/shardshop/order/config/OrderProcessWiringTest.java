package dev.nklip.javacraft.shardshop.order.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderProcessWiringTest {
    /** Shared enabled flags never start stock, profit or result work in the HTTP process. */
    @ParameterizedTest
    @EnumSource(OrderConfiguration.Process.class)
    void workersBelongToTheSagaProcess(OrderConfiguration.Process process) {
        var order = mock(OrderConfiguration.class);
        var stock = mock(OrderConfiguration.Stock.class);
        var profit = mock(OrderConfiguration.Profit.class);
        var results = mock(OrderConfiguration.Results.class);
        var reconciliation = mock(OrderConfiguration.Reconciliation.class);
        when(order.process()).thenReturn(process);
        when(order.stock()).thenReturn(stock);
        when(order.profit()).thenReturn(profit);
        when(order.results()).thenReturn(results);
        when(order.reconciliation()).thenReturn(reconciliation);
        when(stock.enabled()).thenReturn(true);
        when(profit.enabled()).thenReturn(true);
        when(results.enabled()).thenReturn(true);
        when(reconciliation.enabled()).thenReturn(true);
        when(reconciliation.pollInterval()).thenReturn(Duration.ofSeconds(30));
        when(reconciliation.batchSize()).thenReturn(100);
        when(stock.pollInterval()).thenReturn(Duration.ofMillis(250));
        when(stock.lease()).thenReturn(Duration.ofSeconds(30));
        when(stock.batchSize()).thenReturn(8);
        when(profit.pollInterval()).thenReturn(Duration.ofMillis(250));
        when(profit.batchSize()).thenReturn(8);
        var cdc = mock(CdcConfiguration.class);
        var broker = mock(CdcConfiguration.Broker.class);
        when(cdc.broker()).thenReturn(broker);
        when(broker.host()).thenReturn("localhost");
        when(broker.port()).thenReturn(5672);
        when(broker.virtualHost()).thenReturn("shardshop");
        when(broker.username()).thenReturn("order");
        when(broker.password()).thenReturn(Optional.of("test-secret"));
        when(broker.timeout()).thenReturn(Duration.ofSeconds(5));
        boolean saga = process == OrderConfiguration.Process.SAGA;
        assertEquals(saga, new OrderWiring().stockSettings(order).enabled());
        assertEquals(saga, new ResultWiring().profitSettings(order).enabled());
        assertEquals(saga, new ResultWiring().resultSettings(order, cdc).enabled());
        assertEquals(saga, new ReconciliationWiring().settings(order).enabled());
    }
}
