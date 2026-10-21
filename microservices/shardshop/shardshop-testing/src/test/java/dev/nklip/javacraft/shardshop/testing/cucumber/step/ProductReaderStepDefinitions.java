package dev.nklip.javacraft.shardshop.testing.cucumber.step;

import dev.nklip.javacraft.shardshop.testing.cucumber.config.PackagedApplication;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.ProductRuntime;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.StoredCatalog;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.WorkloadRuntime;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** The packaged reader of steps 3.8 and 3.9 against product and the seeded shard databases. */
public class ProductReaderStepDefinitions {
    private static final String READING = "Reading at 200 requests/s with 16 workers and 16 connections";
    private static final String WAITING = "The catalog is not complete yet: ";
    private static final String WAIT = "dev.nklip.javacraft.shardshop.workload.reader.config.CatalogWait/resolve/";
    private static final Pattern RESOLVED = Pattern.compile("Resolved catalog-v2 \\[(.*)], key-to-ID SHA-256 ([0-9a-f]{64})$");
    private static final Pattern REPORT = Pattern.compile("\\b(INFO|WARN) .*Product reads, (interval|total) ([0-9.]+) s: "
            + "(\\d+) requests, ([0-9.]+)/s, outcomes \\{(.*?)}, latency ms \\{(.*?)}, regions \\{(.*?)}, pool \\{(.*?)}, "
            + "catalog-v2 key-to-ID SHA-256 ([0-9a-f]{64})$");
    private static final Pattern COUNT = Pattern.compile("(\\w+)=(\\d+)");
    private static final Pattern STARTUP_FAILURE = Pattern.compile("ERROR .*Product reader startup failed: the catalog "
            + "was not complete within the wait\\. Last failure: Look up seller catalog-v2\\.(US|EU|ASIA)\\.seller\\.\\d+ "
            + "failed with REPLICA_UNAVAILABLE$");

    private final ProductRuntime product;
    private final WorkloadRuntime workloads;
    private int loadStart;
    private int restartMark;
    private int exitCode;

    private record Report(String level, String scope, double rate, long requests, Map<String, Long> outcomes,
                          Map<String, Long> latency, Map<String, Long> regions, Map<String, Long> pool) {
    }

    public ProductReaderStepDefinitions(ProductRuntime product, WorkloadRuntime workloads) {
        this.product = product;
        this.workloads = workloads;
    }

    @When("the reader starts")
    public void readerStarts() throws IOException {
        workloads.startReader(List.of());
    }

    @When("the reader starts with a maximum wait of {int} seconds and a maximum backoff of {int} seconds")
    public void readerStartsWithShortWait(int seconds, int backoff) throws IOException {
        // SmallRye Fault Tolerance rejects a maximum backoff that is longer than the maximum wait.
        workloads.startReader(List.of("-D" + WAIT + "Retry/maxDuration=" + seconds * 1000,
                "-D" + WAIT + "ExponentialBackoff/maxDelay=" + backoff * 1000));
    }

    @Then("the reader waits for the catalog and does not start its load")
    public void readerWaits() throws InterruptedException {
        PackagedApplication reader = workloads.reader();
        await(Duration.ofSeconds(30), "The reader did not wait for the catalog",
                () -> reader.lines().stream().filter(line -> line.contains(WAITING)).count() >= 2);
        assertTrue(reader.lines().stream().noneMatch(line -> line.contains(READING)),
                "The reader started its load without the catalog");
    }

    @Then("the reader starts its load within {int} seconds and resolves {string} with the digest of the seeder")
    public void readerResolves(int seconds, String regions) throws InterruptedException {
        PackagedApplication reader = workloads.reader();
        await(Duration.ofSeconds(seconds), "The reader did not start its load",
                () -> reader.lines().stream().anyMatch(line -> line.contains(READING)));
        List<String> lines = reader.lines();
        loadStart = indexOf(lines, READING);
        List<Matcher> resolved = lines.stream().map(RESOLVED::matcher).filter(Matcher::find).toList();
        assertEquals(1, resolved.size(), "The reader logs exactly one resolution");
        assertEquals(regions, resolved.getFirst().group(1), "Resolution summary");
        assertEquals(workloads.seededDigest(), resolved.getFirst().group(2),
                "The reader resolved other IDs than the seeder verified");
    }

    /**
     * The concurrent lookups of the startup open all connections of the pool. The reports come before the end of the
     * first connection lifetime, and each connection is in use again before its idle timeout. Thus all connections of
     * the warm-up are still open.
     */
    @Then("the next {int} reports of the reader have only OK reads at {int} to {int} requests\\/s in all three regions, {int} open connections and no read that waits for a connection")
    public void healthyReports(int count, int minimumRate, int maximumRate, int connections) throws InterruptedException {
        PackagedApplication reader = workloads.reader();
        await(Duration.ofSeconds(5L * count + 15), "Missing reader reports",
                () -> reports(reader.lines().subList(loadStart, reader.lines().size())).size() >= count);
        List<Report> reports = reports(reader.lines().subList(loadStart, reader.lines().size())).subList(0, count);
        for (Report report : reports) {
            assertEquals("interval", report.scope(), report.toString());
            assertEquals("INFO", report.level(), report.toString());
            assertEquals(Set.of("OK"), report.outcomes().keySet(), report.toString());
            assertTrue(report.rate() >= minimumRate && report.rate() <= maximumRate, "Request rate of " + report);
            assertTrue(report.latency().get("p99") < 1000, "Healthy reads were slow: " + report);
            assertEquals(Set.copyOf(StoredCatalog.REGIONS), report.regions().keySet(), "Read regions of " + report);
            assertEquals(connections, report.pool().get("max"), "Pool size of " + report);
            assertEquals(0L, report.pool().get("pending"), "Reads that wait for a connection: " + report);
            assertEquals(connections, report.pool().get("leased") + report.pool().get("available"),
                    "Open connections of the warm-up: " + report);
        }
        assertEquals(List.of(), reader.problems(), "Reader warnings and errors");
    }

    @When("product stops for {int} seconds, then runs with the replica read profile for {int} seconds, and then runs against the shard primaries")
    public void productRestarts(int stopped, int replica) throws IOException, InterruptedException {
        restartMark = workloads.reader().lines().size();
        product.stop();
        Thread.sleep(Duration.ofSeconds(stopped));
        product.start(ProductRuntime.Endpoints.REFUSED_REPLICAS);
        Thread.sleep(Duration.ofSeconds(replica));
        product.restart(ProductRuntime.Endpoints.PRIMARIES);
    }

    @Then("the reader records TRANSPORT and REPLICA_UNAVAILABLE reads, and then only OK reads within {int} seconds")
    public void readerRecovers(int seconds) throws InterruptedException {
        PackagedApplication reader = workloads.reader();
        await(Duration.ofSeconds(seconds), "The reader did not recover after the product restart", () -> {
            List<Report> reports = reportsSinceRestart(reader);
            return !reports.isEmpty() && failed(total(reports, Report::outcomes))
                    && reports.getLast().outcomes().keySet().equals(Set.of("OK"));
        });
        Map<String, Long> outcomes = total(reportsSinceRestart(reader), Report::outcomes);
        assertTrue(outcomes.getOrDefault("TRANSPORT", 0L) >= 1 && outcomes.getOrDefault("REPLICA_UNAVAILABLE", 0L) >= 1,
                "Missing connection or replica failures: " + outcomes);
    }

    @Then("no read since the product stop has the outcome NOT_FOUND, INVALID_RESPONSE or HTTP_ERROR, or takes longer than {double} seconds")
    public void noUnexpectedOutcomes(double seconds) {
        List<Report> reports = reportsSinceRestart(workloads.reader());
        Map<String, Long> outcomes = total(reports, Report::outcomes);
        for (String outcome : List.of("NOT_FOUND", "INVALID_RESPONSE", "HTTP_ERROR")) {
            assertFalse(outcomes.containsKey(outcome), "Unexpected outcomes during the restarts: " + outcomes);
        }
        long longest = reports.stream().mapToLong(report -> report.latency().get("max")).max().orElseThrow();
        assertTrue(longest <= seconds * 1000, "A read took " + longest + " ms");
    }

    @When("the reader receives SIGTERM")
    public void stopReader() throws InterruptedException {
        exitCode = workloads.reader().stop();
    }

    @Then("the reader exits with code 0 or 143, its last report is the total of all reads, and it logs no error")
    public void readerStopped() {
        PackagedApplication reader = workloads.reader();
        assertTrue(exitCode == 0 || exitCode == 143, "Exit code after SIGTERM: " + exitCode);
        List<Report> reports = reports(reader.lines());
        Report last = reports.getLast();
        assertEquals("total", last.scope(), "The last report");
        List<Report> intervals = reports.subList(0, reports.size() - 1);
        assertTrue(intervals.stream().allMatch(report -> report.scope().equals("interval")), "One total report");
        assertTrue(last.requests() >= intervals.stream().mapToLong(Report::requests).sum(), "The totals lost requests");
        assertTrue(reader.lines().stream().noneMatch(line -> line.contains("ERROR")), "The reader logged an error");
    }

    @Then("the reader exits with code 1 within {int} seconds and logs only the error of a seller lookup that failed with REPLICA_UNAVAILABLE")
    public void readerGivesUp(int seconds) throws InterruptedException {
        PackagedApplication reader = workloads.reader();
        assertEquals(1, reader.awaitExit(Duration.ofSeconds(seconds)), reader.tail());
        List<String> problems = reader.problems();
        assertEquals(1, problems.size(), "Reader warnings and errors: " + problems);
        assertTrue(STARTUP_FAILURE.matcher(problems.getFirst()).find(), problems.getFirst());
    }

    @Then("the reader did not start its load")
    public void noLoad() {
        assertTrue(workloads.reader().lines().stream().noneMatch(line -> line.contains(READING)),
                "The reader started its load without the catalog");
    }

    private List<Report> reportsSinceRestart(PackagedApplication reader) {
        List<String> lines = reader.lines();
        return reports(lines.subList(restartMark, lines.size()));
    }

    private List<Report> reports(List<String> lines) {
        List<Report> reports = new ArrayList<>();
        for (String line : lines) {
            Matcher matcher = REPORT.matcher(line);
            if (matcher.find()) {
                assertEquals(workloads.seededDigest(), matcher.group(10), "Reader report catalog ID digest");
                reports.add(new Report(matcher.group(1), matcher.group(2), Double.parseDouble(matcher.group(5)),
                        Long.parseLong(matcher.group(4)), counts(matcher.group(6)), counts(matcher.group(7)),
                        counts(matcher.group(8)), counts(matcher.group(9))));
            }
        }
        return reports;
    }

    private static Map<String, Long> counts(String text) {
        Map<String, Long> counts = new HashMap<>();
        Matcher matcher = COUNT.matcher(text);
        while (matcher.find()) {
            counts.put(matcher.group(1), Long.parseLong(matcher.group(2)));
        }
        return counts;
    }

    private static Map<String, Long> total(List<Report> reports, Function<Report, Map<String, Long>> field) {
        Map<String, Long> total = new HashMap<>();
        reports.forEach(report -> field.apply(report).forEach((name, value) -> total.merge(name, value, Long::sum)));
        return total;
    }

    private static boolean failed(Map<String, Long> outcomes) {
        return outcomes.keySet().stream().anyMatch(outcome -> !outcome.equals("OK"));
    }

    private static int indexOf(List<String> lines, String text) {
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).contains(text)) {
                return index;
            }
        }
        throw new IllegalStateException("No line contains " + text);
    }

    private void await(Duration timeout, String message, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail(message + ":\n" + workloads.reader().tail());
            }
            if (!workloads.reader().isAlive()) {
                fail("The reader exited:\n" + workloads.reader().tail());
            }
            Thread.sleep(100);
        }
    }
}
