package dev.nklip.javacraft.shardshop.workload.seeder.load;

import dev.nklip.javacraft.shardshop.workload.control.ControlFailure;
import dev.nklip.javacraft.shardshop.workload.control.ControlStatus;
import dev.nklip.javacraft.shardshop.workload.control.WorkloadControl;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Operator-started finite seed runs of the interactive seeder, one at a time. Each run repeats the dataset keys and
 * payloads, thus a rerun recovers the same IDs and changes no stock or profit. A run has no deadline of its own: each
 * HTTP attempt keeps its timeout and retry budget, and a paused run waits until Resume.
 */
public final class SeederRuns {
    private static final Logger LOG = Logger.getLogger(SeederRuns.class);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(5);

    private final CatalogSeeder seeder;
    private final WorkloadControl control;
    private long number;
    private Run current;
    private Thread worker;
    private boolean stopped;

    public SeederRuns(CatalogSeeder seeder, WorkloadControl control) {
        this.seeder = Objects.requireNonNull(seeder);
        this.control = Objects.requireNonNull(control);
        control.run(this::view);
    }

    /** Starts the next run, or {@link ControlFailure} with {@code RUN_ACTIVE} while a run is in progress. */
    public ControlStatus start() {
        long started;
        synchronized (this) {
            if (stopped || current != null && current.finishedAt == null) {
                throw new ControlFailure(ControlFailure.Reason.RUN_ACTIVE);
            }
            Run run = new Run(++number, Instant.now(), seeder.plannedOperations());
            current = run;
            control.phase(WorkloadControl.Phase.ACTIVE);
            worker = Thread.ofVirtual().name("product-seeder-run-" + run.number).start(() -> execute(run));
            started = run.number;
        }
        LOG.infof("Seed run %d started", started);
        return control.status();
    }

    /** Interrupts a run in progress at shutdown; later starts fail. */
    public void stop() {
        Thread running;
        synchronized (this) {
            stopped = true;
            running = worker;
        }
        if (running != null && running != Thread.currentThread()) {
            running.interrupt();
            try {
                running.join(STOP_TIMEOUT);
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void execute(Run run) {
        try {
            SeedReport report = seeder.seed(run.completed::incrementAndGet);
            LOG.info(report.summary());
            finish(run, WorkloadControl.Phase.COMPLETED, report.summary(), null);
        } catch (SeedingException failure) {
            LOG.error("Catalog seeding failed: " + failure.getMessage());
            finish(run, WorkloadControl.Phase.FAILED, null, failure.getMessage());
        } catch (InterruptedException interruption) {
            finish(run, WorkloadControl.Phase.FAILED, null, "Seed run was stopped");
        } catch (RuntimeException failure) {
            LOG.error("Catalog seeding failed unexpectedly: " + failure.getClass().getSimpleName());
            finish(run, WorkloadControl.Phase.FAILED, null, "Unexpected " + failure.getClass().getSimpleName());
        }
    }

    private synchronized void finish(Run run, WorkloadControl.Phase phase, String summary, String error) {
        run.summary = summary;
        run.error = error;
        run.finishedAt = Instant.now();
        control.phase(phase);
    }

    private synchronized ControlStatus.Run view() {
        Run run = current;
        if (run == null) {
            return null;
        }
        String state = run.finishedAt == null ? "RUNNING" : run.error == null ? "COMPLETED" : "FAILED";
        return new ControlStatus.Run(run.number, state, run.startedAt.toString(),
                run.finishedAt == null ? null : run.finishedAt.toString(), run.completed.get(), run.planned,
                run.summary, run.error);
    }

    private static final class Run {
        private final long number;
        private final Instant startedAt;
        private final long planned;
        private final AtomicLong completed = new AtomicLong();
        private Instant finishedAt;
        private String summary;
        private String error;

        private Run(long number, Instant startedAt, long planned) {
            this.number = number;
            this.startedAt = startedAt;
            this.planned = planned;
        }
    }
}
