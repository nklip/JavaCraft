package dev.nklip.javacraft.shardshop.testing.cucumber.step;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nklip.javacraft.shardshop.common.contract.ContractDocument;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.PackagedApplication;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.CatalogShards;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.Contracts;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.StoredCatalog;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.WorkloadRuntime;
import io.cucumber.java.After;
import io.cucumber.java.Scenario;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live traffic control of the packaged workloads (step 4.13). The steps measure traffic only through the control
 * status, which counts the backend HTTP requests of each workload, and check every response against the contract.
 */
public class WorkloadControlStepDefinitions {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SCHEMAS = "#/components/schemas/";
    private static final Pattern SUMMARY = Pattern.compile("^Seeded and verified catalog-v2 \\[.*], key-to-ID SHA-256 ([0-9a-f]{64})$");
    private static final Duration MINIMUM_WINDOW = Duration.ofSeconds(2);

    private final CatalogShards shards;
    private final WorkloadRuntime workloads;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2)).build();
    private final AtomicBoolean confirming = new AtomicBoolean();
    private Thread confirmer;
    private volatile boolean confirmerStopped;
    private String confirmedRun;
    private long revisionBeforeUpdates;
    private Map<String, StoredCatalog.Row> firstSeededCatalog;

    public WorkloadControlStepDefinitions(CatalogShards shards, WorkloadRuntime workloads) {
        this.shards = shards;
        this.workloads = workloads;
    }

    @After(value = "@workload-control", order = 11500)
    public void stopWorkloads(Scenario scenario) throws InterruptedException {
        confirmerStopped = true;
        if (confirmer != null) {
            confirmer.interrupt();
            confirmer.join(Duration.ofSeconds(5));
        }
        List<String> problems = new ArrayList<>();
        for (PackagedApplication application : running()) {
            if (application.isAlive()) {
                application.stop();
            }
            problems.addAll(application.problems());
        }
        http.close();
        if (!scenario.isFailed()) {
            assertEquals(List.of(), problems, "Reader and interactive seeder warnings and errors");
        }
    }

    private List<PackagedApplication> running() {
        List<PackagedApplication> applications = new ArrayList<>();
        try {
            applications.add(workloads.reader());
        } catch (IllegalStateException notStarted) {
            // This scenario has no reader.
        }
        try {
            applications.add(workloads.seederControl());
        } catch (IllegalStateException notStarted) {
            // This scenario has no interactive seeder.
        }
        return applications;
    }

    @When("the reader starts with an initial rate of {int}")
    public void readerStarts(int rate) throws IOException {
        workloads.startReader(List.of("-Dshardshop.reader.request-rate=" + rate));
    }

    @When("the reader restarts with an initial rate of {int}")
    public void readerRestarts(int rate) throws IOException, InterruptedException {
        int exit = workloads.reader().stop();
        assertTrue(exit == 0 || exit == 143, "Reader exit code " + exit);
        readerStarts(rate);
    }

    @When("the interactive seeder starts with an initial rate of {int}")
    public void seederStarts(int rate) throws IOException {
        workloads.startSeederControl(List.of("-Dshardshop.seeder.request-rate=" + rate));
    }

    @When("the interactive seeder restarts with an initial rate of {int}")
    public void seederRestarts(int rate) throws IOException, InterruptedException {
        int exit = workloads.seederControl().stop();
        assertTrue(exit == 0 || exit == 143, "Interactive seeder exit code " + exit);
        seederStarts(rate);
    }

    @When("the normal order producer {string} starts with an initial rate of {int}")
    public void producerStarts(String run, int rate) throws IOException {
        workloads.startProducer(run, -1, List.of("-Dshardshop.producer.order-rate=" + rate));
    }

    @Then("the {word} control shows {word} at a target of {int} {string} within {int} seconds")
    public void controlShows(String workload, String state, int target, String unit, int seconds) throws Exception {
        JsonNode status = await(workload, Duration.ofSeconds(seconds), "state " + state,
                current -> current.path("state").asText().equals(state));
        assertSameRate(target, status.path("targetRate"));
        assertEquals(unit, status.path("unit").asText());
    }

    @When("the {word} target rate becomes {int}")
    public void rateBecomes(String workload, int rate) throws Exception {
        JsonNode status = expect(send(workload, "PUT", "/rate", "{\"rate\": " + rate + "}"), 200, "ControlStatus");
        assertSameRate(rate, status.path("targetRate"));
    }

    @When("the {word} is paused")
    public void paused(String workload) throws Exception {
        assertTrue(expect(send(workload, "POST", "/pause", null), 200, "ControlStatus").path("paused").asBoolean());
    }

    @When("the {word} resumes")
    public void resumes(String workload) throws Exception {
        assertFalse(expect(send(workload, "POST", "/resume", null), 200, "ControlStatus").path("paused").asBoolean());
    }

    @Then("the {word} sends {double} to {double} {string} requests per second")
    public void sendsRequests(String workload, double minimum, double maximum, String category) throws Exception {
        assertRate(workload, minimum, maximum, status -> category.equals("all") ? status.path("requests").asLong()
                : category(status, category).path("requests").asLong());
    }

    @Then("the {word} starts {double} to {double} new orders per second")
    public void startsOrders(String workload, double minimum, double maximum) throws Exception {
        assertRate(workload, minimum, maximum, status -> status.path("starts").asLong());
    }

    @Then("the {word} sends at most {int} {string} requests in its first {int} milliseconds")
    public void noBurst(String workload, int maximum, String category, int milliseconds) throws Exception {
        long before = category(status(workload), category).path("requests").asLong();
        Thread.sleep(milliseconds);
        long after = category(status(workload), category).path("requests").asLong();
        assertTrue(after - before <= maximum, "Resume sent " + (after - before) + " requests at once");
    }

    @Then("the {word} shows PAUSED within {int} seconds and sends no request for {int} seconds")
    public void pausedWithoutRequests(String workload, int seconds, int quiet) throws Exception {
        JsonNode status = await(workload, Duration.ofSeconds(seconds), "PAUSED",
                current -> current.path("state").asText().equals("PAUSED"));
        assertEquals(0, status.path("inFlight").asInt());
        assertQuiet(workload, quiet);
    }

    @Then("the {word} sends no request for {int} seconds")
    public void quiet(String workload, int seconds) throws Exception {
        assertQuiet(workload, seconds);
    }

    @Then("invalid {word} updates are rejected with 400 without changing its target {int} or its revision")
    public void invalidUpdates(String workload, int target) throws Exception {
        JsonNode before = status(workload);
        for (String body : List.of("{\"rate\": -1}", "{\"rate\": 10000.5}", "{\"rate\": \"5\"}", "{\"rate\": 0.0001}",
                "{\"rate\": 1", "{\"rate\": 1, \"other\": 2}", "{\"rate\": 1, \"rate\": 2}", "[5]", "")) {
            JsonNode error = expect(send(workload, "PUT", "/rate", body), 400, "Error");
            assertTrue(Set.of("INVALID_RATE", "INVALID_REQUEST").contains(error.path("code").asText()), body);
        }
        JsonNode after = status(workload);
        assertSameRate(target, after.path("targetRate"));
        assertEquals(before.path("revision").asLong(), after.path("revision").asLong(), "Rejected updates changed nothing");
        revisionBeforeUpdates = after.path("revision").asLong();
    }

    @When("{int} concurrent {word} updates set the target rates 1 to {int}")
    public void concurrentUpdates(int count, String workload, int highest) throws Exception {
        assertEquals(count, highest);
        revisionBeforeUpdates = status(workload).path("revision").asLong();
        CountDownLatch ready = new CountDownLatch(count);
        List<Callable<Long>> updates = new ArrayList<>();
        for (int rate = 1; rate <= count; rate++) {
            String body = "{\"rate\": " + rate + "}";
            updates.add(() -> {
                ready.countDown();
                ready.await();
                return expect(send(workload, "PUT", "/rate", body), 200, "ControlStatus").path("revision").asLong();
            });
        }
        Set<Long> revisions = new HashSet<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(count)) {
            for (Future<Long> revision : executor.invokeAll(updates)) {
                revisions.add(revision.get());
            }
        }
        assertEquals(count, revisions.size(), "Each concurrent update must apply alone: " + revisions);
    }

    @Then("the {word} revision grew by exactly {int} and its target is one of the rates 1 to {int}")
    public void revisionGrew(String workload, int count, int highest) throws Exception {
        JsonNode status = status(workload);
        assertEquals(revisionBeforeUpdates + count, status.path("revision").asLong());
        int target = status.path("targetRate").decimalValue().intValueExact();
        assertTrue(target >= 1 && target <= highest, "Target " + target);
        assertEquals(status.path("targetRate").decimalValue(), status.path("effectiveRate").decimalValue());
    }

    @Then("the {word} shows revision 0, unpaused")
    public void restoredDefaults(String workload) throws Exception {
        JsonNode status = status(workload);
        assertEquals(0, status.path("revision").asLong(), "A restart restores the configured defaults");
        assertFalse(status.path("paused").asBoolean());
        assertEquals(status.path("defaultRate").decimalValue(), status.path("targetRate").decimalValue());
    }

    @When("an SQL confirmer confirms each pending order of the run {string}")
    public void startConfirmer(String run) {
        confirmedRun = run + ":catalog-v2:regional-v1";
        confirming.set(true);
        confirmer = Thread.ofPlatform().name("workload-control-confirmer").daemon().start(() -> {
            while (!confirmerStopped) {
                try {
                    if (confirming.get()) {
                        confirmPendingOrders();
                    }
                    Thread.sleep(25);
                } catch (InterruptedException stopped) {
                    return;
                } catch (SQLException retry) {
                    // The next pass confirms the same pending orders.
                }
            }
        });
    }

    @When("the SQL confirmer pauses and the order-producer is paused with an order in flight")
    public void pauseWithOrderInFlight() throws Exception {
        confirming.set(false);
        await("order-producer", Duration.ofSeconds(10), "one order in flight",
                status -> status.path("inFlight").asInt() == 1);
        paused("order-producer");
    }

    @When("the SQL confirmer resumes")
    public void resumeConfirmer() {
        confirming.set(true);
    }

    @Then("the order-producer shows PAUSING with 1 operation in flight and allocates no new ordinal for {int} seconds")
    public void pausingWithOrderInFlight(int seconds) throws Exception {
        JsonNode status = status("order-producer");
        assertEquals("PAUSING", status.path("state").asText());
        assertEquals(1, status.path("inFlight").asInt());
        long allocations = category(status, "allocation").path("requests").asLong();
        int ordinals = ordinals(confirmedRun).size();
        Thread.sleep(seconds * 1000L);
        JsonNode later = status("order-producer");
        assertEquals("PAUSING", later.path("state").asText(), "The pending order is still observed");
        assertEquals(allocations, category(later, "allocation").path("requests").asLong());
        assertEquals(ordinals, ordinals(confirmedRun).size(), "No new allocation while pausing");
        assertTrue(category(later, "polling").path("requests").asLong() > category(status, "polling").path("requests").asLong(),
                "The producer keeps observing its order in flight");
    }

    @Then("the {word} shows the concurrency limit {int} below the target")
    public void concurrencyLimit(String workload, int concurrency) throws Exception {
        JsonNode status = await(workload, Duration.ofSeconds(15), "a concurrency limit",
                current -> current.path("limit").isObject());
        JsonNode limit = status.path("limit");
        double target = status.path("effectiveRate").asDouble();
        assertEquals("CONCURRENCY", limit.path("code").asText());
        assertEquals(concurrency, limit.path("concurrency").asInt());
        assertTrue(limit.path("achievableRate").asDouble() < target, limit.toString());
        assertTrue(status.path("achievedRate").asDouble() < target, status.toString());
    }

    @Then("the run {string} has consecutive ordinals from 0 with one allocation each")
    public void consecutiveOrdinals(String run) throws Exception {
        List<Long> ordinals = ordinals(run + ":catalog-v2:regional-v1");
        assertFalse(ordinals.isEmpty());
        assertEquals(LongStream.range(0, ordinals.size()).boxed().toList(), ordinals,
                "Rate changes and Pause must keep the ordinal stream without gaps or repeats");
    }

    @Then("the order-producer counts startup, allocation, submission and polling requests separately")
    public void producerTraffic() throws Exception {
        JsonNode status = status("order-producer");
        long allocations = ordinals(confirmedRun).size();
        // 352 catalog lookups and 3000 buyer creations, plus the repeats after the proxy's hidden responses.
        assertTrue(category(status, "startup").path("requests").asLong() >= 3352, status.toString());
        assertTrue(category(status, "allocation").path("requests").asLong() >= allocations, status.toString());
        assertTrue(category(status, "submission").path("requests").asLong() >= allocations, status.toString());
        assertTrue(category(status, "polling").path("requests").asLong() > 0, status.toString());
        long total = 0;
        for (JsonNode category : status.path("traffic")) {
            total += category.path("requests").asLong();
        }
        assertEquals(total, status.path("requests").asLong());
        assertTrue(status.path("starts").asLong() <= allocations, "New order starts count accepted new orders only");
    }

    @When("a seed run starts")
    public void seedRunStarts() throws Exception {
        JsonNode status = expect(send("product-seeder", "POST", "/runs", null), 202, "ControlStatus");
        assertEquals("RUNNING", status.path("run").path("state").asText());
    }

    @Then("a second seed run is rejected with 409 RUN_ACTIVE")
    public void secondRunRejected() throws Exception {
        assertEquals("RUN_ACTIVE", expect(send("product-seeder", "POST", "/runs", null), 409, "Error")
                .path("code").asText());
    }

    @Then("the seed run {int} completes within {int} seconds after {int} operations without errors")
    public void seedRunCompletes(int number, int seconds, int operations) throws Exception {
        long requestsBefore = status("product-seeder").path("requests").asLong();
        JsonNode status = await("product-seeder", Duration.ofSeconds(seconds), "a finished run " + number,
                current -> current.path("run").path("number").asInt() == number
                        && !current.path("run").path("state").asText().equals("RUNNING"));
        JsonNode run = status.path("run");
        assertEquals("COMPLETED", run.path("state").asText(), run.toString());
        assertEquals("COMPLETED", status.path("state").asText());
        assertEquals(operations, run.path("completedOperations").asLong());
        assertEquals(operations, run.path("plannedOperations").asLong());
        assertTrue(run.path("error").isNull());
        assertEquals(0, status.path("errors").asLong());
        assertEquals(operations * (long) number, status.path("requests").asLong(),
                "Each operation of each run sent exactly one request: " + requestsBefore);
    }

    @Then("the seed run summary has the digest of the stored IDs")
    public void summaryDigest() throws Exception {
        Matcher summary = SUMMARY.matcher(status("product-seeder").path("run").path("summary").asText());
        assertTrue(summary.matches(), summary.toString());
        Map<String, StoredCatalog.Row> stored = StoredCatalog.read(shards);
        assertEquals(StoredCatalog.digest(stored), summary.group(1));
        if (firstSeededCatalog == null) {
            firstSeededCatalog = stored;
        }
    }

    @Then("the stored catalog equals the catalog after the first seed run")
    public void catalogUnchanged() throws Exception {
        assertEquals(firstSeededCatalog, StoredCatalog.read(shards), "A rerun created, duplicated or changed entities");
    }

    private void assertRate(String workload, double minimum, double maximum, Counter counter) throws Exception {
        // A window long enough for ten expected starts keeps the count error of one start below 10%.
        long window = Math.max(MINIMUM_WINDOW.toMillis(), (long) Math.ceil(10_000 / minimum));
        JsonNode first = status(workload);
        long started = System.nanoTime();
        Thread.sleep(window);
        JsonNode last = status(workload);
        double seconds = (System.nanoTime() - started) / 1e9;
        double rate = (counter.count(last) - counter.count(first)) / seconds;
        assertTrue(rate >= minimum && rate <= maximum, workload + " measured " + rate + "/s in " + seconds
                + " s, expected " + minimum + ".." + maximum + "; status " + last);
    }

    private static void assertSameRate(int expected, JsonNode rate) {
        assertEquals(0, new BigDecimal(expected).compareTo(rate.decimalValue()), "Rate " + rate + ", expected " + expected);
    }

    private void assertQuiet(String workload, int seconds) throws Exception {
        long before = status(workload).path("requests").asLong();
        Thread.sleep(seconds * 1000L);
        JsonNode after = status(workload);
        assertEquals(before, after.path("requests").asLong(), "Requests while paused: " + after);
    }

    private JsonNode await(String workload, Duration timeout, String description, Condition condition)
            throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        JsonNode last = null;
        while (System.nanoTime() - deadline < 0) {
            try {
                last = status(workload);
                if (condition.met(last)) {
                    return last;
                }
            } catch (IOException notReady) {
                // The workload has not opened its control port yet.
            }
            Thread.sleep(100);
        }
        throw new AssertionError(workload + " did not show " + description + " within " + timeout + ": " + last);
    }

    private JsonNode status(String workload) throws Exception {
        return expect(send(workload, "GET", "", null), 200, "ControlStatus");
    }

    private HttpResponse<String> send(String workload, String method, String path, String body)
            throws IOException, InterruptedException {
        URI uri = URI.create(workloads.controlUri(workload) + path);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode expect(HttpResponse<String> response, int status, String schema) throws IOException {
        assertEquals(status, response.statusCode(), response.request().method() + " " + response.uri() + ": " + response.body());
        assertEquals(List.of(), Contracts.WORKLOAD_CONTROL_API.errors(SCHEMAS + schema, ContractDocument.json(response.body())),
                "Undocumented control response: " + response.body());
        assertTrue(response.headers().allValues("Cache-Control").stream().anyMatch(value -> value.contains("no-store")),
                "Control responses must not be stored: " + response.headers());
        return JSON.readTree(response.body());
    }

    private static JsonNode category(JsonNode status, String name) {
        for (JsonNode category : status.path("traffic")) {
            if (category.path("category").asText().equals(name)) {
                return category;
            }
        }
        throw new AssertionError("Missing traffic category " + name + ": " + status);
    }

    private void confirmPendingOrders() throws SQLException {
        for (CatalogShards.Shard shard : shards.all()) {
            try (Connection connection = shards.superuser(shard)) {
                connection.setAutoCommit(false);
                List<Long> confirmed = new ArrayList<>();
                try (PreparedStatement orders = connection.prepareStatement(
                        "UPDATE ordering.orders o SET status='CONFIRMED', updated_at=CURRENT_TIMESTAMP "
                                + "FROM ordering.order_allocations a WHERE a.order_id=o.order_id AND a.run_name=? "
                                + "AND o.status NOT IN ('CONFIRMED', 'CANCELLED') RETURNING o.order_id")) {
                    orders.setString(1, confirmedRun);
                    try (ResultSet rows = orders.executeQuery()) {
                        while (rows.next()) {
                            confirmed.add(rows.getLong(1));
                        }
                    }
                }
                if (!confirmed.isEmpty()) {
                    try (PreparedStatement sagas = connection.prepareStatement(
                            "UPDATE ordering.order_sagas SET status='CONFIRMED', cancellation_reason=NULL, "
                                    + "updated_at=CURRENT_TIMESTAMP WHERE order_id = ANY(?)")) {
                        Array ids = connection.createArrayOf("bigint", confirmed.toArray());
                        sagas.setArray(1, ids);
                        sagas.executeUpdate();
                    }
                }
                connection.commit();
            }
        }
    }

    private List<Long> ordinals(String run) throws SQLException {
        List<Long> ordinals = new ArrayList<>();
        for (CatalogShards.Shard shard : shards.all()) {
            try (Connection connection = shards.superuser(shard);
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT request_ordinal FROM ordering.order_allocations WHERE run_name=?")) {
                statement.setString(1, run);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        ordinals.add(rows.getLong(1));
                    }
                }
            }
        }
        ordinals.sort(null);
        return ordinals;
    }

    @FunctionalInterface
    private interface Counter {
        long count(JsonNode status);
    }

    @FunctionalInterface
    private interface Condition {
        boolean met(JsonNode status);
    }
}
