package dev.nklip.javacraft.shardshop.ledger;

import dev.nklip.javacraft.shardshop.ledger.messaging.LedgerCommandConsumer;
import dev.nklip.javacraft.shardshop.ledger.result.LedgerResultRelay;
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
        assertEquals(0, new LedgerApplication(consumer, relay).run("--check-startup"));
        verifyNoInteractions(consumer, relay);
    }

    @Test
    void normalLifetimeStartsAndClosesBothWorkersEvenWhenWaitingFails() {
        var consumer = mock(LedgerCommandConsumer.class);
        var relay = mock(LedgerResultRelay.class);
        try (var quarkus = mockStatic(Quarkus.class)) {
            quarkus.when(Quarkus::waitForExit).thenThrow(new IllegalStateException("stopping"));
            assertThrows(IllegalStateException.class, () -> new LedgerApplication(consumer, relay).run());
            var sequence = inOrder(consumer, relay);
            sequence.verify(consumer).start();
            sequence.verify(relay).start();
            sequence.verify(consumer).close();
            sequence.verify(relay).close();
        }
    }
}
