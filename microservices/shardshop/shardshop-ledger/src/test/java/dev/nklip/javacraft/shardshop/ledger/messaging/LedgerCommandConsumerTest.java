package dev.nklip.javacraft.shardshop.ledger.messaging;

import dev.nklip.javacraft.shardshop.common.amqp.QueueConsumer.Settlement;
import dev.nklip.javacraft.shardshop.ledger.command.CommandDisposition;
import dev.nklip.javacraft.shardshop.ledger.command.CommandOutcome;
import dev.nklip.javacraft.shardshop.ledger.command.MalformedCommandException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The shared consumer of common owns the broker protocol; these rules map ledger's command outcomes to it. */
class LedgerCommandConsumerTest {
    private static final byte[] BODY = {1};

    @Test
    void consumesTheRecordOrderQueue() {
        assertEquals("ledger.record-order", LedgerCommandConsumer.SOURCE.queue());
        assertEquals("Ledger command", LedgerCommandConsumer.SOURCE.consumer());
        assertEquals("ledger command", LedgerCommandConsumer.SOURCE.message());
    }

    @Test
    void decisionsAndReplaysAreAcknowledgedWithoutAWarning() {
        for (CommandDisposition disposition : CommandDisposition.values()) {
            if (disposition != CommandDisposition.QUARANTINED) {
                assertEquals(Settlement.acknowledge(), LedgerCommandConsumer.settle(
                        body -> new CommandOutcome(disposition, 3, 4, null), BODY));
            }
        }
    }

    @Test
    void committedQuarantineIsAcknowledgedWithItsConflict() {
        assertEquals(Settlement.acknowledge("Conflicting ledger command 3 for order 4 committed to quarantine: "
                        + "PAYLOAD_CONFLICT"),
                LedgerCommandConsumer.settle(body -> new CommandOutcome(CommandDisposition.QUARANTINED, 3, 4,
                        "PAYLOAD_CONFLICT"), BODY));
    }

    @Test
    void malformedCommandIsRejectedWithItsRule() {
        assertEquals(Settlement.reject("Invalid JSON body"), LedgerCommandConsumer.settle(body -> {
            throw new MalformedCommandException("Invalid JSON body");
        }, BODY));
    }

    @Test
    void failedTransactionReachesTheSharedConsumerForABrokerRetry() {
        IllegalStateException failure = new IllegalStateException("database unavailable");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> LedgerCommandConsumer.settle(body -> {
            throw failure;
        }, BODY)));
    }
}
