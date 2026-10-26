package dev.nklip.javacraft.shardshop.testing.kind;

import io.cucumber.java.en.Then;

public final class KindRecoverySteps {
    @Then("the isolated ordering schema is restored twice without changing security or any database rows")
    public void schema() throws Exception { new KindRecovery().schema(); }
    @Then("the selected restored shards are audited against the permanent ledger snapshot")
    public void audit() throws Exception { new KindRecovery().audit(); }
    @Then("the selected verified original order is recovered without discarding unresolved evidence")
    public void repair() throws Exception { new KindRecovery().repair(); }
    @Then("the selected original order record is exported from its verified backup")
    public void records() throws Exception { new KindRecovery().records(); }
    @Then("an older isolated restore is audited and recovered with original identities")
    public void drill() throws Exception { new KindRecovery().drill(); }
}
