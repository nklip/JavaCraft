package dev.nklip.javacraft.shardshop.order.messaging;

import dev.nklip.javacraft.shardshop.common.amqp.BrokerSettings;
import dev.nklip.javacraft.shardshop.common.amqp.QueueConsumer;
import dev.nklip.javacraft.shardshop.order.result.MalformedResultException;
import dev.nklip.javacraft.shardshop.order.result.ResultOutcome;
import dev.nklip.javacraft.shardshop.order.result.ResultOutcome.Disposition;

import java.util.Objects;
import java.util.function.Function;

/** Binds order.ledger-results to the shared consumer of common; this class maps result outcomes to settlements. */
public final class OrderResultConsumer implements AutoCloseable {
    static final QueueConsumer.Source SOURCE = new QueueConsumer.Source("order.ledger-results", "Order result",
            "ledger result", "order-result-consumer");
    private final QueueConsumer consumer;

    public OrderResultConsumer(Settings settings, Function<byte[], ResultOutcome> handler) {
        Objects.requireNonNull(handler, "handler");
        consumer = new QueueConsumer(settings.broker(), settings.enabled(), SOURCE, body -> settle(handler, body));
    }

    /** A failed transaction propagates, thus the shared consumer requests the broker's delayed retry. */
    static QueueConsumer.Settlement settle(Function<byte[], ResultOutcome> handler, byte[] body) {
        ResultOutcome outcome;
        try {
            outcome = Objects.requireNonNull(handler.apply(body), "outcome");
        } catch (MalformedResultException malformed) {
            return QueueConsumer.Settlement.reject(malformed.reason());
        }
        if (outcome.disposition() == Disposition.ORPHANED || outcome.disposition() == Disposition.CONFLICT) {
            return QueueConsumer.Settlement.acknowledge("Ledger result " + outcome.messageId() + " for order "
                    + outcome.orderId() + " committed to " + outcome.disposition() + " quarantine: " + outcome.reason());
        }
        return QueueConsumer.Settlement.acknowledge();
    }

    public void start() {
        consumer.start();
    }

    @Override
    public void close() {
        consumer.close();
    }

    /** The consumer shares the CDC broker connection and credential, and it can run without CDC. */
    public record Settings(boolean enabled, BrokerSettings broker) {
        public Settings {
            Objects.requireNonNull(broker, "broker");
        }
    }
}
