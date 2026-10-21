package dev.nklip.javacraft.shardshop.testing.cucumber.step;

import dev.nklip.javacraft.shardshop.testing.cucumber.config.PackagedApplication;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.CatalogLock;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.CatalogShards;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.StoredCatalog;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.WorkloadRuntime;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The packaged seeder of step 3.7 against product and the shard databases of the scenario. */
public class ProductSeederStepDefinitions {
    private static final Pattern SUMMARY =
            Pattern.compile("Seeded and verified catalog-v2 \\[(.*)], key-to-ID SHA-256 ([0-9a-f]{64})$");

    private final CatalogShards shards;
    private final CatalogLock lock;
    private final WorkloadRuntime workloads;
    private Map<String, StoredCatalog.Row> failedRun;
    private Map<String, StoredCatalog.Row> stored;
    private String loggedDigest;

    public ProductSeederStepDefinitions(CatalogShards shards, CatalogLock lock, WorkloadRuntime workloads) {
        this.shards = shards;
        this.lock = lock;
        this.workloads = workloads;
    }

    @Given("a database session holds the creation lock of the seller {string}")
    public void holdSellerLock(String key) throws SQLException {
        lock.hold(key.split("\\.")[1], key);
    }

    @When("the seeder runs")
    public void seederRuns() throws IOException, InterruptedException {
        workloads.runSeeder(List.of());
    }

    @When("the seeder runs with {int} attempts and a retry backoff of {word}")
    public void seederRunsWithRetries(int attempts, String backoff) throws IOException, InterruptedException {
        workloads.runSeeder(List.of("-Dshardshop.seeder.max-attempts=" + attempts,
                "-Dshardshop.seeder.retry-backoff=" + backoff));
    }

    @Then("the seeder exits with code 1 and logs only the error {string}")
    public void seederFails(String message) {
        PackagedApplication seeder = workloads.seeder();
        assertEquals(1, workloads.seederExitCode(), seeder.tail());
        List<String> problems = seeder.problems();
        assertEquals(1, problems.size(), "A failed run logs exactly one error: " + problems);
        assertTrue(problems.getFirst().contains("ERROR") && problems.getFirst().endsWith(message), problems.getFirst());
    }

    @Then("the seeder exits with code 0 and logs the summary {string}")
    public void seederSucceeds(String regions) throws SQLException {
        Matcher summary = summary();
        assertEquals(regions, summary.group(1), "Regional summary");
        loggedDigest = summary.group(2);
        workloads.seeded(loggedDigest, StoredCatalog.read(shards));
    }

    @Then("the seeder exits with code 0 and logs the same key-to-ID digest")
    public void seederRecoversDigest() {
        assertEquals(workloads.seededDigest(), summary().group(2), "A repeated run recovered other IDs");
    }

    private Matcher summary() {
        PackagedApplication seeder = workloads.seeder();
        assertEquals(0, workloads.seederExitCode(), seeder.tail());
        assertEquals(List.of(), seeder.problems(), "Seeder warnings and errors");
        List<Matcher> summaries = seeder.lines().stream().map(SUMMARY::matcher).filter(Matcher::find).toList();
        assertEquals(1, summaries.size(), "The seeder logs exactly one summary");
        return summaries.getFirst();
    }

    @Then("the shards store {int} sellers and {int} products of catalog-v2 with their dataset payloads and placement")
    public void shardsStore(int sellers, int products) throws SQLException {
        stored = StoredCatalog.read(shards);
        StoredCatalog.assertDataset(shards, stored, sellers, products);
        if (failedRun == null && workloads.seederExitCode() != 0) {
            failedRun = stored;
        }
    }

    @Then("the logged key-to-ID digest is the digest of the stored IDs")
    public void digestMatches() {
        assertEquals(StoredCatalog.digest(stored), loggedDigest, "The logged digest differs from the stored IDs");
    }

    @Then("the entities of the failed run did not change")
    public void failedRunKept() {
        for (Map.Entry<String, StoredCatalog.Row> entity : failedRun.entrySet()) {
            assertEquals(entity.getValue(), stored.get(entity.getKey()), "Recovery changed " + entity.getKey());
        }
    }

    @Then("the stored catalog did not change")
    public void catalogUnchanged() throws SQLException {
        assertEquals(workloads.seededCatalog(), StoredCatalog.read(shards), "Stored catalog rows");
    }
}
