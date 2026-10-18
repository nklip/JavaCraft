package dev.nklip.javacraft.shardshop.ledger.command;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Instant;

/** Permanent business decision, independent of command/result transport IDs. */
public record LedgerDecision(long orderId, long buyerId, long sagaId, long commandId, String fingerprint,
                             ObjectNode snapshot, BigDecimal amount, String currency, String outcome,
                             String rejectionReason, Instant decidedAt, ObjectNode resultData) {
    public static LedgerDecision first(LedgerCommand command, boolean currencyAllowed, Instant decidedAt) {
        String outcome = currencyAllowed ? "RECORDED" : "REJECTED";
        String reason = currencyAllowed ? null : "UNSUPPORTED_CURRENCY";
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.put("type", currencyAllowed ? "LedgerRecorded" : "LedgerRejected");
        data.put("schemaVersion", 1);
        data.put("commandId", Long.toString(command.commandId()));
        data.put("sagaId", Long.toString(command.sagaId()));
        data.put("orderId", Long.toString(command.orderId()));
        data.put("buyerId", Long.toString(command.buyerId()));
        data.put("requestFingerprint", command.requestFingerprint());
        data.put("amount", command.amount().toPlainString());
        data.put("currency", command.currency());
        data.put("outcome", outcome);
        if (reason != null) {
            data.put("rejectionReason", reason);
        }
        data.put("decidedAt", decidedAt.toString());
        return new LedgerDecision(command.orderId(), command.buyerId(), command.sagaId(), command.commandId(),
                command.requestFingerprint(), command.snapshot(), command.amount(), command.currency(),
                outcome, reason, decidedAt, data);
    }

    public boolean matches(LedgerCommand command) {
        return orderId == command.orderId() && buyerId == command.buyerId()
                && sagaId == command.sagaId() && commandId == command.commandId()
                && fingerprint.equals(command.requestFingerprint()) && snapshot.equals(command.snapshot())
                && amount.compareTo(command.amount()) == 0 && currency.equals(command.currency());
    }

    public ObjectNode resultEnvelope(long resultMessageId, Instant now) {
        ObjectNode envelope = resultData.deepCopy();
        envelope.put("messageId", Long.toString(resultMessageId));
        envelope.put("occurredAt", (now.isBefore(decidedAt) ? decidedAt : now).toString());
        return envelope;
    }
}
