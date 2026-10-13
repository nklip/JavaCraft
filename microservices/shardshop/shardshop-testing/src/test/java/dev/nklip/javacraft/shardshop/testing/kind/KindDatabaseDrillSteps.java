package dev.nklip.javacraft.shardshop.testing.kind;

import io.cucumber.java.en.Then;
import java.io.IOException;

public final class KindDatabaseDrillSteps {
    @Then("the selected inventory shard survives the reversible database quorum drill")
    public void databaseDrill() throws IOException, InterruptedException { new KindDatabaseDrill().verify(); }
}
