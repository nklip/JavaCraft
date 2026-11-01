package dev.nklip.javacraft.shardshop.testing.kind;

import io.cucumber.java.en.Then;

import java.io.IOException;

public final class EksDurabilitySteps {
    @Then("every database cluster archives WAL and has a completed base backup")
    public void backups() throws IOException, InterruptedException { new EksDurability().backups(); }

    @Then("a point-in-time restore of each selected database cluster returns only the rows before its target time")
    public void restores() throws IOException, InterruptedException { new EksDurability().pointInTimeRestores(); }

    @Then("every quorum queue has three members on three brokers and keeps a leader when one broker stops")
    public void brokerQuorum() throws IOException, InterruptedException { new EksDurability().brokerQuorum(); }

    @Then("every runtime Secret is synced from AWS Secrets Manager and every login role is applied")
    public void runtimeSecrets() throws IOException, InterruptedException { new EksDurability().runtimeSecrets(); }
}
