package dev.nklip.javacraft.shardshop.testing.kind;

import io.cucumber.java.en.Then;
import java.io.IOException;

public final class KindReadinessSteps {
    @Then("cluster DNS resolves from every kind node")
    public void dns() throws IOException, InterruptedException { new KindReadiness().dns(); }

    @Then("the databases have reconciled and serve the required topology")
    public void infrastructure() throws IOException, InterruptedException { new KindReadiness().infrastructure(); }
}
