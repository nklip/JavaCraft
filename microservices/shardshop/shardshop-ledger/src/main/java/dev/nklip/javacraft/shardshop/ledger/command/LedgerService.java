package dev.nklip.javacraft.shardshop.ledger.command;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

/** Validates a command before opening its bounded ledger transaction. */
public final class LedgerService {
    private final LedgerStore store;
    private final Set<String> allowedCurrencies;
    private final Clock clock;

    public LedgerService(LedgerStore store, Set<String> allowedCurrencies, Clock clock) {
        this.store = java.util.Objects.requireNonNull(store);
        this.allowedCurrencies = Set.copyOf(allowedCurrencies);
        if (this.allowedCurrencies.isEmpty() || this.allowedCurrencies.stream().anyMatch(c -> !c.matches("[A-Z]{3}"))) {
            throw new IllegalArgumentException("Invalid ledger currency allowlist");
        }
        this.clock = java.util.Objects.requireNonNull(clock);
    }

    public CommandOutcome handle(byte[] body) {
        LedgerCommand command = LedgerCommand.parse(body);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        return store.write(command, session -> decide(session, command, now));
    }

    private CommandOutcome decide(LedgerStore.Session session, LedgerCommand command, Instant now) {
        List<LedgerDecision> operations = session.operations(command);
        List<TransportBinding> bindings = session.bindings(command);
        LedgerDecision decision = operations.isEmpty() ? null : operations.getFirst();
        TransportBinding exact = null;
        for (TransportBinding binding : bindings) {
            if (binding.samePair(command)) {
                exact = binding;
            }
        }
        String conflict = null;
        if (operations.size() > 1 || decision != null && !decision.matches(command)) {
            conflict = "BUSINESS_IDENTITY_CONFLICT";
        } else if (bindings.size() != (exact == null ? 0 : 1)
                || exact != null && exact.orderId() != command.orderId()) {
            conflict = "TRANSPORT_IDENTITY_CONFLICT";
        } else if (exact != null && !exact.sameDelivery(command)) {
            conflict = "PAYLOAD_CONFLICT";
        } else if (decision == null && exact != null) {
            conflict = "TRANSPORT_IDENTITY_CONFLICT";
        }
        if (conflict != null) {
            session.quarantine(command, expectedIdentity(operations, bindings), receivedIdentity(command),
                    conflict, now);
            return new CommandOutcome(CommandDisposition.QUARANTINED, command.messageId(), command.orderId(), conflict);
        }
        boolean first = decision == null;
        if (first) {
            decision = LedgerDecision.first(command, allowedCurrencies.contains(command.currency()), now);
            session.insertDecision(command, decision);
        }
        if (exact == null) {
            session.insertBinding(command, decision.resultEnvelope(command.resultMessageId(), now), now);
            session.insertInbox(command, now);
            session.insertOutbox(command.resultMessageId());
        } else {
            session.insertInbox(command, now);
            long attempt = session.incrementAttempt(command.resultMessageId());
            session.rearmOutbox(command.resultMessageId(), attempt);
        }
        return new CommandOutcome(first ? CommandDisposition.APPLIED : CommandDisposition.REPLAYED,
                command.messageId(), command.orderId(), null);
    }

    private static ObjectNode expectedIdentity(List<LedgerDecision> operations, List<TransportBinding> bindings) {
        ObjectNode expected = JsonNodeFactory.instance.objectNode();
        if (!operations.isEmpty()) {
            var values = expected.putArray("operations");
            for (LedgerDecision decision : operations) {
                ObjectNode item = values.addObject();
                item.put("orderId", Long.toString(decision.orderId()));
                item.put("buyerId", Long.toString(decision.buyerId()));
                item.put("sagaId", Long.toString(decision.sagaId()));
                item.put("commandId", Long.toString(decision.commandId()));
                item.put("requestFingerprint", decision.fingerprint());
                item.set("snapshot", decision.snapshot());
                item.put("amount", decision.amount().toPlainString());
                item.put("currency", decision.currency());
                item.put("outcome", decision.outcome());
            }
        }
        if (!bindings.isEmpty()) {
            var values = expected.putArray("bindings");
            for (TransportBinding binding : bindings) {
                ObjectNode item = values.addObject();
                item.put("messageId", Long.toString(binding.messageId()));
                item.put("resultMessageId", Long.toString(binding.resultMessageId()));
                item.put("orderId", Long.toString(binding.orderId()));
            }
        }
        return expected;
    }

    private static ObjectNode receivedIdentity(LedgerCommand command) {
        ObjectNode received = JsonNodeFactory.instance.objectNode();
        received.put("orderId", Long.toString(command.orderId()));
        received.put("buyerId", Long.toString(command.buyerId()));
        received.put("sagaId", Long.toString(command.sagaId()));
        received.put("commandId", Long.toString(command.commandId()));
        received.put("messageId", Long.toString(command.messageId()));
        received.put("resultMessageId", Long.toString(command.resultMessageId()));
        received.put("requestFingerprint", command.requestFingerprint());
        received.set("snapshot", command.snapshot());
        return received;
    }
}
