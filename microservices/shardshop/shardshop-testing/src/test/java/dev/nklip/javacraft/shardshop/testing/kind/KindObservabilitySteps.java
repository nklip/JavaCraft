package dev.nklip.javacraft.shardshop.testing.kind;

import io.cucumber.java.en.Then;

import java.io.IOException;

public final class KindObservabilitySteps {
    @Then("the deployed applications, broker and databases expose fresh bounded observability")
    public void observability() throws IOException, InterruptedException { new KindObservability().verify(); }
}
