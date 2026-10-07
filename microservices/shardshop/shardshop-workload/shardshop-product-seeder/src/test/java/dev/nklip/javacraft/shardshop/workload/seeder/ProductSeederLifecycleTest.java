package dev.nklip.javacraft.shardshop.workload.seeder;

import dev.nklip.javacraft.shardshop.workload.dataset.CatalogDataset;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProductSeederLifecycleTest {
    private final CatalogSeeder seeder = mock();
    private final ProductSeederApplication application = new ProductSeederApplication(seeder);

    @Test
    void checksStartupWithoutSeeding() {
        assertEquals(0, application.run("--check-startup"));
        verifyNoInteractions(seeder);
    }

    @Test
    void exitsSuccessfullyOnlyAfterAVerifiedRun() throws Exception {
        when(seeder.seed()).thenReturn(new SeedReport(CatalogDataset.VERSION, List.of(), List.of()))
                .thenThrow(new SeedingException("Read seller key failed on attempt 1: HTTP 404 SELLER_NOT_FOUND", null));
        assertEquals(0, application.run());
        assertEquals(1, application.run("--unknown"));
        verify(seeder, times(2)).seed();
    }

    @Test
    void reportsInterruptionAsFailureAndKeepsTheInterruptFlag() throws Exception {
        when(seeder.seed()).thenThrow(new InterruptedException());
        assertEquals(1, application.run());
        assertTrue(Thread.interrupted());
        assertFalse(Thread.currentThread().isInterrupted());
    }
}
