package dev.nklip.javacraft.shardshop.ledger;

import dev.nklip.javacraft.shardshop.ledger.messaging.LedgerCommandConsumer;
import dev.nklip.javacraft.shardshop.ledger.result.LedgerResultRelay;
import dev.nklip.javacraft.shardshop.ledger.cleanup.LedgerTransportCleanup;
import dev.nklip.javacraft.shardshop.ledger.metrics.LedgerMetrics;
import io.quarkus.runtime.Quarkus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class LedgerLifecycleTest {
    @Test
    void startupCheckDoesNotStartWorkers() {
        var consumer = mock(LedgerCommandConsumer.class);
        var relay = mock(LedgerResultRelay.class);
        var cleanup = mock(LedgerTransportCleanup.class);
        var metrics = mock(LedgerMetrics.class);
        assertEquals(0, new LedgerApplication(consumer, relay, cleanup, metrics).run("--check-startup"));
        verifyNoInteractions(consumer, relay, cleanup, metrics);
    }

    @Test
    void normalLifetimeStartsAndClosesBothWorkersEvenWhenWaitingFails() {
        var consumer = mock(LedgerCommandConsumer.class);
        var relay = mock(LedgerResultRelay.class);
        var cleanup = mock(LedgerTransportCleanup.class);
        var metrics = mock(LedgerMetrics.class);
        try (var quarkus = mockStatic(Quarkus.class)) {
            quarkus.when(Quarkus::waitForExit).thenThrow(new IllegalStateException("stopping"));
            assertThrows(IllegalStateException.class, () -> new LedgerApplication(consumer, relay, cleanup, metrics).run());
            var sequence = inOrder(consumer, relay, cleanup, metrics);
            sequence.verify(consumer).start();
            sequence.verify(relay).start();
            sequence.verify(cleanup).start();
            sequence.verify(metrics).start();
            sequence.verify(consumer).close();
            sequence.verify(relay).close();
            sequence.verify(cleanup).close();
            sequence.verify(metrics).close();
        }
    }
}
