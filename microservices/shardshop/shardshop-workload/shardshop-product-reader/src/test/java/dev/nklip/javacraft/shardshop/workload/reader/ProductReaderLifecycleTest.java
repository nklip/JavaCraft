package dev.nklip.javacraft.shardshop.workload.reader;

import dev.nklip.javacraft.shardshop.workload.reader.load.ProductReader;
import dev.nklip.javacraft.shardshop.workload.reader.load.ReaderException;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProductReaderLifecycleTest {
    private final ProductReader reader = mock();
    private final Runnable waitForExit = mock();
    private final Runnable exitAfterFailure = mock();
    private final ProductReaderApplication application =
            new ProductReaderApplication(reader, waitForExit, exitAfterFailure);

    @Test
    void checksStartupWithoutReading() {
        assertEquals(0, application.run("--check-startup"));
        assertEquals(0, new ProductReaderApplication(reader).run("--check-startup"));
        verifyNoInteractions(reader, waitForExit);
    }

    @Test
    void readsUntilExitAndThenStops() throws Exception {
        assertEquals(0, application.run());
        InOrder order = inOrder(reader, waitForExit);
        order.verify(reader).start(exitAfterFailure);
        order.verify(waitForExit).run();
        order.verify(reader).stop();
    }

    @Test
    void exitsWithFailureAfterAFailedWorker() {
        when(reader.failed()).thenReturn(true);
        assertEquals(1, application.run("--unknown"));
        verify(reader).stop();
    }

    @Test
    void exitsWithFailureWhenTheCatalogCannotBeResolved() throws Exception {
        doThrow(new ReaderException("Look up seller key failed with NOT_FOUND (attempts: 1)"))
                .when(reader).start(exitAfterFailure);
        assertEquals(1, application.run());
        verify(reader).stop();
        verifyNoInteractions(waitForExit);
    }

    @Test
    void reportsInterruptionAsFailureAndKeepsTheInterruptFlag() throws Exception {
        doThrow(new InterruptedException()).when(reader).start(exitAfterFailure);
        assertEquals(1, application.run());
        assertTrue(Thread.interrupted());
        assertFalse(Thread.currentThread().isInterrupted());
        verify(reader).stop();
    }

    @Test
    void stopsTheReaderWhenStartupFailsUnexpectedly() throws Exception {
        IllegalStateException failure = new IllegalStateException("A catalog lookup failed unexpectedly");
        doThrow(failure).when(reader).start(exitAfterFailure);
        assertEquals(failure, assertThrows(IllegalStateException.class, application::run));
        verify(reader).stop();
    }
}
