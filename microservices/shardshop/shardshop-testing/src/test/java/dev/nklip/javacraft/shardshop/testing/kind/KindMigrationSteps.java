package dev.nklip.javacraft.shardshop.testing.kind;

import io.cucumber.java.en.Then;

import java.io.IOException;

public final class KindMigrationSteps {
    private final KindMigration migration = new KindMigration();

    @Then("the migration topology reservation matches the invocation inventory")
    public void reservationMatches() throws IOException, InterruptedException { migration.reservation(); }

    @Then("every inventory publication has applied its current generation")
    public void publicationsApplied() throws IOException, InterruptedException { migration.publications(); }

    @Then("no schema migration Job is active pending or terminating")
    public void migrationsIdle() throws IOException, InterruptedException { migration.idle(); }

    @Then("no explicit drill holds or retains the shared drill lock")
    public void drillsIdle() throws IOException, InterruptedException { KindDrillGuard.requireIdle(new KindLab()); }
}
