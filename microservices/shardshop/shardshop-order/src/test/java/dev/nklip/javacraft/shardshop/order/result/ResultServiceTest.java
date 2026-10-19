package dev.nklip.javacraft.shardshop.order.result;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nklip.javacraft.shardshop.order.ordering.OrderModel.ItemSnapshot;
import dev.nklip.javacraft.shardshop.order.ordering.OrderService;
import dev.nklip.javacraft.shardshop.sharding.Shard;
import dev.nklip.javacraft.shardshop.sharding.ShardRouter;
import dev.nklip.javacraft.shardshop.sharding.ShardTopology;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static dev.nklip.javacraft.shardshop.order.result.ResultOutcome.Disposition.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ResultServiceTest {
    private final ResultStore store = mock(ResultStore.class);
    private final ResultStore.Session session = mock(ResultStore.Session.class);
    private final ShardRouter router = mock(ShardRouter.class);
    private final Shard home = ShardTopology.fromNamesAndRegions("a,b", "US,EU").shards().getFirst();
    private final ResultService service = new ResultService(store, router);

    @BeforeEach
    void transaction() {
        when(router.route(5)).thenReturn(home);
        when(store.result(eq(home), any())).thenAnswer(call -> {
            Function<ResultStore.Session, ResultOutcome> work = call.getArgument(1);
            return work.apply(session);
        });
    }

    @Test
    void confirmsWithImmutableSignedMarginsAndReceivesWithinTheSameTransaction() {
        var state = pending();
        var gain = item(0, "10.00", "4.50", 3);
        var loss = item(1, "5.00", "7.00", 2);
        when(session.items(4)).thenReturn(List.of(gain, loss));

        assertEquals(APPLIED, service.handle(bytes(recorded())).disposition());

        var sequence = inOrder(session);
        sequence.verify(session).lock(OrderService.orderLock(4));
        sequence.verify(session).state(4);
        sequence.verify(session).apply(eq(state), any(), eq(List.of(
                new ResultStore.Credit(gain, new BigDecimal("16.50")),
                new ResultStore.Credit(loss, new BigDecimal("-4.00")))));
        sequence.verify(session).receive(any());
    }

    @Test
    void rejectsWithoutCreatingProfitOrReadingCatalogSnapshots() {
        var state = pending();
        ObjectNode rejection = recorded().put("type", "LedgerRejected").put("outcome", "REJECTED")
                .put("rejectionReason", "UNSUPPORTED_CURRENCY");

        assertEquals(APPLIED, service.handle(bytes(rejection)).disposition());

        verify(session).apply(eq(state), argThat(result -> "REJECTED".equals(result.outcome())), eq(List.of()));
        verify(session, never()).items(anyLong());
    }

    @Test
    void quarantinesMissingOrderBeforeLookingUpItsReservation() {
        when(session.state(4)).thenReturn(Optional.empty());

        assertEquals(ORPHANED, service.handle(bytes(recorded())).disposition());

        var sequence = inOrder(session);
        sequence.verify(session).lock(OrderService.orderLock(4));
        sequence.verify(session).state(4);
        sequence.verify(session).quarantine(any(), eq("ORDER_ABSENT"), any(), eq(true));
        verify(session, never()).binding(anyLong());
        verify(session, never()).receive(any());
    }

    @ParameterizedTest
    @CsvSource({"commandId,8,IDENTITY_MISMATCH", "sagaId,8,IDENTITY_MISMATCH",
            "amount,40.01,SNAPSHOT_MISMATCH", "currency,EUR,SNAPSHOT_MISMATCH"})
    void quarantineDoesNotChangeTheOrderOrDeduplicateInvalidResults(String field, String value, String reason) {
        pending();
        assertEquals(reason, service.handle(bytes(recorded().put(field, value))).reason());
        verify(session).quarantine(any(), eq(reason), any(), eq(false));
        verify(session, never()).apply(any(), any(), any());
        verify(session, never()).receive(any());
    }

    @Test
    void reservationAndExistingQuarantineMustBothPermitApplication() {
        pending();
        when(session.binding(1)).thenReturn(Optional.empty());
        assertEquals("RESULT_ID_NOT_RESERVED", service.handle(bytes(recorded())).reason());
        when(session.orphaned(4)).thenReturn(true);
        assertEquals("UNRESOLVED_ORPHAN", service.handle(bytes(recorded())).reason());
        verify(session, never()).apply(any(), any(), any());
    }

    @Test
    void matchingTerminalReplayAcceptsNewReservedIdsButRejectsChangedDecisionAndSameIdPayload() {
        ObjectNode original = recorded();
        terminal(original);
        when(session.binding(1)).thenReturn(Optional.of(new ResultStore.Binding(3, canonical(original))));
        assertEquals(DUPLICATE, service.handle(bytes(original)).disposition());
        ObjectNode replay = original.deepCopy().put("messageId", "8").put("occurredAt", "2026-10-19T00:01:00Z");
        when(session.binding(8)).thenReturn(Optional.of(new ResultStore.Binding(3, null)));
        assertEquals(DUPLICATE, service.handle(bytes(replay)).disposition());
        assertEquals("OUTCOME_MISMATCH", service.handle(bytes(replay.put("decidedAt", "2026-10-19T00:00:01Z"))).reason());
        assertEquals("RESULT_ENVELOPE_MISMATCH", service.handle(bytes(original.put("occurredAt", "2026-10-19T00:01:00Z"))).reason());
        verify(session, never()).apply(any(), any(), any());
        verify(session, never()).items(anyLong());
    }

    @Test
    void unknownOrUnrepresentableLegacyMarginCannotConfirmOrInventAZeroCredit() {
        pending();
        when(session.items(4)).thenReturn(List.of(item(0, "10.00", null, 2)));
        assertEquals("ITEM_COST_UNKNOWN", service.handle(bytes(recorded())).reason());
        when(session.items(4)).thenReturn(List.of(item(0, "10.00", "99999999999999999.99", 2)));
        assertEquals("ITEM_PROFIT_OUT_OF_RANGE", service.handle(bytes(recorded())).reason());
        verify(session, never()).apply(any(), any(), any());
        verify(session, never()).receive(any());
    }

    @Test
    void failedCommitPropagatesRatherThanReturningAnAcknowledgableOutcome() {
        pending();
        when(session.items(4)).thenReturn(List.of(item(0, "10.00", "2.00", 4)));
        when(store.result(eq(home), any())).thenAnswer(call -> {
            Function<ResultStore.Session, ResultOutcome> work = call.getArgument(1);
            work.apply(session);
            throw new ResultFailure(new IllegalStateException("commit outcome unknown"));
        });
        assertThrows(ResultFailure.class, () -> service.handle(bytes(recorded())));
    }

    private ResultStore.State pending() {
        var state = new ResultStore.State(4, 5, "a".repeat(64), "USD", new BigDecimal("40.00"),
                "PENDING_LEDGER", 3, 2, "PENDING_LEDGER", null, null);
        when(session.state(4)).thenReturn(Optional.of(state));
        when(session.binding(1)).thenReturn(Optional.of(new ResultStore.Binding(3, null)));
        return state;
    }

    private void terminal(ObjectNode result) {
        when(session.state(4)).thenReturn(Optional.of(new ResultStore.State(4, 5, "a".repeat(64), "USD",
                new BigDecimal("40.00"), "CONFIRMED", 3, 2, "CONFIRMED", null, canonical(result))));
    }

    private static ItemSnapshot item(int position, String price, String cost, int quantity) {
        return new ItemSnapshot(position, 10 + position, 20 + position, quantity, "Item",
                new BigDecimal(price), cost == null ? null : new BigDecimal(cost), "USD");
    }

    private static ObjectNode recorded() {
        return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                .put("type", "LedgerRecorded").put("schemaVersion", 1).put("messageId", "1")
                .put("commandId", "2").put("sagaId", "3").put("orderId", "4").put("buyerId", "5")
                .put("requestFingerprint", "a".repeat(64)).put("occurredAt", "2026-10-19T00:00:00Z")
                .put("decidedAt", "2026-10-19T00:00:00Z").put("amount", "40.00").put("currency", "USD")
                .put("outcome", "RECORDED");
    }

    private static ObjectNode canonical(ObjectNode result) {
        return LedgerResult.parse(bytes(result)).envelope();
    }

    private static byte[] bytes(ObjectNode result) {
        return result.toString().getBytes(StandardCharsets.UTF_8);
    }
}
