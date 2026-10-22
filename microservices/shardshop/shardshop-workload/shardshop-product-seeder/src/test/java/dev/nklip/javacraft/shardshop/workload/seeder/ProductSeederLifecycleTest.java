package dev.nklip.javacraft.shardshop.workload.seeder;

import dev.nklip.javacraft.shardshop.workload.control.WorkloadControl;
import dev.nklip.javacraft.shardshop.workload.dataset.CatalogDataset;
import dev.nklip.javacraft.shardshop.workload.seeder.load.CatalogSeeder;
import dev.nklip.javacraft.shardshop.workload.seeder.load.SeedReport;
import dev.nklip.javacraft.shardshop.workload.seeder.load.SeederRuns;
import dev.nklip.javacraft.shardshop.workload.seeder.load.SeedingException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
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
    private final SeederRuns runs = mock();
    private final WorkloadControl control = new WorkloadControl(CatalogSeeder.controlSettings(new BigDecimal("100")),
            WorkloadControl.Phase.IDLE);
    private final ProductSeederApplication application = new ProductSeederApplication(seeder, control, runs, false,
            () -> { throw new AssertionError("The Job does not wait"); });

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
        assertEquals("COMPLETED", control.status().state());
        assertEquals(1, application.run("--unknown"));
        assertEquals("FAILED", control.status().state());
        verify(seeder, times(2)).seed();
        verifyNoInteractions(runs);
    }

    @Test
    void theInteractiveModeSeedsOnlyOnRequestAndStopsItsRunAtExit() {
        List<String> waits = new ArrayList<>();
        var interactive = new ProductSeederApplication(seeder, control, runs, true, () -> waits.add("wait"));
        assertEquals(0, interactive.run());
        assertEquals(List.of("wait"), waits);
        assertEquals("IDLE", control.status().state());
        verifyNoInteractions(seeder);
        verify(runs).stop();
    }

    @Test
    void reportsInterruptionAsFailureAndKeepsTheInterruptFlag() throws Exception {
        when(seeder.seed()).thenThrow(new InterruptedException());
        assertEquals(1, application.run());
        assertTrue(Thread.interrupted());
        assertFalse(Thread.currentThread().isInterrupted());
    }
}
