package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Read-only checks of the deployed product workload; deployment remains a production responsibility. */
public final class KindDeployment {
    private static final Pattern JOB_NAME = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
    private static final Pattern RESERVED = Pattern.compile("Reserved generator ID ([0-9]+) for this JVM start\\.");
    private static final Pattern DIGEST = Pattern.compile("key-to-ID SHA-256 ([0-9a-f]{64})(?![0-9a-f])");
    private static final Pattern REPORT = Pattern.compile("Product reads, interval ([0-9.]+) s: ([0-9]+) requests, ([0-9.]+)/s, outcomes \\{([^}]*)}.*pool \\{([^}]*)}");
    private static final Pattern COUNTER = Pattern.compile("http_server_requests_seconds_count\\{(.*)}\\s+([^ ]+)");
    private final KindLab lab;

    public KindDeployment() { this(new KindLab()); }
    KindDeployment(KindLab lab) { this.lab = lab; }

    public record Workload(List<String> products, JsonNode reader, JsonNode seeder, String digest) { }
    record Interval(Instant ended, double seconds, long requests, double rate, String outcomes, String pool) { }

    public Workload deployed() throws IOException, InterruptedException {
        JsonNode readerDeployment = null;
        for (String name : List.of("shardshop-product", "shardshop-product-reader")) {
            var result = lab.kubectlAttempt("get", "deployment", name, "-o", "json");
            require(result.exitCode() == 0, "Deploy and migrate the lab before workload checks: missing " + name);
            JsonNode deployment = KindLab.parse(result.stdout());
            if (name.equals("shardshop-product-reader")) readerDeployment = deployment;
            int expected = name.endsWith("reader") ? 1 : 2;
            require(deployment.path("spec").path("replicas").asInt() == expected
                    && deployment.path("status").path("readyReplicas").asInt() == expected
                    && deployment.path("status").path("updatedReplicas").asInt() == expected
                    && deployment.path("status").path("observedGeneration").asLong(-1)
                    >= deployment.path("metadata").path("generation").asLong(),
                    name + " must already have " + expected + " ready, current replicas");
        }
        List<String> products = readyProducts();
        require(products.size() == 2, "Expected two ready product pods before workload checks");
        List<JsonNode> readers = pods("app.kubernetes.io/name=shardshop-product-reader");
        require(readers.size() == 1 && ready(readers.getFirst())
                && readers.getFirst().path("status").path("phase").asText().equals("Running"),
                "The product reader must already have one running ready pod; deploy the workload first");
        JsonNode reader = readers.getFirst();
        httpOnly(reader.path("spec"));
        List<JsonNode> seederControls = pods("app.kubernetes.io/name=shardshop-product-seeder-control");
        require(seederControls.size() == 1 && ready(seederControls.getFirst()),
                "The interactive product seeder must already have one ready pod");
        httpOnly(seederControls.getFirst().path("spec"));
        String jobName = seederJobName(readerDeployment, reader);
        JsonNode job = resource("job", jobName);
        require(completed(job), "The deployed release needs a successful seeder Job before workload checks");
        httpOnly(job.path("spec").path("template").path("spec"));
        JsonNode seeder = successfulSeeder(jobName);
        String digest = digest(logs(name(seeder)), "Seeded and verified catalog-v2");
        lab.await("reader resolves the deployed seeder ID digest", Duration.ofSeconds(360).plus(readerInterval(reader)), () -> {
            String readerDigest = readerDigest(reader);
            require(digest.equals(readerDigest), "Reader resolved a different catalog ID digest than its deployed seeder");
            return true;
        });
        catalogCounts();
        generators(products, highWater());
        return new Workload(products, reader, seeder, digest);
    }

    static String seederJobName(JsonNode readerDeployment, JsonNode reader) throws IOException {
        require(readerDeployment != null, "The deployed reader Deployment is missing");
        JsonNode annotation = readerDeployment.path("metadata").path("annotations").path("shardshop.javacraft/seeder-job");
        if (!annotation.isMissingNode()) {
            require(annotation.isTextual() && JOB_NAME.matcher(annotation.textValue()).matches(),
                    "The deployed reader has an invalid current seeder Job annotation");
            return annotation.textValue();
        }
        String run = reader.path("metadata").path("labels").path("shardshop.javacraft/run").asText();
        require(run.matches("[a-z0-9][a-z0-9-]*"), "The deployed reader has no current seeder Job annotation or valid legacy run label");
        String name = "shardshop-product-seeder-" + run;
        require(JOB_NAME.matcher(name).matches(), "The deployed reader's legacy seeder Job name is invalid");
        return name;
    }

    public void workload() throws IOException, InterruptedException {
        Workload deployed = deployed();
        Instant boundary = Instant.now();
        traffic(deployed.products());
        cleanInterval(deployed.reader(), boundary);
    }

    void traffic(List<String> products) throws IOException, InterruptedException {
        Map<String, Double> baseline = new LinkedHashMap<>();
        for (String pod : products) baseline.put(pod, reads(pod));
        Map<String, Double> deltas = new LinkedHashMap<>();
        lab.await("positive product-read deltas on both pods within 120 seconds", Duration.ofSeconds(120), () -> {
            require(readyProducts().equals(products), "Product pods changed during the traffic observation");
            for (String pod : products) deltas.put(pod, reads(pod) - baseline.get(pod));
            if (!deltas.values().stream().allMatch(delta -> delta > 0))
                throw new IOException("Product read deltas " + deltas + "; latest reader pool: " + latestPool());
            return true;
        });
    }

    void cleanInterval(JsonNode reader, Instant boundary) throws IOException, InterruptedException {
        double minimum = minimumRate(reader);
        Duration interval = readerInterval(reader);
        lab.await("a fresh complete all-OK reader interval at " + minimum + "/s", interval.multipliedBy(2).plusSeconds(15), () -> {
            List<JsonNode> current = pods("app.kubernetes.io/name=shardshop-product-reader");
            require(current.size() == 1 && name(current.getFirst()).equals(name(reader))
                    && current.getFirst().path("metadata").path("uid").equals(reader.path("metadata").path("uid"))
                    && ready(current.getFirst()), "Reader pod changed during the workload observation");
            String log = lab.kubectl("logs", name(reader), "--timestamps=true", "--since-time=" + boundary,
                    "--tail=100", "--limit-bytes=65536");
            return freshCleanInterval(log, boundary, minimum);
        });
    }

    private String latestPool() throws IOException, InterruptedException {
        List<JsonNode> reader = pods("app.kubernetes.io/name=shardshop-product-reader");
        if (reader.size() != 1) return "reader unavailable";
        List<Interval> intervals = intervals(lab.kubectl("logs", name(reader.getFirst()), "--timestamps=true",
                "--tail=30", "--limit-bytes=65536"));
        return intervals.isEmpty() ? "no report" : intervals.getLast().pool();
    }

    private double reads(String pod) throws IOException, InterruptedException {
        return readCounter(lab.kubectl("get", "--raw", "/api/v1/namespaces/shardshop/pods/" + pod + ":8080/proxy/q/metrics"));
    }

    List<String> readyProducts() throws IOException, InterruptedException {
        return pods("app.kubernetes.io/name=shardshop-product").stream().filter(KindDeployment::ready)
                .map(KindDeployment::name).sorted().toList();
    }

    List<JsonNode> pods(String selector) throws IOException, InterruptedException {
        return KindLab.items(KindLab.parse(lab.kubectl("get", "pods", "-l", selector, "-o", "json")));
    }

    JsonNode resource(String type, String name) throws IOException, InterruptedException {
        var result = lab.kubectlAttempt("get", type, name, "-o", "json");
        require(result.exitCode() == 0, "Missing " + type + " " + name + "; migrate and deploy the lab first");
        return KindLab.parse(result.stdout());
    }

    JsonNode successfulSeeder(String job) throws IOException, InterruptedException {
        List<JsonNode> all = pods("batch.kubernetes.io/job-name=" + job);
        for (JsonNode pod : all) httpOnly(pod.path("spec"));
        List<JsonNode> successful = all.stream()
                .filter(pod -> pod.path("status").path("phase").asText().equals("Succeeded")).toList();
        require(successful.size() == 1, "Expected one successful seeder pod for " + job);
        JsonNode pod = successful.getFirst();
        httpOnly(pod.path("spec"));
        return pod;
    }

    String logs(String pod) throws IOException, InterruptedException { return lab.kubectl("logs", pod); }

    String readerDigest(JsonNode reader) throws IOException, InterruptedException {
        return readerDigest(lab.kubectl("logs", name(reader), "--tail=100", "--limit-bytes=65536"));
    }

    static String readerDigest(String logs) throws IOException {
        var found = new HashSet<String>();
        for (String line : logs.lines().toList()) {
            // Each interval repeats the resolved catalog identity, so rotation need not retain startup output.
            if (!line.contains("Resolved catalog-v2")
                    && !(line.contains("Product reads, interval ") && line.contains(", catalog-v2 key-to-ID SHA-256 "))) continue;
            var match = DIGEST.matcher(line);
            require(match.find(), "Reader catalog summary has no valid ID digest");
            found.add(match.group(1));
            require(!match.find(), "Reader catalog summary has multiple ID digests");
        }
        require(found.size() == 1, "Expected one consistent catalog-v2 ID digest in recent reader reports");
        return found.iterator().next();
    }

    int highWater() throws IOException, InterruptedException {
        return highWater(resource("configmap", "shardshop-snowflake-generators").path("data").path("highWaterMark").asText());
    }

    Map<String, Integer> generators(List<String> products, int highWater) throws IOException, InterruptedException {
        Map<String, String> logs = new LinkedHashMap<>();
        for (String product : products) logs.put(product, logs(product));
        return generatorIds(logs, highWater);
    }

    void catalogCounts() throws IOException, InterruptedException {
        List<String> shards = lab.shards();
        require(!shards.isEmpty(), "The invocation inventory has no shards");
        Map<String, String> counts = new LinkedHashMap<>();
        String counted = String.join(", ", List.of("catalog-v2.", KindSaga.GATE + ".", "catalog-v1.", KindSaga.RETAINED_GATE + ".")
                .stream().map(prefix -> "(SELECT count(*) FROM catalog.sellers WHERE starts_with(creation_key, '" + prefix + "')), "
                        + "(SELECT count(*) FROM catalog.products WHERE starts_with(creation_key, '" + prefix + "'))").toList());
        for (String shard : shards) counts.put(shard, sql(shard, "SELECT concat_ws(' ', " + counted
                + ", (SELECT count(*) FROM catalog.sellers) + (SELECT count(*) FROM catalog.products))").strip());
        validateCatalogCounts(counts);
    }

    /**
     * Per shard: sellers and products of catalog-v2, of the saga gate, of the retained catalog-v1 and of the
     * retained step 4.9 gate, then all rows. A lab created after step 4.12 has no retained rows.
     */
    static void validateCatalogCounts(Map<String, String> shards) throws IOException {
        long[] total = new long[9];
        for (var shard : shards.entrySet()) {
            require(shard.getValue().matches("[0-9]+( [0-9]+){8}"), "Invalid catalog count result for " + shard.getKey());
            String[] parts = shard.getValue().split(" ");
            for (int index = 0; index < total.length; index++) total[index] += Long.parseLong(parts[index]);
        }
        require(total[0] == 151 && total[1] == 201, "Expected exactly 151 catalog-v2 sellers and 201 products; found "
                + total[0] + " and " + total[1]);
        require(total[2] <= 2 && total[3] <= 2, "The saga gate fixtures are duplicated");
        require(total[4] == 0 && total[5] == 0 || total[4] == 150 && total[5] == 300,
                "Retained catalog-v1 rows must be absent or complete; found " + total[4] + " sellers and " + total[5] + " products");
        require(total[6] <= 1 && total[7] <= 2, "The retained step 4.9 gate fixtures are duplicated");
        long known = 0;
        for (int index = 0; index < 8; index++) known += total[index];
        require(total[8] == known, "The catalog has " + (total[8] - known)
                + " rows outside catalog-v2, catalog-v1 and the saga gate fixtures");
    }

    Map<String, String> catalogIds() throws IOException, InterruptedException {
        Map<String, String> ids = new LinkedHashMap<>();
        for (String shard : lab.shards()) {
            ids.put(shard, sql(shard, "BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY; "
                    + "SELECT json_build_object('sellers', coalesce((SELECT json_agg(s ORDER BY s.region, s.creation_key, s.seller_id) FROM "
                    + "(SELECT region, creation_key, seller_id::text FROM catalog.sellers) s), '[]'::json), "
                    + "'products', coalesce((SELECT json_agg(p ORDER BY p.seller_id, p.creation_key, p.product_id) FROM "
                    + "(SELECT seller_id::text, creation_key, product_id::text FROM catalog.products) p), '[]'::json)); COMMIT").strip());
        }
        return Map.copyOf(ids);
    }

    private String sql(String shard, String statement) throws IOException, InterruptedException {
        String primary = resource("cluster", shard).path("status").path("currentPrimary").asText();
        require(!primary.isEmpty(), "No current primary for " + shard);
        return lab.kubectl("exec", primary, "-c", "postgres", "--", "env",
                "PGOPTIONS=-c statement_timeout=5000 -c lock_timeout=5000", "psql", "-X", "-qAt", "-U", "postgres", "-d", "shardshop",
                "-v", "ON_ERROR_STOP=1", "-c", statement);
    }

    static String name(JsonNode pod) { return pod.path("metadata").path("name").asText(); }

    static boolean ready(JsonNode pod) {
        if (pod.path("metadata").hasNonNull("deletionTimestamp")) return false;
        for (JsonNode condition : pod.path("status").path("conditions"))
            if (condition.path("type").asText().equals("Ready") && condition.path("status").asText().equals("True")) return true;
        return false;
    }

    static boolean completed(JsonNode job) throws IOException {
        boolean complete = false;
        for (JsonNode condition : job.path("status").path("conditions")) {
            require(!(condition.path("type").asText().equals("Failed") && condition.path("status").asText().equals("True")),
                    "Seeder Job " + name(job) + " failed");
            complete |= condition.path("type").asText().equals("Complete") && condition.path("status").asText().equals("True");
        }
        return complete;
    }

    static void httpOnly(JsonNode spec) throws IOException {
        KindWorkloadPolicy.httpOnly(spec);
    }

    static String digest(String logs, String prefix) throws IOException {
        List<String> found = new ArrayList<>();
        for (String line : logs.lines().toList()) {
            if (!line.contains(prefix)) continue;
            var match = DIGEST.matcher(line);
            if (match.find()) found.add(match.group(1));
        }
        require(found.size() == 1, "Expected one " + prefix + " ID digest summary");
        return found.getFirst();
    }

    static int highWater(String value) throws IOException {
        require(value.matches("0|[1-9][0-9]{0,3}"), "Invalid generator high-water mark");
        int parsed = Integer.parseInt(value);
        require(parsed <= 1023, "Generator high-water mark exceeds 1023");
        return parsed;
    }

    static Map<String, Integer> generatorIds(Map<String, String> logs, int highWater) throws IOException {
        require(highWater >= 0 && highWater <= 1023 && !logs.isEmpty(), "Invalid generator registry or empty product set");
        Map<String, Integer> result = new LinkedHashMap<>();
        for (var entry : logs.entrySet()) {
            var match = RESERVED.matcher(entry.getValue());
            require(match.find(), "Product pod " + entry.getKey() + " has no generator reservation");
            String reserved = match.group(1);
            int id = highWater(reserved);
            require(id > 0 && id <= highWater && !match.find(), "Product pod " + entry.getKey() + " has invalid or repeated generator reservations");
            result.put(entry.getKey(), id);
        }
        require(new HashSet<>(result.values()).size() == result.size(), "Product pods share a generator ID");
        return Map.copyOf(result);
    }

    static double readCounter(String metrics) throws IOException {
        double count = 0;
        for (String line : metrics.lines().toList()) {
            var match = COUNTER.matcher(line);
            if (match.matches() && Pattern.compile("(?:^|,)method=\"GET\"(?:,|$)").matcher(match.group(1)).find()
                    && Pattern.compile("(?:^|,)uri=\"/api/v1/sellers/\\{sellerId}/products/\\{productId}\"(?:,|$)").matcher(match.group(1)).find()) {
                double value;
                try { value = Double.parseDouble(match.group(2)); }
                catch (NumberFormatException failure) { throw new IOException("Invalid product read counter", failure); }
                require(Double.isFinite(value) && value >= 0, "Invalid product read counter");
                count += value;
            }
        }
        require(Double.isFinite(count), "Product GET metric total is invalid");
        return count;
    }

    static List<Interval> intervals(String logs) throws IOException {
        List<Interval> reports = new ArrayList<>();
        for (String line : logs.lines().toList()) {
            var match = REPORT.matcher(line);
            if (!match.find()) continue;
            try {
                Instant ended = Instant.parse(line.substring(0, line.indexOf(' ')));
                double seconds = Double.parseDouble(match.group(1));
                double rate = Double.parseDouble(match.group(3));
                require(Double.isFinite(seconds) && seconds > 0 && Double.isFinite(rate) && rate >= 0, "Invalid reader interval");
                reports.add(new Interval(ended, seconds, Long.parseLong(match.group(2)), rate, match.group(4), match.group(5)));
            } catch (DateTimeParseException | NumberFormatException | IndexOutOfBoundsException failure) {
                throw new IOException("Reader interval must have a valid Kubernetes log timestamp and counters", failure);
            }
        }
        return List.copyOf(reports);
    }

    static boolean freshCleanInterval(String logs, Instant boundary, double minimum) throws IOException {
        for (Interval report : intervals(logs)) {
            // Reports round duration to tenths: add 100ms conservatively to exclude a straddling interval.
            Instant began = report.ended().minusNanos((long) Math.ceil((report.seconds() + 0.1) * 1_000_000_000));
            if (!began.isBefore(boundary) && report.requests() > 0 && report.rate() >= minimum
                    && report.outcomes().equals("OK=" + report.requests())) return true;
        }
        return false;
    }

    static double minimumRate(JsonNode reader) throws IOException {
        return Math.max(180, readerRate(reader) * 0.9);
    }

    static int readerRate(JsonNode reader) throws IOException {
        String value = environment(reader, "SHARDSHOP_READER_REQUEST_RATE", "200");
        require(value.matches("[1-9][0-9]{0,4}"), "Invalid reader request-rate setting");
        int rate = Integer.parseInt(value);
        require(rate <= 10000, "Invalid reader request-rate setting");
        return rate;
    }

    static Duration readerInterval(JsonNode reader) throws IOException {
        String value = environment(reader, "SHARDSHOP_READER_REPORT_INTERVAL", "30s");
        try {
            Duration interval = value.startsWith("P") ? Duration.parse(value)
                    : value.matches("[0-9]+ms") ? Duration.ofMillis(Long.parseLong(value.substring(0, value.length() - 2)))
                    : value.matches("[0-9]+s") ? Duration.ofSeconds(Long.parseLong(value.substring(0, value.length() - 1)))
                    : value.matches("[0-9]+m") ? Duration.ofMinutes(Long.parseLong(value.substring(0, value.length() - 1)))
                    : Duration.parse(value);
            require(interval.compareTo(Duration.ofSeconds(1)) >= 0 && interval.compareTo(Duration.ofMinutes(10)) <= 0,
                    "Unsupported reader report interval for bounded live checks");
            return interval;
        } catch (DateTimeParseException | NumberFormatException | ArithmeticException failure) {
            throw new IOException("Invalid reader report-interval setting", failure);
        }
    }

    private static String environment(JsonNode pod, String name, String fallback) throws IOException {
        for (JsonNode container : pod.path("spec").path("containers")) {
            if (!container.path("name").asText().equals("reader")) continue;
            for (JsonNode variable : container.path("env"))
                if (variable.path("name").asText().equals(name)) return variable.path("value").asText();
            return fallback;
        }
        throw new IOException("Reader container is missing");
    }

    static void require(boolean condition, String description) throws IOException {
        if (!condition) throw new IOException(description);
    }
}
