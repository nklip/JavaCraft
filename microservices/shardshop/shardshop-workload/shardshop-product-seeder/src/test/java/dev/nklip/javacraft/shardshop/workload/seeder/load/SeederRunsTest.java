package dev.nklip.javacraft.shardshop.workload.seeder.load;

import dev.nklip.javacraft.shardshop.workload.control.ControlFailure;
import dev.nklip.javacraft.shardshop.workload.control.ControlStatus;
import dev.nklip.javacraft.shardshop.workload.control.WorkloadControl;
import dev.nklip.javacraft.shardshop.workload.dataset.CatalogDataset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Timeout(10)
class SeederRunsTest {
    private final CatalogSeeder seeder = mock();
    private final WorkloadControl control = new WorkloadControl(CatalogSeeder.controlSettings(new BigDecimal("100")),
            WorkloadControl.Phase.IDLE);
    private final SeederRuns runs;

    SeederRunsTest() {
        when(seeder.plannedOperations()).thenReturn(1056L);
        runs = new SeederRuns(seeder, control);
    }

    @AfterEach
    void stopRuns() {
        runs.stop();
    }

    @Test
    void startsOneRunAtATimeAndShowsItsProgressAndSummary() throws Exception {
        assertEquals("IDLE", control.status().state());
        assertNull(control.status().run(), "No run before the operator starts one");
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SeedReport report = new SeedReport(CatalogDataset.VERSION, List.of(), List.of());
        when(seeder.seed(any())).thenAnswer(call -> {
            Runnable completed = call.getArgument(0);
            completed.run();
            completed.run();
            running.countDown();
            release.await();
            return report;
        });
        ControlStatus started = runs.start();
        assertEquals("RUNNING", started.state());
        assertEquals(1, started.run().number());
        assertTrue(running.await(5, TimeUnit.SECONDS));
        ControlStatus.Run progress = control.status().run();
        assertEquals("RUNNING", progress.state());
        assertEquals(2, progress.completedOperations());
        assertEquals(1056, progress.plannedOperations());
        assertNull(progress.finishedAt());
        assertSame(ControlFailure.Reason.RUN_ACTIVE, assertThrows(ControlFailure.class, runs::start).reason());

        release.countDown();
        awaitUntil(() -> control.status().state().equals("COMPLETED"));
        ControlStatus.Run done = control.status().run();
        assertEquals("COMPLETED", done.state());
        assertEquals(report.summary(), done.summary());
        assertNull(done.error());
        assertTrue(!Instant.parse(done.finishedAt()).isBefore(Instant.parse(done.startedAt())));

        assertEquals(2, runs.start().run().number(), "A finished run permits the next one");
        awaitUntil(() -> control.status().state().equals("COMPLETED"));
        verify(seeder, times(2)).seed(any());
    }

    @Test
    void aFailedRunShowsTheOperationAndStatusWithoutPayloads() throws Exception {
        when(seeder.seed(any())).thenThrow(new SeedingException(
                "Create seller catalog-v2.US.seller.1 failed on attempt 5: HTTP 503 CATALOG_UNAVAILABLE", null));
        runs.start();
        awaitUntil(() -> control.status().state().equals("FAILED"));
        ControlStatus.Run failed = control.status().run();
        assertEquals("FAILED", failed.state());
        assertEquals("Create seller catalog-v2.US.seller.1 failed on attempt 5: HTTP 503 CATALOG_UNAVAILABLE",
                failed.error());
        assertNull(failed.summary());
        assertNotNull(failed.finishedAt());
    }

    @Test
    void stopInterruptsTheRunAndRejectsLaterStarts() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        when(seeder.seed(any())).thenAnswer(call -> {
            running.countDown();
            new CountDownLatch(1).await();
            return null;
        });
        runs.start();
        assertTrue(running.await(5, TimeUnit.SECONDS));
        runs.stop();
        assertEquals("Seed run was stopped", control.status().run().error());
        assertSame(ControlFailure.Reason.RUN_ACTIVE, assertThrows(ControlFailure.class, runs::start).reason());
    }

    @Test
    void anUnexpectedFailureNamesOnlyItsType() throws Exception {
        when(seeder.seed(any())).thenThrow(new IllegalStateException("private detail"));
        runs.start();
        awaitUntil(() -> control.status().state().equals("FAILED"));
        assertEquals("Unexpected IllegalStateException", control.status().run().error());
    }

    private static void awaitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() - deadline < 0, "Condition not reached within 5 seconds");
            Thread.sleep(10);
        }
    }
}
