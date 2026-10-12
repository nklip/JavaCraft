package dev.nklip.javacraft.shardshop.testing.cucumber.step;

import dev.nklip.javacraft.shardshop.testing.cucumber.world.CatalogShards;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.ProductRuntime;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.ProductRuntime.Endpoints;
import io.cucumber.java.After;
import io.cucumber.java.Scenario;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.When;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The shard databases and the product process, which all product scenarios share. */
public class ProductStepDefinitions {
    private final CatalogShards shards;
    private final ProductRuntime product;

    public ProductStepDefinitions(CatalogShards shards, ProductRuntime product) {
        this.shards = shards;
        this.product = product;
    }

    @Given("the catalog shards {string} that Flyway migrated")
    public void migratedShards(String inventory) throws SQLException {
        shards.create(inventory);
    }

    @Given("product runs against the shard primaries")
    public void productRuns() throws IOException, InterruptedException {
        product.start(Endpoints.PRIMARIES);
    }

    @Given("product runs with the replica read profile and replica endpoints that refuse connections")
    public void productRunsWithReplicas() throws IOException, InterruptedException {
        product.start(Endpoints.REFUSED_REPLICAS);
    }

    @When("product restarts with a new generator ID")
    @When("product restarts against the shard primaries")
    public void productRestarts() throws IOException, InterruptedException {
        product.restart(Endpoints.PRIMARIES);
    }

    @When("product restarts with primary endpoints that refuse connections")
    public void productRestartsWithoutPrimaries() throws IOException, InterruptedException {
        product.restart(Endpoints.REFUSED_PRIMARIES);
    }

    @When("product restarts with the replica read profile and replica endpoints that refuse connections")
    public void productRestartsWithReplicas() throws IOException, InterruptedException {
        product.restart(Endpoints.REFUSED_REPLICAS);
    }

    @When("product restarts with the root certificate of an unrelated CA")
    public void productRestartsWithUnrelatedCa() throws IOException, InterruptedException {
        product.restart(Endpoints.UNRELATED_CA);
    }

    @When("product restarts with the host name 127.0.0.1, which the server certificate does not contain")
    public void productRestartsWithIpAddress() throws IOException, InterruptedException {
        product.restart(Endpoints.IP_ADDRESS_HOST);
    }

    /**
     * No product JVM of a scenario may log a warning or an error, also for the invalid requests, deadlines and outages
     * that the scenario causes. Product stops first, thus the check also covers its shutdown. A failed scenario
     * already has its cause, thus it gets no second failure from this check.
     */
    @After
    public void productLogsNoProblems(Scenario scenario) throws InterruptedException {
        product.stop();
        if (!scenario.isFailed()) {
            assertEquals(List.of(), product.problems(), "Product warnings and errors");
        }
    }
}
