package dev.nklip.javacraft.shardshop.testing.cucumber.step;

import dev.nklip.javacraft.shardshop.testing.cucumber.config.CatalogDatabase;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.Json;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.RawHttp.Response;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.SqlRows;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.CatalogApi;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.ProductRuntime;
import io.cucumber.java.After;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;

import static dev.nklip.javacraft.shardshop.testing.cucumber.world.CatalogApi.assertFaster;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PostgreSQL TLS and the recovery of the product pools after a database outage. */
public class ProductTransportStepDefinitions {
    private final ProductRuntime product;
    private final CatalogApi api;
    private Long productPid;
    private boolean postgresqlStopped;

    public ProductTransportStepDefinitions(ProductRuntime product, CatalogApi api) {
        this.product = product;
        this.api = api;
    }

    /** A failed scenario must not leave PostgreSQL stopped for the next scenarios. */
    @After
    public void startPostgresql() throws InterruptedException {
        if (postgresqlStopped) {
            CatalogDatabase.instance().start();
        }
    }

    @Then("every PostgreSQL session of product_app uses TLS")
    public void productSessionsUseTls() throws SQLException {
        try (Connection connection = CatalogDatabase.instance().superuser("postgres");
             Statement statement = connection.createStatement()) {
            assertEquals("t", SqlRows.single(statement.executeQuery("SELECT count(*) > 0 AND bool_and(ssl) "
                    + "FROM pg_stat_ssl JOIN pg_stat_activity USING (pid) WHERE usename = 'product_app'")),
                    "Product sessions without TLS");
        }
    }

    @When("PostgreSQL stops")
    public void stopPostgresql() {
        productPid = product.application().pid();
        postgresqlStopped = true;
        CatalogDatabase.instance().stop();
    }

    @When("PostgreSQL starts again")
    public void startPostgresqlAgain() throws InterruptedException {
        CatalogDatabase.instance().start();
        postgresqlStopped = false;
    }

    @Then("a GET of the seller {string} returns the seller within {int} seconds, and each attempt ends in less than {int} seconds")
    public void sellerRecovers(String alias, int seconds, int attemptSeconds) throws IOException, InterruptedException {
        CatalogApi.Seller seller = api.seller(alias);
        long deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        Response response = api.get(seller.path());
        while (response.status() == 503 && System.nanoTime() < deadline) {
            assertFaster(response, Duration.ofSeconds(attemptSeconds));
            Thread.sleep(100);
            response = api.get(seller.path());
        }
        assertFaster(response, Duration.ofSeconds(attemptSeconds));
        assertEquals(200, response.status(), "The pool did not reconnect: " + response.text());
        assertEquals(seller.body(), Json.parse(response.body()), "The PostgreSQL restart changed the stored seller");
    }

    @Then("the product process is the same process")
    public void sameProductProcess() {
        assertEquals(productPid, product.application().pid(), "Product process");
        assertTrue(product.application().isAlive(), "Product must keep running");
    }
}
