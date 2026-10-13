package dev.nklip.javacraft.shardshop.testing.kind;

import io.cucumber.java.en.Then;
import java.io.IOException;

public final class KindDeploymentSteps {
    @Then("the deployed product workload has the seeded catalog and HTTP-only reader")
    public void deployed() throws IOException, InterruptedException { new KindDeployment().deployed(); }

    @Then("both product pods receive reads and the reader reports a fresh clean interval")
    public void workload() throws IOException, InterruptedException { new KindDeployment().workload(); }
}
