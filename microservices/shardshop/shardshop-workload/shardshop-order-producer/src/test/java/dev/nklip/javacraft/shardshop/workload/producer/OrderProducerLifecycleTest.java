package dev.nklip.javacraft.shardshop.workload.producer;

import dev.nklip.javacraft.shardshop.workload.producer.load.OrderProducer;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderProducerLifecycleTest {
    private final OrderProducer producer = mock();

    @Test
    void startupCheckDoesNotStartHttpWork() {
        var application = new OrderProducerApplication(producer, () -> fail("must not wait"), () -> fail("must not exit"));
        assertEquals(0, application.run("--check-startup"));
        verifyNoInteractions(producer);
    }

    @Test
    void workerFailureExitsWithFailureAndAlwaysStops() {
        AtomicBoolean exit = new AtomicBoolean();
        doAnswer(call -> {
            ((Runnable) call.getArgument(0)).run();
            return null;
        }).when(producer).start(any());
        when(producer.failed()).thenReturn(true);
        var application = new OrderProducerApplication(producer, () -> assertTrue(exit.get()), () -> exit.set(true));
        assertEquals(1, application.run());
        verify(producer).stop();
    }

    @Test
    void normalShutdownStopsWorkersAndReturnsSuccess() {
        Runnable waitForExit = mock();
        var application = new OrderProducerApplication(producer, waitForExit, () -> fail("unexpected failure"));
        assertEquals(0, application.run());
        verify(waitForExit).run();
        verify(producer).stop();
    }
}
