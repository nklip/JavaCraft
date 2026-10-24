package dev.nklip.javacraft.shardshop.ledger;

import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.annotations.QuarkusMain;
import dev.nklip.javacraft.shardshop.ledger.messaging.LedgerCommandConsumer;
import dev.nklip.javacraft.shardshop.ledger.result.LedgerResultRelay;
import dev.nklip.javacraft.shardshop.ledger.cleanup.LedgerTransportCleanup;
import dev.nklip.javacraft.shardshop.ledger.metrics.LedgerMetrics;

@QuarkusMain
public class LedgerApplication implements QuarkusApplication {
    private final LedgerCommandConsumer consumer;
    private final LedgerResultRelay relay;
    private final LedgerTransportCleanup cleanup;
    private final LedgerMetrics metrics;

    public LedgerApplication(LedgerCommandConsumer consumer, LedgerResultRelay relay,
                             LedgerTransportCleanup cleanup, LedgerMetrics metrics) {
        this.consumer = consumer;
        this.relay = relay;
        this.cleanup = cleanup;
        this.metrics = metrics;
    }

    @Override
    public int run(String... args) {
        if (args.length == 1 && "--check-startup".equals(args[0])) {
            return 0;
        }
        try {
            consumer.start();
            relay.start();
            cleanup.start();
            metrics.start();
            Quarkus.waitForExit();
        } finally {
            try {
                consumer.close();
            } finally {
                try {
                    relay.close();
                } finally {
                    try {
                        cleanup.close();
                    } finally {
                        metrics.close();
                    }
                }
            }
        }
        return 0;
    }
}
