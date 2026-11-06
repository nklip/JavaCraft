package dev.nklip.javacraft.shardshop.testing.cluster.drill;

import io.cucumber.java.en.Then;

import java.io.IOException;

/** Disruptive step 5.4 scenarios, selected only by the explicit kind drill tags. */
public final class RetryDrillSteps {
    @Then("a shard outage parks its result and reconciliation recovers the order exactly once")
    public void recovery() throws IOException, InterruptedException {
        new RetryRecoveryDrill().verify();
    }

    @Then("a {string} DLQ retains source pressure on {string} until its application outbox recovers")
    public void capacity(String mode, String queue) throws IOException, InterruptedException {
        new DlqCapacityDrill().verify(mode, queue);
    }
}
