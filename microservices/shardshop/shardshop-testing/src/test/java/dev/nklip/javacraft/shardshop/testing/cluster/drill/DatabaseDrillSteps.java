package dev.nklip.javacraft.shardshop.testing.cluster.drill;

import io.cucumber.java.en.Then;
import java.io.IOException;

public final class DatabaseDrillSteps {
    @Then("the selected inventory shard survives the reversible database quorum drill")
    public void databaseDrill() throws IOException, InterruptedException { new DatabaseDrill().verify(); }
}
