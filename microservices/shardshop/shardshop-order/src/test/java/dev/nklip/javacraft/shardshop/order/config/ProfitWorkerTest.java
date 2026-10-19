package dev.nklip.javacraft.shardshop.order.config;

import dev.nklip.javacraft.shardshop.order.profit.ProfitFailure;
import dev.nklip.javacraft.shardshop.order.profit.ProfitStore;
import dev.nklip.javacraft.shardshop.order.profit.ProfitStore.PendingCredit;
import dev.nklip.javacraft.shardshop.sharding.ShardRouter;
import dev.nklip.javacraft.shardshop.sharding.ShardTopology;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProfitWorkerTest {
    private final ProfitStore store = mock();
    private final ShardRouter router = mock();
    private final ShardTopology topology = ShardTopology.fromNamesAndRegions("a,b", "US,EU");
    private final ProfitWorker.Settings settings = new ProfitWorker.Settings(true, Duration.ofMillis(250), 2);

    @Test
    void oneFailedSellerDoesNotStopOtherIntentsOrHomeShards() {
        var first = topology.shards().getFirst();
        var second = topology.shards().getLast();
        var failed = new PendingCredit(41, 0, 51, 61, "USD", new BigDecimal("1.00"), 0);
        var healthy = new PendingCredit(42, 0, 52, 62, "USD", new BigDecimal("2.00"), 0);
        when(store.pendingProfit(first, 2)).thenReturn(List.of(failed, healthy));
        when(store.pendingProfit(second, 2)).thenReturn(List.of());
        when(router.route(51)).thenReturn(second);
        when(router.route(52)).thenReturn(second);
        doThrow(new ProfitFailure(ProfitFailure.Reason.UNAVAILABLE)).when(store).creditProfit(second, failed);
        doThrow(new ProfitFailure(ProfitFailure.Reason.UNAVAILABLE)).when(store)
                .retryProfit(eq(first), eq(failed), any());

        try (var worker = new ProfitWorker(store, router, topology, settings)) {
            worker.poll();
        }
        verify(store).creditProfit(second, healthy);
        verify(store).markProfitApplied(first, healthy);
        verify(store).pendingProfit(second, 2);
    }

    /** An operator searches by order ID and must see which shard failed and why. */
    @Test
    void logLinesNameTheFailedShardTheCauseAndThePlainOrderId() {
        var home = topology.shards().getFirst();
        var seller = topology.shards().getLast();
        var deferred = new PendingCredit(105614554681012224L, 0, 51, 61, "USD", new BigDecimal("1.00"), 0);
        var blocked = new PendingCredit(105614554681012225L, 3, 52, 62, "USD", new BigDecimal("2.00"), 0);
        when(store.pendingProfit(home, 2)).thenReturn(List.of(deferred, blocked), List.of(deferred));
        when(store.pendingProfit(seller, 2)).thenReturn(List.of());
        when(router.route(51)).thenReturn(seller);
        when(router.route(52)).thenReturn(seller);
        doThrow(ProfitFailure.from(new SQLException("Connection refused", "08001"))).doNothing()
                .when(store).creditProfit(seller, deferred);
        doThrow(ProfitFailure.from(new SQLException("Foreign key violation", "23503")))
                .when(store).creditProfit(seller, blocked);
        List<String> lines = new CopyOnWriteArrayList<>();
        Logger log = Logger.getLogger(ProfitWorker.class.getName());
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                String level = record.getLevel().intValue() >= Level.SEVERE.intValue() ? "ERROR"
                        : record.getLevel().intValue() >= Level.WARNING.intValue() ? "WARN" : "INFO";
                lines.add(level + " " + new SimpleFormatter().formatMessage(record));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        log.addHandler(capture);
        log.setUseParentHandlers(false);
        try (var worker = new ProfitWorker(store, router, topology, settings)) {
            worker.poll();
            worker.poll();
        } finally {
            log.removeHandler(capture);
            log.setUseParentHandlers(true);
        }

        assertEquals(List.of(
                "WARN Profit posting unavailable for b; durable intents will retry: "
                        + "ProfitFailure: UNAVAILABLE <- SQLException: Connection refused",
                "ERROR Profit intent blocked for order 105614554681012225, item 3 on a: "
                        + "MISSING_CATALOG_REFERENCE; investigate the durable intent",
                "INFO Profit posting resumed for b"), lines);
    }

    @Test
    void settingsBoundThePollingIntervalAndBatch() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProfitWorker.Settings(true, Duration.ofMillis(99), 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ProfitWorker.Settings(true, Duration.ofSeconds(31), 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ProfitWorker.Settings(true, Duration.ofSeconds(1), 101));
    }
}
