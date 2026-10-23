package dev.nklip.javacraft.shardshop.workload.producer.load;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LatenciesTest {
    @Test
    void p95UsesNearestRankAndTheWindowDropsOldSamplesWithoutGrowing() {
        var latencies = new Latencies();
        assertEquals(0, latencies.snapshot().samples());
        for (int millis = 1; millis <= 100; millis++) {
            latencies.add(millis * 1_000_000L);
        }
        assertEquals(new BigDecimal("50.500"), latencies.snapshot().meanMillis());
        assertEquals(new BigDecimal("95.000"), latencies.snapshot().p95Millis());
        for (int sample = 0; sample < 1024; sample++) {
            latencies.add(7_000_000);
        }
        assertEquals(1124, latencies.snapshot().samples());
        assertEquals(new BigDecimal("7.000"), latencies.snapshot().meanMillis());
        assertEquals(new BigDecimal("7.000"), latencies.snapshot().p95Millis());
    }
}
