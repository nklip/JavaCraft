package dev.nklip.javacraft.shardshop.testing.cluster.gate;

import dev.nklip.javacraft.shardshop.testing.cluster.ClusterLab;
import dev.nklip.javacraft.shardshop.testing.cluster.DrillGuard;
import io.cucumber.java.en.Then;

import java.io.IOException;

public final class MigrationSteps {
    private final Migration migration = new Migration();

    @Then("the migration topology reservation matches the invocation inventory")
    public void reservationMatches() throws IOException, InterruptedException { migration.reservation(); }

    @Then("every inventory publication has applied its current generation")
    public void publicationsApplied() throws IOException, InterruptedException { migration.publications(); }

    @Then("no schema migration Job is active pending or terminating")
    public void migrationsIdle() throws IOException, InterruptedException { migration.idle(); }

    @Then("no explicit drill holds or retains the shared drill lock")
    public void drillsIdle() throws IOException, InterruptedException { DrillGuard.requireIdle(new ClusterLab()); }
}
