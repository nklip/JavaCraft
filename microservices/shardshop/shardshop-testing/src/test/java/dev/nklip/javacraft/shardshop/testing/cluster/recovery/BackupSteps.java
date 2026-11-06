package dev.nklip.javacraft.shardshop.testing.cluster.recovery;

import io.cucumber.java.en.Then;

public final class BackupSteps {
    @Then("the latest allocator identity and high-water history are retained outside kind")
    public void allocator() throws Exception { new BackupRestore().allocator(); }

    @Then("a coordinated backup is written outside kind and the source is restored")
    public void backup() throws Exception { new BackupRestore().backup(); }

    @Then("the selected backup restores into a new isolated target with explicit CDC recovery")
    public void restore() throws Exception { new BackupRestore().restore(); }
}
