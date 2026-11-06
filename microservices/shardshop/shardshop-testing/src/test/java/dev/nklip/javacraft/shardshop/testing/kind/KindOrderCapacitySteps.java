package dev.nklip.javacraft.shardshop.testing.kind;

import io.cucumber.java.en.Then;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;

/** Write capacity of the deployed lab, selected only by the explicit kind-order-capacity tag. */
public final class KindOrderCapacitySteps {
    @Then("the producer sustains {bigdecimal} new orders per second for {int} seconds with at least {double} per second")
    public void sustains(BigDecimal rate, int seconds, double minimumRate) throws IOException, InterruptedException {
        new KindOrderCapacity().verify(rate, Duration.ofSeconds(seconds), minimumRate);
    }
}
