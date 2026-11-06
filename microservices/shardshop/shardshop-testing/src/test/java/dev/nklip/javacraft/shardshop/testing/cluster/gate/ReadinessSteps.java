package dev.nklip.javacraft.shardshop.testing.cluster.gate;

import io.cucumber.java.en.Then;
import java.io.IOException;

public final class ReadinessSteps {
    @Then("cluster DNS resolves from every kind node")
    public void dns() throws IOException, InterruptedException { new Readiness().dns(); }

    @Then("the databases have reconciled and serve the required topology")
    public void infrastructure() throws IOException, InterruptedException { new Readiness().infrastructure(); }
}
