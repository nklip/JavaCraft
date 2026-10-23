package dev.nklip.javacraft.shardshop.testing.kind;

import io.cucumber.java.en.Then;
import java.io.IOException;

public final class KindWorkloadDrillSteps {
    @Then("two temporary HTTP seeders preserve IDs and one replacement product JVM recovers workload traffic")
    public void drill() throws IOException, InterruptedException { new KindWorkloadDrill().repeatAndReplace(); }
    @Then("scoped saga retries, rejection replay and two orders for the same stock preserve permanent effects")
    public void sagaReplay() throws Exception { new KindSagaDrill().replayAndRace(); }

    @Then("the saga JVM on a different worker resumes CDC while HTTP commits new orders")
    public void sagaRelocate() throws IOException, InterruptedException { new KindSagaDrill().relocateCdc(); }
    @Then("an HTTP rollout preserves the saga JVM and its active CDC slots")
    public void httpRollout() throws IOException, InterruptedException { new KindSagaDrill().rolloutHttp(); }
}
