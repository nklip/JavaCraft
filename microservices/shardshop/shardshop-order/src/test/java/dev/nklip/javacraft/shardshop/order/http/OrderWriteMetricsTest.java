package dev.nklip.javacraft.shardshop.order.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OrderWriteMetricsTest {
    @Test
    void separatesWriteOperationsAndUsesBoundedRecentPercentilesWithLifetimeTotals() {
        var metrics = new OrderWriteMetrics();
        assertNull(metrics.operation("GET", "/api/v1/buyers/1/orders/2"));
        var allocation = metrics.operation("POST", "/api/v1/buyers/1/order-allocations");
        var put = metrics.operation("PUT", "/api/v1/buyers/1/orders/2");
        assertEquals(0, metrics.snapshot().get("put").requests());
        allocation.record(10_000_000L, 201);
        for (int i = 0; i < 5000; i++) {
            put.record(i * 1_000_000L, i == 0 ? 503 : 202);
        }
        var snapshot = metrics.snapshot();
        assertEquals(1, snapshot.get("allocation").requests());
        assertEquals(10, snapshot.get("allocation").p95Millis());
        assertEquals(5000, snapshot.get("put").requests());
        assertEquals(1, snapshot.get("put").errors());
        assertEquals(4096, snapshot.get("put").sampleSize());
        assertEquals(2951, snapshot.get("put").p50Millis());
        assertEquals(4795, snapshot.get("put").p95Millis());
        assertEquals(4999, snapshot.get("put").maxMillis());
    }
}
