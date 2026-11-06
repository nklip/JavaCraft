package dev.nklip.javacraft.shardshop.testing.cluster.drill;

import io.cucumber.java.en.Then;
import java.io.IOException;

public final class ReplicaReadDrillSteps {
    @Then("strict replica reads survive lag, standby loss and hung queries and restore primary reads")
    public void strictReads() throws IOException, InterruptedException { new ReplicaReadDrill().verify(); }
}
