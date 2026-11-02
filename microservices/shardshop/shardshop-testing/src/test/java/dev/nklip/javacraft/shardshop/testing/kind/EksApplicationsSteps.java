package dev.nklip.javacraft.shardshop.testing.kind;

import io.cucumber.java.en.Then;

import java.io.IOException;

public final class EksApplicationsSteps {
    @Then("the current Argo CD sync applied each application resource after its gate, runs the pinned images and seeded the catalog")
    public void released() throws IOException, InterruptedException { new EksApplications().released(); }

    @Then("resyncs, a held drill lock and syncs without hooks keep the application gates")
    public void drill() throws IOException, InterruptedException { new EksApplications().drill(); }
}
