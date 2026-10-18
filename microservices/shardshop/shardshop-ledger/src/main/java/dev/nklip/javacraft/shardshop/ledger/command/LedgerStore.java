package dev.nklip.javacraft.shardshop.ledger.command;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;
import java.util.function.Function;

/** The IO operations available inside one atomic, identity-locked ledger transaction. */
public interface LedgerStore {
    <T> T write(LedgerCommand command, Function<Session, T> work);

    interface Session {
        List<LedgerDecision> operations(LedgerCommand command);

        List<TransportBinding> bindings(LedgerCommand command);

        void insertDecision(LedgerCommand command, LedgerDecision decision);

        void insertBinding(LedgerCommand command, ObjectNode resultEnvelope, Instant now);

        void insertInbox(LedgerCommand command, Instant now);

        void insertOutbox(long resultMessageId);

        long incrementAttempt(long resultMessageId);

        void rearmOutbox(long resultMessageId, long attempt);

        void quarantine(LedgerCommand command, ObjectNode expectedIdentity,
                        ObjectNode receivedIdentity, String reason, Instant now);
    }
}
