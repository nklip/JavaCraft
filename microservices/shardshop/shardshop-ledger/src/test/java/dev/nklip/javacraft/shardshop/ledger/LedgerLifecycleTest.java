package dev.nklip.javacraft.shardshop.ledger;

import dev.nklip.javacraft.shardshop.ledger.messaging.LedgerCommandConsumer;
import dev.nklip.javacraft.shardshop.ledger.result.LedgerResultRelay;
import dev.nklip.javacraft.shardshop.ledger.cleanup.LedgerTransportCleanup;
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
        assertEquals(0, new LedgerApplication(consumer, relay, cleanup).run("--check-startup"));
        verifyNoInteractions(consumer, relay, cleanup);
    }

    @Test
    void normalLifetimeStartsAndClosesBothWorkersEvenWhenWaitingFails() {
        var consumer = mock(LedgerCommandConsumer.class);
        var relay = mock(LedgerResultRelay.class);
        var cleanup = mock(LedgerTransportCleanup.class);
        try (var quarkus = mockStatic(Quarkus.class)) {
            quarkus.when(Quarkus::waitForExit).thenThrow(new IllegalStateException("stopping"));
            assertThrows(IllegalStateException.class, () -> new LedgerApplication(consumer, relay, cleanup).run());
            var sequence = inOrder(consumer, relay, cleanup);
            sequence.verify(consumer).start();
            sequence.verify(relay).start();
            sequence.verify(cleanup).start();
            sequence.verify(consumer).close();
            sequence.verify(relay).close();
            sequence.verify(cleanup).close();
        }
    }
}
