package dev.nklip.javacraft.shardshop.testing.kind;

import io.cucumber.java.en.Then;
import java.io.IOException;

public final class KindUpgradeSteps {
    @Then("the pre-5.3 lab retains a private upgrade witness")
    public void capture() throws IOException, InterruptedException { new KindUpgradeWitness().capture(); }

    @Then("the upgraded lab preserves the witness and grants the restricted operator")
    public void retained() throws IOException, InterruptedException { new KindUpgradeWitness().retained(); }
    @Then("the retained exhausted order replays once after the operator reset")
    public void replayed() throws IOException, InterruptedException { new KindUpgradeWitness().replayed(); }
}
