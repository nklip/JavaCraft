package dev.nklip.javacraft.shardshop.testing.cluster.gate;

import dev.nklip.javacraft.shardshop.testing.cluster.Topology;
import io.cucumber.java.en.Then;
import java.io.IOException;

public final class TopologySteps {
    @Then("every inventory shard has independent replication, durable quorum, worker volumes and golden routing")
    public void topology() throws IOException, InterruptedException { new Topology().verify(); }
}
