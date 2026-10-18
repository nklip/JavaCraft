package dev.nklip.javacraft.shardshop.ledger.command;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** Permanent ownership of a command/result transport pair and its original full command. */
public record TransportBinding(long messageId, long resultMessageId, long orderId, ObjectNode commandEnvelope) {
    public boolean samePair(LedgerCommand command) {
        return messageId == command.messageId() && resultMessageId == command.resultMessageId();
    }

    public boolean sameDelivery(LedgerCommand command) {
        return samePair(command) && orderId == command.orderId()
                && commandEnvelope.equals(command.envelope());
    }
}
