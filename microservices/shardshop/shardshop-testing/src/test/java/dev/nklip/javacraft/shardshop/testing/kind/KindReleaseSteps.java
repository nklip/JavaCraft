package dev.nklip.javacraft.shardshop.testing.kind;

import io.cucumber.java.en.Then;

import java.io.IOException;

public final class KindReleaseSteps {
    @Then("the current application release runs the invocation's qualified images and desired ready pods")
    public void accepted() throws IOException, InterruptedException { new KindRelease().accepted(); }
}
