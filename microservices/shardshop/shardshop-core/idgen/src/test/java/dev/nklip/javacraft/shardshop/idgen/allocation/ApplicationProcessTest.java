package dev.nklip.javacraft.shardshop.idgen.allocation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Timeout(10)
class ApplicationProcessTest {

    private final ApplicationProcess.ProcessStarter starter = mock(ApplicationProcess.ProcessStarter.class);
    private final Process child = mock(Process.class);
    private final AtomicReference<Thread> shutdown = new AtomicReference<>();
    private final AtomicReference<Thread> removed = new AtomicReference<>();
    private final List<String> command = List.of("java", "-jar", "quarkus-run.jar");

    @Test
    void inheritsStreamsAndUsesOnlyTheProvidedEnvironmentWhilePreservingTheExitCode() throws Exception {
        when(starter.start(any())).thenReturn(child);
        when(child.waitFor()).thenReturn(23);

        assertEquals(23, process().run(command, Map.of("SAFE_SETTING", "value")));

        ArgumentCaptor<ProcessBuilder> builders = ArgumentCaptor.forClass(ProcessBuilder.class);
        verify(starter).start(builders.capture());
        assertEquals(command, builders.getValue().command());
        assertEquals(Map.of("SAFE_SETTING", "value"), builders.getValue().environment());
        assertEquals(ProcessBuilder.Redirect.INHERIT, builders.getValue().redirectInput());
        assertEquals(ProcessBuilder.Redirect.INHERIT, builders.getValue().redirectOutput());
        assertEquals(ProcessBuilder.Redirect.INHERIT, builders.getValue().redirectError());
        assertSame(shutdown.get(), removed.get());
        verify(child, never()).destroy();
    }

    @Test
    void forkingFailureRemovesItsShutdownHook() throws Exception {
        IOException failure = new IOException("cannot start");
        when(starter.start(any())).thenThrow(failure);

        assertSame(failure, assertThrows(IOException.class, () -> process().run(command, Map.of())));
        assertSame(shutdown.get(), removed.get());
        verifyNoInteractions(child);
    }

    @Test
    void shutdownForwardsTerminationAndAllowsGracefulExit() throws Exception {
        when(starter.start(any())).thenReturn(child);
        when(child.waitFor(10, TimeUnit.SECONDS)).thenReturn(true);
        when(child.waitFor()).thenAnswer(invocation -> {
            shutdown.get().run();
            return 143;
        });

        assertEquals(143, process().run(command, Map.of()));

        verify(child).destroy();
        verify(child, never()).destroyForcibly();
        assertSame(shutdown.get(), removed.get());
    }

    @Test
    void shutdownKillsAChildThatDoesNotExitWithinTheGracePeriod() throws Exception {
        when(starter.start(any())).thenReturn(child);
        when(child.waitFor(10, TimeUnit.SECONDS)).thenReturn(false);
        when(child.destroyForcibly()).thenReturn(child);
        when(child.waitFor()).thenAnswer(invocation -> {
            shutdown.get().run();
            return 137;
        });

        assertEquals(137, process().run(command, Map.of()));

        verify(child).destroy();
        verify(child).destroyForcibly();
        assertSame(shutdown.get(), removed.get());
    }

    @Test
    void interruptedWaitStopsTheChildBeforePropagating() throws Exception {
        InterruptedException failure = new InterruptedException("interrupted");
        when(starter.start(any())).thenReturn(child);
        when(child.waitFor()).thenThrow(failure);
        when(child.waitFor(10, TimeUnit.SECONDS)).thenReturn(true);

        assertSame(failure, assertThrows(InterruptedException.class, () -> process().run(command, Map.of())));

        verify(child).destroy();
        verify(child, never()).destroyForcibly();
        assertSame(shutdown.get(), removed.get());
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test
    void interruptionDuringTerminationKillsTheChildAndPreservesTheInterrupt() throws Exception {
        when(starter.start(any())).thenReturn(child);
        when(child.waitFor(10, TimeUnit.SECONDS)).thenThrow(new InterruptedException("interrupted"));
        when(child.destroyForcibly()).thenReturn(child);
        when(child.waitFor()).thenAnswer(invocation -> {
            shutdown.get().run();
            return 137;
        });
        try {
            assertEquals(137, process().run(command, Map.of()));
            assertTrue(Thread.currentThread().isInterrupted());
            verify(child).destroyForcibly();
            assertSame(shutdown.get(), removed.get());
        } finally {
            assertTrue(Thread.interrupted());
        }
    }

    @Test
    void shutdownBeforeForkPreventsTheApplicationFromStarting() {
        ApplicationProcess process = new ApplicationProcess(starter, hook -> {
            shutdown.set(hook);
            hook.run();
        }, hook -> removed.compareAndSet(null, hook));

        assertEquals("Application startup cancelled",
                assertThrows(IOException.class, () -> process.run(command, Map.of())).getMessage());

        verifyNoInteractions(starter);
        assertSame(shutdown.get(), removed.get());
    }

    @Test
    void concurrentJvmShutdownStillStopsTheChildWhenTheHookCannotBeRemoved() throws Exception {
        when(starter.start(any())).thenReturn(child);
        when(child.waitFor()).thenReturn(0);
        when(child.waitFor(10, TimeUnit.SECONDS)).thenReturn(true);
        ApplicationProcess process = new ApplicationProcess(starter, shutdown::set, hook -> {
            assertSame(shutdown.get(), hook);
            throw new IllegalStateException("shutdown already started");
        });

        assertEquals(0, process.run(command, Map.of()));
        verify(child).destroy();
        verify(child, never()).destroyForcibly();
    }

    private ApplicationProcess process() {
        return new ApplicationProcess(starter, shutdown::set, hook -> removed.compareAndSet(null, hook));
    }
}
