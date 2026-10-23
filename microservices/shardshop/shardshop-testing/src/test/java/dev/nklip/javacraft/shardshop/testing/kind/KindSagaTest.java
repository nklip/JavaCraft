package dev.nklip.javacraft.shardshop.testing.kind;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** The observer must reject missing, duplicated or monetarily incorrect permanent effects. */
final class KindSagaTest {
    private String status = "CONFIRMED";
    private String reason = "";
    private String ledgerAmount = "10.00";
    private String creditAmount = "6.00";
    private boolean duplicateReservation;
    private boolean missingReservation;
    private boolean activeCancelledReservation;

    private KindSaga observer() throws Exception {
        KindLab lab = mock(KindLab.class);
        when(lab.shards()).thenReturn(List.of("shard-a", "shard-b", "shard-c"));
        KindSaga observer = spy(new KindSaga(lab));
        doAnswer(invocation -> {
            String cluster = invocation.getArgument(0);
            String query = invocation.getArgument(1);
            boolean confirmed = status.equals("CONFIRMED");
            if (query.contains("FROM ordering.orders o")) return """
                    {"currency":"USD","total_amount":"10.00","status":"%s","cancellation_reason":"%s","released":%s}
                    """.formatted(status, reason, !confirmed);
            if (query.contains("FROM ledger.ledger_operations")) return """
                    {"decisions":%d,"entries":%d,"outcome":"%s","currency":"USD","amount":"%s"}
                    """.formatted(reason.equals("OUT_OF_STOCK") ? 0 : 1, confirmed ? 1 : 0, confirmed ? "RECORDED" : "REJECTED", ledgerAmount);
            if (query.contains("FROM ordering.order_items")) return """
                    [{"product_id":"3","seller_id":"2","quantity":2,"currency":"USD","margin":"6.00"}]
                    """;
            if (query.contains("catalog.stock_reservations")) {
                boolean sellerShard = cluster.equals("shard-b");
                String reservation = sellerShard && !missingReservation || duplicateReservation ?
                        "{\"quantity\":2,\"released\":" + (!confirmed && !activeCancelledReservation) + "}" : "";
                String credit = sellerShard && confirmed ?
                        "{\"seller_id\":\"2\",\"currency\":\"USD\",\"amount\":\"" + creditAmount + "\"}" : "";
                return "{\"reservations\":[" + reservation + "],\"credits\":[" + credit + "]}";
            }
            if (query.contains("ordering.profit_intents")) return confirmed ? "1" : "0";
            throw new AssertionError("Unexpected observation query");
        }).when(observer).sql(anyString(), anyString());
        return observer;
    }

    @Test void confirmedCrossShardOrderRequiresOneLedgerEntryReservationAndExactMargin() throws Exception {
        observer().verifyOrder("shard-a", "1", status);
    }

    @Test void ledgerRejectionRequiresReleasedStockAndNoProfit() throws Exception {
        status = "CANCELLED"; reason = "UNSUPPORTED_CURRENCY";
        observer().verifyOrder("shard-a", "1", status);
        activeCancelledReservation = true;
        assertThrows(IOException.class, () -> observer().verifyOrder("shard-a", "1", status));
    }

    @Test void outOfStockMayHaveNoReservationAndMustHaveNoLedgerEntry() throws Exception {
        status = "CANCELLED"; reason = "OUT_OF_STOCK"; missingReservation = true;
        observer().verifyOrder("shard-a", "1", status);
    }

    @Test void duplicatedOrMissingCrossShardReservationsFailTheObservation() throws Exception {
        duplicateReservation = true;
        assertThrows(IOException.class, () -> observer().verifyOrder("shard-a", "1", status));
        duplicateReservation = false; missingReservation = true;
        assertThrows(IOException.class, () -> observer().verifyOrder("shard-a", "1", status));
    }

    @Test void monetaryChecksCompareLedgerTotalsAndImmutableItemMargins() throws Exception {
        ledgerAmount = "9.99";
        assertThrows(IOException.class, () -> observer().verifyOrder("shard-a", "1", status));
        ledgerAmount = "10.00"; creditAmount = "5.99";
        assertThrows(IOException.class, () -> observer().verifyOrder("shard-a", "1", status));
    }

    @Test void noncanonicalOrOverflowingIdsCannotBecomeSqlFragments() throws Exception {
        for (String invalid : List.of("0", "01", "1 OR TRUE", "1\n", "9223372036854775808"))
            assertThrows(IOException.class, () -> KindSaga.id(invalid));
        assertEquals("9223372036854775807", KindSaga.id("9223372036854775807"));
    }

    @Test void httpAndSagaDeploymentRolesCannotBeSwappedOrScaledIntoOverlappingReaders() throws Exception {
        var http = KindLab.parse("""
                {"spec":{"replicas":2,"strategy":{"type":"RollingUpdate"},"template":{"spec":{"containers":[
                {"env":[{"name":"SHARDSHOP_ORDER_PROCESS","value":"http"}]}]}}}}
                """);
        var saga = KindLab.parse("""
                {"spec":{"replicas":1,"strategy":{"type":"Recreate"},"template":{"spec":{"containers":[
                {"env":[{"name":"SHARDSHOP_ORDER_PROCESS","value":"saga"}]}]}}}}
                """);
        KindSaga.deploymentShape("order", http);
        KindSaga.deploymentShape("order-saga", saga);
        assertThrows(IOException.class, () -> KindSaga.deploymentShape("order", saga));
        assertThrows(IOException.class, () -> KindSaga.deploymentShape("order-saga", http));
        ((com.fasterxml.jackson.databind.node.ObjectNode) saga.path("spec")).put("replicas", 2);
        assertThrows(IOException.class, () -> KindSaga.deploymentShape("order-saga", saga));
    }

    @Test void cdcReplacementMustChangeBothJvmAndWorker() throws Exception {
        var before = KindLab.parse("{\"metadata\":{\"uid\":\"old\"},\"spec\":{\"nodeName\":\"worker-a\"}}");
        var moved = KindLab.parse("{\"metadata\":{\"uid\":\"new\"},\"spec\":{\"nodeName\":\"worker-b\"}}");
        KindSagaDrill.replacement(before, moved, "worker-b");
        assertThrows(IOException.class, () -> KindSagaDrill.replacement(before, before, "worker-a"));
        assertThrows(IOException.class, () -> KindSagaDrill.replacement(before, moved, "worker-c"));
    }
}
