package dev.nklip.javacraft.shardshop.testing.kind;

import io.cucumber.java.en.Then;
import java.io.IOException;

public final class KindReplicaReadDrillSteps {
    @Then("strict replica reads survive lag, standby loss and hung queries and restore primary reads")
    public void strictReads() throws IOException, InterruptedException { new KindReplicaReadDrill().verify(); }
}
