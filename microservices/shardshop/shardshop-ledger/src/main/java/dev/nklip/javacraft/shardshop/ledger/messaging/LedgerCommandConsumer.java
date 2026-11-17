package dev.nklip.javacraft.shardshop.ledger.messaging;

import dev.nklip.javacraft.shardshop.common.amqp.BrokerSettings;
import dev.nklip.javacraft.shardshop.common.amqp.QueueConsumer;
import dev.nklip.javacraft.shardshop.ledger.command.CommandDisposition;
import dev.nklip.javacraft.shardshop.ledger.command.CommandOutcome;
import dev.nklip.javacraft.shardshop.ledger.command.MalformedCommandException;

import java.util.Objects;
import java.util.function.Function;

/** Binds ledger.record-order to the shared consumer of common; this class maps command outcomes to settlements. */
public final class LedgerCommandConsumer implements AutoCloseable {
    static final QueueConsumer.Source SOURCE = new QueueConsumer.Source("ledger.record-order", "Ledger command",
            "ledger command", "ledger-command-consumer");
    private final QueueConsumer consumer;

    public LedgerCommandConsumer(Settings settings, Function<byte[], CommandOutcome> handler) {
        Objects.requireNonNull(handler, "handler");
        consumer = new QueueConsumer(settings.broker(), settings.enabled(), SOURCE, settings.concurrency(),
                body -> settle(handler, body));
    }

    /** A failed transaction propagates, thus the shared consumer requests the broker's delayed retry. */
    static QueueConsumer.Settlement settle(Function<byte[], CommandOutcome> handler, byte[] body) {
        CommandOutcome outcome;
        try {
            outcome = Objects.requireNonNull(handler.apply(body), "outcome");
        } catch (MalformedCommandException malformed) {
            return QueueConsumer.Settlement.reject(malformed.reason());
        }
        if (outcome.disposition() == CommandDisposition.QUARANTINED) {
            return QueueConsumer.Settlement.acknowledge("Conflicting ledger command " + outcome.messageId()
                    + " for order " + outcome.orderId() + " committed to quarantine: " + outcome.conflict());
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

    public record Settings(boolean enabled, BrokerSettings broker, int concurrency) {
        public Settings(boolean enabled, BrokerSettings broker) {
            this(enabled, broker, 1);
        }

        public Settings {
            Objects.requireNonNull(broker, "broker");
            validateConcurrency(concurrency);
        }

        public static void validateConcurrency(int concurrency) {
            if (concurrency < 1 || concurrency > QueueConsumer.MAX_CONCURRENCY) {
                throw new IllegalArgumentException("shardshop.ledger.consumer-concurrency must be between 1 and "
                        + QueueConsumer.MAX_CONCURRENCY);
            }
        }
    }
}
