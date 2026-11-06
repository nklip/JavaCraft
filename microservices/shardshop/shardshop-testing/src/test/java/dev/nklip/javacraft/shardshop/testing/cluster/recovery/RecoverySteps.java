package dev.nklip.javacraft.shardshop.testing.cluster.recovery;

import io.cucumber.java.en.Then;

public final class RecoverySteps {
    @Then("the isolated ordering schema is restored twice without changing security or any database rows")
    public void schema() throws Exception { new Recovery().schema(); }
    @Then("the selected restored shards are audited against the permanent ledger snapshot")
    public void audit() throws Exception { new Recovery().audit(); }
    @Then("the selected verified original order is recovered without discarding unresolved evidence")
    public void repair() throws Exception { new Recovery().repair(); }
    @Then("the selected original order record is exported from its verified backup")
    public void records() throws Exception { new Recovery().records(); }
    @Then("an older isolated restore is audited and recovered with original identities")
    public void drill() throws Exception { new Recovery().drill(); }
}
