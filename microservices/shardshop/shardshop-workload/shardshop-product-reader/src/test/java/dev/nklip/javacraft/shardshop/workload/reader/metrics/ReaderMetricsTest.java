package dev.nklip.javacraft.shardshop.workload.reader.metrics;

import dev.nklip.javacraft.shardshop.workload.reader.http.ReadResult;
import dev.nklip.javacraft.shardshop.workload.reader.http.ReadResult.Outcome;
import org.apache.hc.core5.pool.PoolStats;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReaderMetricsTest {
    private static final long MILLISECOND = 1_000_000L;
    private static final long SECOND = 1_000 * MILLISECOND;
    private static final PoolStats FULL = new PoolStats(16, 0, 0, 16);

    private long now = Long.MAX_VALUE - 10 * SECOND;
    private final ReaderMetrics metrics = new ReaderMetrics(() -> now);

    @Test
    void reportsRequestsOutcomesLatencyRegionsAndThePoolStateOfOneInterval() {
        for (int milliseconds = 1; milliseconds <= 100; milliseconds++) {
            metrics.record("US", ReadResult.ok("product"), milliseconds * MILLISECOND);
        }
        // A latency rounds up to whole milliseconds and includes the wait for a connection.
        metrics.record("EU", ReadResult.failed(Outcome.DEADLINE), 5_000 * MILLISECOND + 1);
        now += 30 * SECOND;
        ReaderMetrics.Report report = metrics.nextInterval(new PoolStats(12, 1, 4, 16));
        assertEquals(101, report.requests());
        assertEquals(Map.of(Outcome.OK, 100L, Outcome.DEADLINE, 1L), report.outcomes());
        assertEquals(Map.of("p50", 51L, "p95", 96L, "p99", 100L, "max", 5_001L), report.latencyMilliseconds());
        assertTrue(report.failed());
        assertEquals("Product reads, interval 30.0 s: 101 requests, 3.4/s, outcomes {OK=100, DEADLINE=1}, "
                + "latency ms {p50=51, p95=96, p99=100, max=5001}, regions {EU=1, US=100}, "
                + "pool {leased=12, pending=1, available=4, max=16}", report.summary());
        assertThrows(UnsupportedOperationException.class, () -> report.outcomes().put(Outcome.OK, 1L));
        assertThrows(UnsupportedOperationException.class, () -> report.regions().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.latencyMilliseconds().clear());
    }

    @Test
    void startsEachIntervalEmpty() {
        metrics.record("US", ReadResult.failed(Outcome.TRANSPORT), MILLISECOND);
        now += 30 * SECOND;
        metrics.nextInterval(FULL);
        metrics.record("ASIA", ReadResult.ok("product"), 0);
        now += 30 * SECOND;
        ReaderMetrics.Report second = metrics.nextInterval(FULL);
        assertFalse(second.failed());
        assertEquals("Product reads, interval 30.0 s: 1 requests, 0.0/s, outcomes {OK=1}, "
                + "latency ms {p50=0, p95=0, p99=0, max=0}, regions {ASIA=1}, "
                + "pool {leased=16, pending=0, available=0, max=16}", second.summary());
        now += 15 * SECOND;
        ReaderMetrics.Report empty = metrics.nextInterval(new PoolStats(0, 0, 16, 16));
        assertFalse(empty.failed());
        assertEquals("Product reads, interval 15.0 s: 0 requests, 0.0/s, outcomes {}, latency ms {}, regions {}, "
                + "pool {leased=0, pending=0, available=16, max=16}", empty.summary());
    }

    @Test
    void beginsTheIntervalAndTheTotalsWhenTheLoadStarts() {
        metrics.record("US", ReadResult.ok("product"), MILLISECOND);
        now += 20 * SECOND;
        metrics.begin();
        metrics.record("EU", ReadResult.ok("product"), MILLISECOND);
        now += 10 * SECOND;
        assertEquals("Product reads, interval 10.0 s: 1 requests, 0.1/s, outcomes {OK=1}, "
                + "latency ms {p50=1, p95=1, p99=1, max=1}, regions {EU=1}, "
                + "pool {leased=16, pending=0, available=0, max=16}", metrics.nextInterval(FULL).summary());
        assertEquals("Product reads, total 10.0 s: 1 requests, 0.1/s, outcomes {OK=1}, "
                + "latency ms {p50=1, p95=1, p99=1, max=1}, regions {EU=1}, "
                + "pool {leased=16, pending=0, available=0, max=16}", metrics.total(FULL).summary());
    }

    @Test
    void reportsTotalsSinceStartAndCapsTheHistogramButNotTheMaximum() {
        assertEquals("Product reads, total 0.0 s: 0 requests, 0.0/s, outcomes {}, latency ms {}, regions {}, "
                + "pool {leased=0, pending=0, available=0, max=16}", metrics.total(new PoolStats(0, 0, 0, 16)).summary());
        metrics.record("US", ReadResult.failed(Outcome.NOT_FOUND), 12 * SECOND);
        now += 10 * SECOND;
        metrics.nextInterval(FULL);
        metrics.record("EU", ReadResult.failed(Outcome.REPLICA_UNAVAILABLE), 3_999 * MILLISECOND);
        now += 10 * SECOND;
        ReaderMetrics.Report total = metrics.total(FULL);
        assertEquals("Product reads, total 20.0 s: 2 requests, 0.1/s, outcomes {NOT_FOUND=1, REPLICA_UNAVAILABLE=1}, "
                + "latency ms {p50=3999, p95=10000, p99=10000, max=12000}, regions {EU=1, US=1}, "
                + "pool {leased=16, pending=0, available=0, max=16}", total.summary());
        assertTrue(total.failed());
    }
}
