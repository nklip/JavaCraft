package dev.nklip.javacraft.shardshop.workload.reader;

import dev.nklip.javacraft.shardshop.workload.reader.config.CatalogWait;
import dev.nklip.javacraft.shardshop.workload.reader.load.CatalogNotReadyException;
import dev.nklip.javacraft.shardshop.workload.reader.load.ProductReader;
import dev.nklip.javacraft.shardshop.workload.reader.load.ReaderException;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProductReaderLifecycleTest {
    private final ProductReader reader = mock();
    private final CatalogWait catalogWait = mock();
    private final ProductReader.Catalog catalog = mock();
    private final Runnable waitForExit = mock();
    private final Runnable exitAfterFailure = mock();
    private final ProductReaderApplication application =
            new ProductReaderApplication(reader, catalogWait, waitForExit, exitAfterFailure);

    @Test
    void checksStartupWithoutReading() {
        assertEquals(0, application.run("--check-startup"));
        assertEquals(0, new ProductReaderApplication(reader, catalogWait).run("--check-startup"));
        verifyNoInteractions(reader, catalogWait, waitForExit);
    }

    @Test
    void startsLoadOnlyAfterTheCatalogWaitAndStopsAfterExit() throws Exception {
        when(catalogWait.resolve(reader)).thenReturn(catalog);
        assertEquals(0, application.run());
        InOrder order = inOrder(catalogWait, reader, waitForExit);
        order.verify(catalogWait).resolve(reader);
        order.verify(reader).start(catalog, exitAfterFailure);
        order.verify(waitForExit).run();
        order.verify(reader).stop();
    }

    @Test
    void exitsWithFailureAfterAFailedWorker() throws Exception {
        when(catalogWait.resolve(reader)).thenReturn(catalog);
        when(reader.failed()).thenReturn(true);
        assertEquals(1, application.run("--unknown"));
        verify(reader).stop();
    }

    @Test
    void exitsWithFailureWhenTheCatalogIsNotCompleteWithinTheWait() throws Exception {
        when(catalogWait.resolve(reader)).thenThrow(
                new CatalogNotReadyException("Look up seller key failed with NOT_FOUND"));
        assertEquals(1, application.run());
        verify(reader, never()).start(any(), any());
        verify(reader).stop();
        verifyNoInteractions(waitForExit);
    }

    @Test
    void exitsWithFailureWhenTheCatalogCannotBeResolved() throws Exception {
        when(catalogWait.resolve(reader)).thenThrow(
                new ReaderException("Look up seller key failed with INVALID_RESPONSE"));
        assertEquals(1, application.run());
        verify(reader, never()).start(any(), any());
        verify(reader).stop();
        verifyNoInteractions(waitForExit);
    }

    @Test
    void reportsInterruptionAsFailureAndKeepsTheInterruptFlag() throws Exception {
        when(catalogWait.resolve(reader)).thenThrow(new InterruptedException());
        assertEquals(1, application.run());
        assertTrue(Thread.interrupted());
        assertFalse(Thread.currentThread().isInterrupted());
        verify(reader).stop();
    }

    @Test
    void stopsTheReaderWhenStartupFailsUnexpectedly() throws Exception {
        IllegalStateException failure = new IllegalStateException("A catalog lookup failed unexpectedly");
        when(catalogWait.resolve(reader)).thenThrow(failure);
        assertEquals(failure, assertThrows(IllegalStateException.class, application::run));
        verify(reader).stop();
    }
}
