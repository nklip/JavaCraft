package dev.nklip.javacraft.shardshop.testing.kind;

import io.cucumber.java.en.Then;

import java.io.IOException;

public final class KindSafetySteps {
    private final KindSafety safety = new KindSafety();

    @Then("the kind creation configuration is supported")
    public void configurationIsSupported() throws IOException {
        safety.config();
    }

    @Then("the retained kind nodes match the creation configuration")
    public void retainedNodesMatch() throws IOException, InterruptedException {
        safety.nodes();
    }

    @Then("the retained topology and allocator permit infrastructure reconciliation")
    public void retentionPermitsReconciliation() throws IOException, InterruptedException {
        safety.retention();
    }

    @Then("the generator admission policy protects fresh initialization")
    public void admissionProtectsInitialization() throws IOException, InterruptedException {
        safety.bootstrapAdmission();
    }

    @Then("the retained generator state and admission policy are valid")
    public void allocatorIsValid() throws IOException, InterruptedException {
        safety.allocator();
    }
}
