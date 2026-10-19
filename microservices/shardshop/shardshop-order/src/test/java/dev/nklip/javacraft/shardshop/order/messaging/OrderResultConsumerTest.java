package dev.nklip.javacraft.shardshop.order.messaging;

import dev.nklip.javacraft.shardshop.common.amqp.QueueConsumer.Settlement;
import dev.nklip.javacraft.shardshop.order.result.MalformedResultException;
import dev.nklip.javacraft.shardshop.order.result.ResultFailure;
import dev.nklip.javacraft.shardshop.order.result.ResultOutcome;
import dev.nklip.javacraft.shardshop.order.result.ResultOutcome.Disposition;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

/** The shared consumer of common owns the broker protocol; these rules map order's result outcomes to it. */
class OrderResultConsumerTest {
    private static final byte[] BODY = {1};

    @Test
    void consumesTheLedgerResultQueue() {
        assertEquals("order.ledger-results", OrderResultConsumer.SOURCE.queue());
        assertEquals("Order result", OrderResultConsumer.SOURCE.consumer());
        assertEquals("ledger result", OrderResultConsumer.SOURCE.message());
    }

    @Test
    void appliedAndDuplicateResultsAreAcknowledgedWithoutAWarning() {
        for (Disposition disposition : new Disposition[]{Disposition.APPLIED, Disposition.DUPLICATE}) {
            assertEquals(Settlement.acknowledge(), OrderResultConsumer.settle(
                    body -> new ResultOutcome(3, 4, disposition, null), BODY));
        }
    }

    @Test
    void committedQuarantinesAreAcknowledgedWithTheirReason() {
        assertEquals(Settlement.acknowledge("Ledger result 3 for order 4 committed to ORPHANED quarantine: ORDER_ABSENT"),
                OrderResultConsumer.settle(body -> new ResultOutcome(3, 4, Disposition.ORPHANED, "ORDER_ABSENT"), BODY));
        assertEquals(Settlement.acknowledge("Ledger result 3 for order 4 committed to CONFLICT quarantine: "
                        + "RESULT_ENVELOPE_MISMATCH"),
                OrderResultConsumer.settle(body -> new ResultOutcome(3, 4, Disposition.CONFLICT,
                        "RESULT_ENVELOPE_MISMATCH"), BODY));
    }

    @Test
    void malformedResultIsRejectedWithItsRule() {
        assertEquals(Settlement.reject("Invalid JSON body"), OrderResultConsumer.settle(body -> {
            throw new MalformedResultException("Invalid JSON body");
        }, BODY));
    }

    @Test
    void failedTransactionReachesTheSharedConsumerForABrokerRetry() {
        ResultFailure failure = new ResultFailure(new SQLException("Connection refused"));
        assertSame(failure, assertThrows(ResultFailure.class, () -> OrderResultConsumer.settle(body -> {
            throw failure;
        }, BODY)));
    }
}
