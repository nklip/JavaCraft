package dev.nklip.javacraft.shardshop.testing.kind;

import io.cucumber.java.en.Then;
import java.io.IOException;

public final class KindTopologySteps {
    @Then("every inventory shard has independent replication, durable quorum, worker volumes and golden routing")
    public void topology() throws IOException, InterruptedException { new KindTopology().verify(); }
}
