package dev.nklip.javacraft.shardshop.workload.seeder;

import dev.nklip.javacraft.shardshop.workload.control.WorkloadControl;
import dev.nklip.javacraft.shardshop.workload.seeder.config.SeederConfiguration;
import dev.nklip.javacraft.shardshop.workload.seeder.load.CatalogSeeder;
import dev.nklip.javacraft.shardshop.workload.seeder.load.SeedingException;
import dev.nklip.javacraft.shardshop.workload.seeder.load.SeederRuns;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Objects;

/**
 * Finite Job entry point: exit code 0 only after the complete catalog is seeded and verified. In the interactive mode,
 * the process serves its control page and starts seed runs only on operator request, until it stops.
 */
@QuarkusMain
public class ProductSeederApplication implements QuarkusApplication {
    private static final Logger LOG = Logger.getLogger(ProductSeederApplication.class);

    private final CatalogSeeder seeder;
    private final WorkloadControl control;
    private final SeederRuns runs;
    private final boolean interactive;
    private final Runnable waitForExit;

    @Inject
    public ProductSeederApplication(CatalogSeeder seeder, WorkloadControl control, SeederRuns runs,
                                    SeederConfiguration config) {
        this(seeder, control, runs, config.interactive(), Quarkus::waitForExit);
    }

    ProductSeederApplication(CatalogSeeder seeder, WorkloadControl control, SeederRuns runs, boolean interactive,
                             Runnable waitForExit) {
        this.seeder = Objects.requireNonNull(seeder);
        this.control = Objects.requireNonNull(control);
        this.runs = Objects.requireNonNull(runs);
        this.interactive = interactive;
        this.waitForExit = Objects.requireNonNull(waitForExit);
    }

    @Override
    public int run(String... args) {
        if (args.length == 1 && "--check-startup".equals(args[0])) {
            return 0;
        }
        if (interactive) {
            LOG.info("Interactive product seeder ready; seed runs start only on operator request");
            try {
                waitForExit.run();
            } finally {
                runs.stop();
            }
            return 0;
        }
        try {
            control.phase(WorkloadControl.Phase.ACTIVE);
            LOG.info(seeder.seed().summary());
            control.phase(WorkloadControl.Phase.COMPLETED);
            return 0;
        } catch (SeedingException failure) {
            control.phase(WorkloadControl.Phase.FAILED);
            LOG.error("Catalog seeding failed: " + failure.getMessage());
            return 1;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            control.phase(WorkloadControl.Phase.FAILED);
            LOG.error("Catalog seeding was interrupted");
            return 1;
        }
    }
}
