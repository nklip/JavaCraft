package dev.nklip.javacraft.shardshop.ledger;

import dev.nklip.javacraft.shardshop.ledger.messaging.LedgerCommandConsumer;
import io.quarkus.runtime.Quarkus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class LedgerLifecycleTest {
    @Test
    void startupCheckDoesNotStartConsumer() {
        var consumer = mock(LedgerCommandConsumer.class);
        assertEquals(0, new LedgerApplication(consumer).run("--check-startup"));
        verifyNoInteractions(consumer);
    }

    @Test
    void normalLifetimeStartsAndClosesConsumerEvenWhenWaitingFails() {
        var consumer = mock(LedgerCommandConsumer.class);
        try (var quarkus = mockStatic(Quarkus.class)) {
            quarkus.when(Quarkus::waitForExit).thenThrow(new IllegalStateException("stopping"));
            assertThrows(IllegalStateException.class, () -> new LedgerApplication(consumer).run());
            var sequence = inOrder(consumer);
            sequence.verify(consumer).start();
            sequence.verify(consumer).close();
        }
    }
}
