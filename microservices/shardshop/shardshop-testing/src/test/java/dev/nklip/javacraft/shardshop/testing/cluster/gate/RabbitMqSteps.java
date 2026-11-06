package dev.nklip.javacraft.shardshop.testing.cluster.gate;

import dev.nklip.javacraft.shardshop.testing.cluster.RabbitMq;
import io.cucumber.java.en.Then;
import java.io.IOException;

public final class RabbitMqSteps {
    @Then("the RabbitMQ broker is ready with the required durable delivery topology")
    public void ready() throws IOException, InterruptedException { new RabbitMq().verify(); }
}
