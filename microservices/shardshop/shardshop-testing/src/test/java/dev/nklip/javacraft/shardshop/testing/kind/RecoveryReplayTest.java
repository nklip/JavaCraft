package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class RecoveryReplayTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void resultsOnlyWorkerCannotStartCdcStockProfitCleanupOrReconciliation() {
        Properties original = new Properties();
        for (String worker : List.of("cdc", "stock", "profit", "reconciliation", "cleanup", "results"))
            original.setProperty("shardshop.order." + worker + ".enabled", "true");
        original.setProperty("shardshop.order.connections.shard-a.primary-url", "jdbc:postgresql://127.0.0.1:1234/shard_a");
        Properties scoped = RecoveryReplay.restrictOrder(original, false, 0);
        assertEquals("saga", scoped.getProperty("shardshop.order.process"));
        assertEquals("true", scoped.getProperty("shardshop.order.results.enabled"));
        assertEquals("1", scoped.getProperty("shardshop.id.generator-id"));
        for (String worker : List.of("cdc", "stock", "profit", "reconciliation", "cleanup"))
            assertEquals("false", scoped.getProperty("shardshop.order." + worker + ".enabled"), worker);
        assertEquals(original.getProperty("shardshop.order.connections.shard-a.primary-url"),
                scoped.getProperty("shardshop.order.connections.shard-a.primary-url"));
        assertEquals("true", original.getProperty("shardshop.order.cdc.enabled"));
    }

    @Test
    void httpOnlyWitnessDisablesEveryBackgroundWriterAndBindsLoopback() {
        Properties scoped = RecoveryReplay.restrictOrder(new Properties(), true, 12345);
        assertEquals("http", scoped.getProperty("shardshop.order.process"));
        assertEquals("127.0.0.1", scoped.getProperty("quarkus.http.host"));
        assertEquals("12345", scoped.getProperty("quarkus.http.port"));
        for (String worker : List.of("cdc", "stock", "profit", "reconciliation", "cleanup", "results"))
            assertEquals("false", scoped.getProperty("shardshop.order." + worker + ".enabled"), worker);
    }

    @Test
    void originalPutRetainsOrderedBusinessRequestAndDropsTransportAndCatalogSnapshotFields() throws Exception {
        var command = JSON.readTree("""
                {"messageId":"99","orderId":"100","snapshot":{"amount":"12.00","currency":"USD","items":[
                {"itemPosition":0,"sellerId":"21","productId":"22","quantity":2,"productName":"Original","unitPrice":"5.00","currency":"USD"},
                {"itemPosition":1,"sellerId":"31","productId":"32","quantity":1,"productName":"Other","unitPrice":"2.00","currency":"USD"}]}}
                """);
        assertEquals(JSON.readTree("""
                {"currency":"USD","items":[{"sellerId":"21","productId":"22","quantity":2},
                {"sellerId":"31","productId":"32","quantity":1}]}
                """), RecoveryReplay.originalPut(command));
    }

    @Test
    void incompleteOrMalformedOriginalRequestCannotBecomeAReplay() {
        for (String invalid : List.of("{}", "{\"snapshot\":{\"currency\":\"USD\",\"items\":[]}}",
                "{\"snapshot\":{\"currency\":\"USD\",\"items\":[{\"sellerId\":\"1\",\"productId\":\"2\",\"quantity\":0}]}}"))
            assertThrows(IOException.class, () -> RecoveryReplay.originalPut(JSON.readTree(invalid)));
    }
}
