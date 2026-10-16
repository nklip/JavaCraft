package dev.nklip.javacraft.shardshop.testing.kind;

import io.cucumber.java.en.Then;
import java.io.IOException;

public final class KindRabbitMqSteps {
    @Then("the RabbitMQ broker is ready with the required durable delivery topology")
    public void ready() throws IOException, InterruptedException { new KindRabbitMq().verify(); }
}
