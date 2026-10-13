package dev.nklip.javacraft.shardshop.testing.kind;

import io.cucumber.java.en.Then;
import java.io.IOException;

public final class KindWorkloadDrillSteps {
    @Then("two temporary HTTP seeders preserve IDs and one replacement product JVM recovers workload traffic")
    public void drill() throws IOException, InterruptedException { new KindWorkloadDrill().repeatAndReplace(); }
}
