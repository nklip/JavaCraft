package dev.nklip.javacraft.shardshop.testing.cluster.gate;

import dev.nklip.javacraft.shardshop.testing.cluster.Deployment;
import dev.nklip.javacraft.shardshop.testing.cluster.Saga;
import io.cucumber.java.en.Then;
import java.io.IOException;

public final class DeploymentSteps {
    @Then("the deployed product workload has the seeded catalog and HTTP-only reader")
    public void deployed() throws IOException, InterruptedException { new Deployment().deployed(); }

    @Then("both product pods receive reads and the reader reports a fresh clean interval")
    public void workload() throws IOException, InterruptedException { new Deployment().workload(); }

    @Then("two order HTTP replicas, the saga, ledger and HTTP-only producer are deployed with durable CDC offsets")
    public void sagaDeployed() throws IOException, InterruptedException { new Saga().deployed(); }

    @Then("the gate's own USD and EUR orders are confirmed with ledger, stock and seller profit effects")
    public void sagaOrders() throws IOException, InterruptedException { new Saga().ownOrders(); }
}
