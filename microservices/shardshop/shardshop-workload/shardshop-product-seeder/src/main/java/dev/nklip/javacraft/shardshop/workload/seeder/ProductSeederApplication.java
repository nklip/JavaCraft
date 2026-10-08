package dev.nklip.javacraft.shardshop.workload.seeder;

import dev.nklip.javacraft.shardshop.workload.seeder.load.CatalogSeeder;
import dev.nklip.javacraft.shardshop.workload.seeder.load.SeedingException;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/** Finite Job entry point: exit code 0 only after the complete catalog is seeded and verified. */
@QuarkusMain
public class ProductSeederApplication implements QuarkusApplication {
    private static final Logger LOG = Logger.getLogger(ProductSeederApplication.class);

    private final CatalogSeeder seeder;

    @Inject
    public ProductSeederApplication(CatalogSeeder seeder) {
        this.seeder = seeder;
    }

    @Override
    public int run(String... args) {
        if (args.length == 1 && "--check-startup".equals(args[0])) {
            return 0;
        }
        try {
            LOG.info(seeder.seed().summary());
            return 0;
        } catch (SeedingException failure) {
            LOG.error("Catalog seeding failed: " + failure.getMessage());
            return 1;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            LOG.error("Catalog seeding was interrupted");
            return 1;
        }
    }
}
