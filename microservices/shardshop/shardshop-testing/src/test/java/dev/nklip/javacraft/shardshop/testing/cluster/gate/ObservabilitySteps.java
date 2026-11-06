package dev.nklip.javacraft.shardshop.testing.cluster.gate;

import io.cucumber.java.en.Then;

import java.io.IOException;

public final class ObservabilitySteps {
    @Then("the deployed applications, broker and databases expose fresh bounded observability")
    public void observability() throws IOException, InterruptedException { new Observability().verify(); }
}
