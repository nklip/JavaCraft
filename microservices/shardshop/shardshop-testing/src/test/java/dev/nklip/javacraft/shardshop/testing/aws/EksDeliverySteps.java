package dev.nklip.javacraft.shardshop.testing.aws;

import io.cucumber.java.en.Then;

import java.io.IOException;

public final class EksDeliverySteps {
    @Then("every database layer comes from a digest-pinned OCI chart and is synced, healthy and protected from deletion")
    public void delivered() throws IOException, InterruptedException { new EksDelivery().delivered(); }

    @Then("resyncs, changed, interrupted and failed migrations keep the delivery contract")
    public void contractDrill() throws IOException, InterruptedException { new EksDelivery().contractDrill(); }
}
