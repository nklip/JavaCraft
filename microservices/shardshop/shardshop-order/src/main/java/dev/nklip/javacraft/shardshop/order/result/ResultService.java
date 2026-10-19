package dev.nklip.javacraft.shardshop.order.result;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nklip.javacraft.shardshop.order.ordering.OrderService;
import dev.nklip.javacraft.shardshop.sharding.ShardRouter;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static dev.nklip.javacraft.shardshop.order.result.ResultOutcome.Disposition.*;

/** Validates durable correlation before changing state; transport deduplication is never the first check. */
public final class ResultService {
    private final ResultStore store;
    private final ShardRouter router;

    public ResultService(ResultStore store, ShardRouter router) {
        this.store = Objects.requireNonNull(store);
        this.router = Objects.requireNonNull(router);
    }

    public ResultOutcome handle(byte[] body) {
        LedgerResult result = LedgerResult.parse(body);
        return store.result(router.route(result.buyerId()), session -> apply(session, result));
    }

    private ResultOutcome apply(ResultStore.Session session, LedgerResult result) {
        // Creation checks orphan quarantine under this lock, thus a result never passes a creation in progress.
        session.lock(OrderService.orderLock(result.orderId()));
        var saved = session.state(result.orderId());
        if (saved.isEmpty()) {
            session.quarantine(result, "ORDER_ABSENT", JsonNodeFactory.instance.objectNode(), true);
            return new ResultOutcome(result.messageId(), result.orderId(), ORPHANED, "ORDER_ABSENT");
        }
        ResultStore.State state = saved.orElseThrow();
        var binding = session.binding(result.messageId());
        String conflict = conflict(session, state, binding.orElse(null), result);
        if (conflict != null) {
            return quarantine(session, state, result, conflict);
        }
        if (!"PENDING_LEDGER".equals(state.orderStatus())) {
            session.receive(result);
            return new ResultOutcome(result.messageId(), result.orderId(), DUPLICATE, null);
        }
        List<ResultStore.Credit> credits = new ArrayList<>();
        if ("RECORDED".equals(result.outcome())) {
            var items = session.items(result.orderId());
            if (items.isEmpty()) {
                return quarantine(session, state, result, "ITEM_SNAPSHOTS_ABSENT");
            }
            for (var item : items) {
                if (item.unitCost() == null) {
                    return quarantine(session, state, result, "ITEM_COST_UNKNOWN");
                }
                BigDecimal margin = item.unitPrice().subtract(item.unitCost())
                        .multiply(BigDecimal.valueOf(item.quantity()));
                if (margin.precision() - margin.scale() > 17) {
                    return quarantine(session, state, result, "ITEM_PROFIT_OUT_OF_RANGE");
                }
                credits.add(new ResultStore.Credit(item, margin));
            }
        }
        session.apply(state, result, List.copyOf(credits));
        session.receive(result);
        return new ResultOutcome(result.messageId(), result.orderId(), APPLIED, null);
    }

    private String conflict(ResultStore.Session session, ResultStore.State state, ResultStore.Binding binding,
                            LedgerResult result) {
        if (session.orphaned(result.orderId())) {
            return "UNRESOLVED_ORPHAN";
        }
        if (state.sagaId() == 0 || state.commandId() == 0) {
            return "SAGA_ABSENT";
        }
        if (state.buyerId() != result.buyerId() || state.sagaId() != result.sagaId()
                || state.commandId() != result.commandId()) {
            return "IDENTITY_MISMATCH";
        }
        if (!state.fingerprint().equals(result.requestFingerprint())
                || !state.currency().equals(result.currency()) || state.amount().compareTo(result.amount()) != 0) {
            return "SNAPSHOT_MISMATCH";
        }
        if (binding == null || binding.sagaId() != state.sagaId()) {
            return "RESULT_ID_NOT_RESERVED";
        }
        if (binding.receivedEnvelope() != null && !binding.receivedEnvelope().equals(result.envelope())) {
            return "RESULT_ENVELOPE_MISMATCH";
        }
        if (!state.orderStatus().equals(state.sagaStatus())) {
            return "ORDER_SAGA_STATE_MISMATCH";
        }
        if ("PENDING_LEDGER".equals(state.orderStatus())) {
            return state.ledgerResult() == null && binding.receivedEnvelope() == null ? null : "SAVED_OUTCOME_MISMATCH";
        }
        String expectedStatus = "RECORDED".equals(result.outcome()) ? "CONFIRMED" : "CANCELLED";
        if (!expectedStatus.equals(state.orderStatus()) || state.ledgerResult() == null) {
            return "OUTCOME_MISMATCH";
        }
        final LedgerResult previous;
        try {
            previous = LedgerResult.parse(state.ledgerResult().toString().getBytes(StandardCharsets.UTF_8));
        } catch (MalformedResultException invalidStoredResult) {
            throw new ResultFailure(invalidStoredResult);
        }
        if (previous.commandId() != result.commandId() || previous.sagaId() != result.sagaId()
                || previous.orderId() != result.orderId() || previous.buyerId() != result.buyerId()
                || !previous.requestFingerprint().equals(result.requestFingerprint())
                || !previous.outcome().equals(result.outcome()) || !previous.currency().equals(result.currency())
                || previous.amount().compareTo(result.amount()) != 0
                || !previous.decidedAt().equals(result.decidedAt())
                || !Objects.equals(previous.rejectionReason(), result.rejectionReason())
                || !Objects.equals(state.cancellationReason(), result.rejectionReason())) {
            return "OUTCOME_MISMATCH";
        }
        return null;
    }

    private ResultOutcome quarantine(ResultStore.Session session, ResultStore.State state,
                                     LedgerResult result, String reason) {
        ObjectNode expected = JsonNodeFactory.instance.objectNode();
        expected.put("orderId", Long.toString(state.orderId()));
        expected.put("buyerId", Long.toString(state.buyerId()));
        expected.put("sagaId", Long.toString(state.sagaId()));
        expected.put("commandId", Long.toString(state.commandId()));
        expected.put("requestFingerprint", state.fingerprint());
        expected.put("amount", state.amount().toPlainString());
        expected.put("currency", state.currency());
        expected.put("orderStatus", state.orderStatus());
        expected.put("sagaStatus", state.sagaStatus());
        if (state.ledgerResult() != null) {
            expected.set("ledgerResult", state.ledgerResult());
        }
        session.quarantine(result, reason, expected, false);
        return new ResultOutcome(result.messageId(), result.orderId(), CONFLICT, reason);
    }
}
