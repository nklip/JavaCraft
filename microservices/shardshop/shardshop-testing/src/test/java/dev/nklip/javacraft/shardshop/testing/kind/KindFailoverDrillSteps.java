package dev.nklip.javacraft.shardshop.testing.kind;

import io.cucumber.java.en.Then;
import java.io.IOException;

public final class KindFailoverDrillSteps {
    @Then("synchronous promotion preserves acknowledged sagas and CDC and refuses an unconfirmed survivor")
    public void synchronousFailover() throws IOException, InterruptedException { new KindFailoverDrill().verify(); }
}
