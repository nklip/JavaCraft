package dev.nklip.javacraft.shardshop.testing.cluster.capacity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nklip.javacraft.shardshop.testing.support.Json;
import dev.nklip.javacraft.shardshop.testing.cluster.Saga.Tunnel;
import dev.nklip.javacraft.shardshop.testing.cluster.ClusterLab;
import dev.nklip.javacraft.shardshop.testing.cluster.Deployment;
import dev.nklip.javacraft.shardshop.testing.cluster.DrillGuard;
import dev.nklip.javacraft.shardshop.testing.cluster.Saga;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import static dev.nklip.javacraft.shardshop.testing.cluster.Deployment.require;
import static dev.nklip.javacraft.shardshop.testing.cluster.Saga.literal;

/**
 * Holds one producer target rate and checks the write operating point of the capacity protocol. The report splits
 * the saga time at the timestamps that the databases keep, and gives the status polls, the pod placement and the CPU
 * of the nodes and pods. It uses only the permissions of the drill identity, thus it can run as a drill Job.
 */
public final class OrderCapacity {
    private static final String CONTROL = "/api/v1/order-producer/control";
    private static final String STATUS_ROUTE = "uri=\"/api/v1/buyers/{buyerId}/orders/{orderId}\"";
    private static final Duration WARMUP = Duration.ofSeconds(20);
    private static final Duration SAMPLE = Duration.ofSeconds(10);
    private static final Duration DRAIN = Duration.ofSeconds(180);
    private static final Duration REPLAY = Duration.ofSeconds(300);
    private static final Duration REPLAY_SAMPLE = Duration.ofSeconds(5);
    private static final long MINIMUM_WRITE_SAMPLES = 1024;
    private static final double MAXIMUM_WRITE_P95 = 100;
    // The protocol requires backlogs without persistent growth. 100 is approximately one second of profit intents.
    private static final double MAXIMUM_BACKLOG_GROWTH = 100;
    // A new catalog has 1,000 units of each product. At 30/s the one VND product uses approximately 290 units in 130 s.
    private static final long MINIMUM_STOCK = 400;
    private static final List<String> APPLICATIONS =
            List.of("order", "order-saga", "ledger", "order-producer", "product", "product-reader");
    private static final List<String> PHASES = List.of("write", "stock", "relay", "delivery", "ledger", "result", "server");

    private final ClusterLab lab;
    private final Saga saga;
    private final Deployment deployment;
    private Tunnel producer;

    public OrderCapacity() { this(new ClusterLab()); }
    OrderCapacity(ClusterLab lab) {
        this.lab = lab;
        this.saga = new Saga(lab);
        this.deployment = new Deployment(lab);
    }

    /** Fails after the report when one criterion fails. The guard restores the producer controls. */
    public void verify(BigDecimal rate, Duration observation, double minimumRate) throws IOException, InterruptedException {
        saga.deployed();
        try (DrillGuard guard = new DrillGuard(lab).acquire()) {
            guard.run(() -> {
                try { measure(guard, rate, observation, minimumRate); }
                finally { closeProducer(); }
            });
        }
    }

    private void measure(DrillGuard guard, BigDecimal rate, Duration observation, double minimumRate)
            throws IOException, InterruptedException {
        JsonNode original = producer("GET", "", null);
        require(original.path("workload").asText().equals("order-producer") && original.path("targetRate").isNumber()
                        && Set.of("RUNNING", "PAUSED").contains(original.path("state").asText()),
                "Order producer has no healthy live control state");
        BigDecimal originalRate = original.path("targetRate").decimalValue();
        boolean originalPaused = original.path("paused").asBoolean();
        guard.onClose("restore the prior producer rate and pause state", () -> {
            JsonNode current = producer("GET", "", null);
            if (current.path("targetRate").decimalValue().compareTo(originalRate) != 0)
                producer("PUT", "/rate", Json.newObject().put("rate", originalRate));
            if (current.path("paused").asBoolean() != originalPaused)
                producer("POST", originalPaused ? "/pause" : "/resume", null);
            JsonNode restored = producer("GET", "", null);
            return restored.path("paused").asBoolean() == originalPaused
                    && restored.path("targetRate").decimalValue().compareTo(originalRate) == 0;
        });
        pauseAndDrain();
        long stock = minimumStock();
        require(stock >= MINIMUM_STOCK, "Catalog stock is too low for a capacity run without cancellations: "
                + "the smallest catalog-v2 stock is " + stock + " units, and the run needs " + MINIMUM_STOCK);

        ObjectNode report = Json.newObject().put("targetRate", rate).put("observationSeconds", observation.toSeconds())
                .put("minimumRate", minimumRate).put("smallestStock", stock);
        report.set("producerSettings", original.path("producer").deepCopy());
        report.set("reader", reader());
        report.set("placement", placement());
        Resources before = resources();

        producer("PUT", "/rate", Json.newObject().put("rate", rate));
        producer("POST", "/resume", null);
        report.put("replaySeconds", awaitReplay());
        long resumed = System.nanoTime();
        while (System.nanoTime() - resumed < WARMUP.toNanos()) {
            TimeUnit.NANOSECONDS.sleep(Math.min(SAMPLE.toNanos(), WARMUP.toNanos() - (System.nanoTime() - resumed)));
            running(producer("GET", "", null));
        }

        JsonNode baseline = producer("GET", "", null);
        long baselineAt = System.nanoTime();
        running(baseline);
        String windowStart = clock();
        ArrayNode samples = report.putArray("samples");
        JsonNode last;
        long finalAt;
        for (int tick = 1; ; tick++) {
            TimeUnit.NANOSECONDS.sleep(Math.max(0, baselineAt + SAMPLE.toNanos() * tick - System.nanoTime()));
            boolean done = System.nanoTime() - baselineAt >= observation.toNanos();
            long requestAt = System.nanoTime();
            JsonNode status = producer("GET", "", null);
            ObjectNode sample = sample(status, requestAt - baselineAt, backlogs());
            sample.set("sagaPools", sagaPools());
            samples.add(sample);
            running(status);
            if (done) { last = status; finalAt = requestAt; break; }
        }
        String windowEnd = clock();
        Resources after = resources();
        double elapsed = (finalAt - baselineAt) / 1e9;

        List<String> failures = new ArrayList<>();
        long drainStarted = System.nanoTime();
        try {
            pauseAndDrain();
            report.put("drainSeconds", (System.nanoTime() - drainStarted) / 1e9);
        } catch (IOException failure) {
            failures.add("the backlogs did not drain after the pause: " + failure.getMessage());
        }

        long writes = delta(last, baseline, "writeLatency", "samples");
        double accepted = writes / elapsed;
        long errors = last.path("errors").asLong() - baseline.path("errors").asLong();
        long cancelled = delta(last, baseline, "cancelledOrders");
        double writeP95 = last.path("producer").path("writeLatency").path("p95Millis").asDouble();
        int bound = last.path("producer").path("maxNonTerminalOrders").asInt();
        ObjectNode result = report.putObject("result");
        result.put("elapsedSeconds", elapsed).put("newWrites", writes).put("acceptedRate", accepted)
                .put("confirmed", delta(last, baseline, "confirmedOrders")).put("cancelled", cancelled)
                .put("recovered", delta(last, baseline, "recoveredOrders")).put("errors", errors)
                .put("finalWriteP95Millis", writeP95)
                .put("finalSagaMeanMillis", last.path("producer").path("sagaLatency").path("meanMillis").asDouble())
                .put("finalSagaP95Millis", last.path("producer").path("sagaLatency").path("p95Millis").asDouble());
        result.set("traffic", traffic(last, baseline, elapsed, writes));
        if (last.hasNonNull("limit")) result.set("limit", last.path("limit").deepCopy());

        ObjectNode timeline = timeline(windowStart, windowEnd);
        JsonNode serverMean = timeline.path("phases").path("server").path("meanMillis");
        if (serverMean.isNumber())
            timeline.put("observationDelayMillis", round(result.path("finalSagaMeanMillis").asDouble() - serverMean.asDouble()));
        report.set("saga", timeline);
        report.set("resources", usage(before, after));

        if (elapsed < observation.toSeconds()) failures.add("the observation lasted only " + elapsed + " s");
        if (writes < MINIMUM_WRITE_SAMPLES) failures.add("only " + writes + " new writes, fewer than " + MINIMUM_WRITE_SAMPLES);
        if (accepted < minimumRate) failures.add("accepted rate " + accepted + "/s is below " + minimumRate + "/s");
        if (errors != 0) failures.add(errors + " failed backend requests");
        if (cancelled != 0) failures.add(cancelled + " cancelled orders");
        if (writeP95 >= MAXIMUM_WRITE_P95) failures.add("final write p95 " + writeP95 + " ms is not below 100 ms");
        for (JsonNode sample : samples) {
            if (sample.path("backpressured").asBoolean())
                failures.add("backpressure at " + sample.path("seconds").asText() + " s");
            if (sample.path("backlog").asInt() >= bound)
                failures.add("producer backlog reached its bound " + bound + " at " + sample.path("seconds").asText() + " s");
            JsonNode database = sample.path("database");
            if (database.path("pendingSagas").asInt() > bound || database.path("unpublishedCommands").asInt() > bound)
                failures.add("database order backlogs exceed the producer bound at " + sample.path("seconds").asText() + " s");
        }
        for (String backlog : List.of("pendingSagas", "unpublishedCommands", "unappliedProfit")) {
            double growth = growth(samples, backlog);
            if (growth > MAXIMUM_BACKLOG_GROWTH)
                failures.add(backlog + " grew by " + round(growth) + " during the observation");
        }
        ArrayNode failed = report.putArray("failures");
        failures.forEach(failed::add);
        System.out.println("Order capacity report: " + report);
        require(failures.isEmpty(), "Order capacity failed: " + String.join("; ", failures));
    }

    /** Mean of the last three samples minus the mean of the first three: a positive trend is persistent growth. */
    static double growth(JsonNode samples, String backlog) {
        int count = Math.min(3, samples.size() / 2);
        if (count == 0) return 0;
        double first = 0;
        double last = 0;
        for (int index = 0; index < count; index++) {
            first += samples.get(index).path("database").path(backlog).asDouble();
            last += samples.get(samples.size() - 1 - index).path("database").path(backlog).asDouble();
        }
        return (last - first) / count;
    }

    /**
     * A restarted producer first replays the earlier ordinals of its run name at the target rate, and the protocol
     * counts only new writes. Thus the warmup starts after the recovered count stays unchanged for one sample.
     */
    private double awaitReplay() throws IOException, InterruptedException {
        long started = System.nanoTime();
        long recovered = producer("GET", "", null).path("producer").path("recoveredOrders").asLong();
        while (true) {
            TimeUnit.NANOSECONDS.sleep(REPLAY_SAMPLE.toNanos());
            JsonNode status = producer("GET", "", null);
            running(status);
            long now = status.path("producer").path("recoveredOrders").asLong();
            if (now == recovered) return Math.round((System.nanoTime() - started) / 1e7) / 100.0;
            require(System.nanoTime() - started < REPLAY.toNanos(),
                    "The producer still replays earlier orders after " + REPLAY.toSeconds() + " s");
            recovered = now;
        }
    }

    private void running(JsonNode status) throws IOException {
        require(status.path("state").asText().equals("RUNNING") && !status.path("paused").asBoolean(),
                "Order producer stopped during the capacity run: state " + status.path("state").asText());
    }

    private static ObjectNode sample(JsonNode status, long nanos, JsonNode database) {
        JsonNode producer = status.path("producer");
        ObjectNode sample = Json.newObject().put("seconds", Math.round(nanos / 1e7) / 100.0)
                .put("achievedRate", status.path("achievedRate").asDouble())
                .put("inFlight", status.path("inFlight").asInt())
                .put("backlog", producer.path("nonTerminalBacklog").asInt())
                .put("backpressured", producer.path("backpressured").asBoolean())
                .put("writes", producer.path("writeLatency").path("samples").asLong())
                .put("writeP95Millis", producer.path("writeLatency").path("p95Millis").asDouble())
                .put("sagaMeanMillis", producer.path("sagaLatency").path("meanMillis").asDouble())
                .put("sagaP95Millis", producer.path("sagaLatency").path("p95Millis").asDouble())
                .put("errors", status.path("errors").asLong());
        sample.set("database", database);
        return sample;
    }

    private static long delta(JsonNode last, JsonNode baseline, String... path) {
        JsonNode end = last.path("producer");
        JsonNode start = baseline.path("producer");
        for (String field : path) { end = end.path(field); start = start.path(field); }
        return end.asLong() - start.asLong();
    }

    private static ObjectNode traffic(JsonNode last, JsonNode baseline, double elapsed, long writes) {
        ObjectNode traffic = Json.newObject();
        for (JsonNode category : last.path("traffic")) {
            String name = category.path("category").asText();
            JsonNode start = Json.newObject();
            for (JsonNode earlier : baseline.path("traffic"))
                if (earlier.path("category").asText().equals(name)) start = earlier;
            long requests = category.path("requests").asLong() - start.path("requests").asLong();
            traffic.putObject(name).put("perSecond", requests / elapsed).put("perNewWrite", writes == 0 ? 0 : (double) requests / writes)
                    .put("retries", category.path("retries").asLong() - start.path("retries").asLong())
                    .put("errors", category.path("errors").asLong() - start.path("errors").asLong());
        }
        return traffic;
    }

    /** Pause, then wait until the producer, the sagas, both outboxes and the profit intents have no open work. */
    private void pauseAndDrain() throws IOException, InterruptedException {
        require(producer("POST", "/pause", null).path("paused").asBoolean(), "Producer did not accept Pause");
        lab.await("producer finishes its submissions and observations", DRAIN, () -> {
            JsonNode status = producer("GET", "", null);
            return status.path("inFlight").asInt(-1) == 0 && status.path("producer").path("nonTerminalBacklog").asInt(-1) == 0;
        });
        lab.await("saga, outbox and profit backlogs drain", DRAIN, () -> {
            JsonNode backlogs = backlogs();
            for (String field : List.of("pendingSagas", "unpublishedCommands", "unappliedProfit", "unpublishedResults"))
                if (backlogs.path(field).asLong(-1) != 0) return false;
            return true;
        });
    }

    private ObjectNode backlogs() throws IOException, InterruptedException {
        ObjectNode total = Json.newObject().put("pendingSagas", 0).put("unpublishedCommands", 0).put("unappliedProfit", 0);
        for (String shard : lab.shards()) {
            JsonNode counts = ClusterLab.parse(saga.sql(shard, "SELECT json_build_object("
                    + "'pendingSagas', (SELECT count(*) FROM ordering.order_sagas WHERE status IN ('PENDING_STOCK','PENDING_LEDGER')), "
                    + "'unpublishedCommands', (SELECT count(*) FROM ordering.order_outbox WHERE published_at IS NULL), "
                    + "'unappliedProfit', (SELECT count(*) FROM ordering.profit_intents WHERE applied_at IS NULL AND blocked_reason IS NULL))"));
            for (String field : List.of("pendingSagas", "unpublishedCommands", "unappliedProfit"))
                total.put(field, total.path(field).asLong() + counts.path(field).asLong());
        }
        total.put("unpublishedResults",
                saga.number("ledger-db", "SELECT count(*) FROM ledger.ledger_outbox WHERE published_at IS NULL"));
        total.set("queues", queues());
        return total;
    }

    /**
     * Depth of the two saga queues. A full queue with a consumer capacity below 1 waits for acknowledgements; a
     * capacity near 1 waits for the consumer's own work. Best effort: a failed read gives an empty object.
     */
    private ObjectNode queues() throws IOException, InterruptedException {
        ObjectNode queues = Json.newObject();
        ClusterLab.CommandResult listed = lab.kubectlAttempt("exec", "shardshop-rabbitmq-0", "-c", "rabbitmq", "--",
                "rabbitmqctl", "--quiet", "--formatter=json", "list_queues", "-p", "shardshop", "name", "messages_ready",
                "messages_unacknowledged", "consumers", "consumer_capacity");
        if (listed.exitCode() != 0) return queues;
        for (JsonNode queue : ClusterLab.parse(listed.stdout()))
            if (Set.of("ledger.record-order", "order.ledger-results").contains(queue.path("name").asText()))
                queues.set(queue.path("name").asText(), ((ObjectNode) queue.deepCopy()).without("name"));
        return queues;
    }

    /** Busy and waiting connections of each shard pool of the saga process at this moment. Best effort. */
    private ObjectNode sagaPools() throws IOException, InterruptedException {
        ObjectNode pools = Json.newObject();
        List<JsonNode> pods = deployment.pods("app.kubernetes.io/name=shardshop-order-saga").stream()
                .filter(Deployment::ready).toList();
        if (pods.size() != 1) return pools;
        ClusterLab.CommandResult metrics = lab.kubectlAttempt("get", "--raw",
                "/api/v1/namespaces/shardshop/pods/" + Deployment.name(pods.getFirst()) + ":8080/proxy/q/metrics");
        if (metrics.exitCode() != 0) return pools;
        for (String shard : lab.shards()) {
            ObjectNode pool = Json.newObject();
            for (String gauge : List.of("active", "awaiting", "max")) {
                double value = metric(metrics.stdout(), "shardshop_order_db_pool_" + gauge, "shard=\"" + shard + "\"");
                if (!Double.isNaN(value)) pool.put(gauge, (int) value);
            }
            if (!pool.isEmpty()) pools.set(shard, pool);
        }
        return pools;
    }

    private long minimumStock() throws IOException, InterruptedException {
        long minimum = Long.MAX_VALUE;
        for (String shard : lab.shards()) {
            String value = saga.sql(shard, "SELECT min(stock) FROM catalog.products WHERE starts_with(creation_key, 'catalog-v2.')").strip();
            if (!value.isEmpty()) minimum = Math.min(minimum, Long.parseLong(value));
        }
        require(minimum != Long.MAX_VALUE, "The catalog-v2 products are missing");
        return minimum;
    }

    private String clock() throws IOException, InterruptedException {
        return saga.sql(lab.shards().getFirst(), "SELECT clock_timestamp()::text").strip();
    }

    /**
     * Phases of each order allocated in the window: write (allocation to order), stock (order to command outbox),
     * relay (outbox to CDC publication), delivery (publication to ledger receipt), ledger (receipt to result
     * publication), result (result publication to application on the order shard) and server (allocation to
     * application). The databases set each time at the start of its transaction.
     */
    private ObjectNode timeline(String start, String end) throws IOException, InterruptedException {
        Map<String, double[]> ledger = new HashMap<>();
        for (JsonNode row : ClusterLab.parse(saga.sql("ledger-db", "SELECT coalesce(json_agg(x),'[]'::json)::text FROM ("
                + "SELECT i.message_id::text AS cmd, " + millis("i.received_at") + " AS t4, " + millis("o.published_at") + " AS t5 "
                + "FROM ledger.ledger_inbox i LEFT JOIN ledger.ledger_outbox o ON o.message_id=i.result_message_id "
                + "WHERE i.received_at>=" + literal(start) + "::timestamptz AND i.received_at<" + literal(end)
                + "::timestamptz+interval '5 minutes') x")))
            ledger.put(row.path("cmd").asText(), new double[]{number(row.path("t4")), number(row.path("t5"))});
        Map<String, List<Double>> phases = new LinkedHashMap<>();
        PHASES.forEach(phase -> phases.put(phase, new ArrayList<>()));
        Map<String, Integer> statuses = new TreeMap<>();
        int orders = 0;
        int complete = 0;
        for (String shard : lab.shards()) {
            for (JsonNode row : ClusterLab.parse(saga.sql(shard, "SELECT coalesce(json_agg(x),'[]'::json)::text FROM ("
                    + "SELECT DISTINCT ON (a.order_id) s.status, " + millis("a.created_at") + " AS t0, " + millis("o.created_at")
                    + " AS t1, " + millis("b.created_at") + " AS t2, " + millis("b.published_at") + " AS t3, "
                    + "b.message_id::text AS cmd, " + millis("i.received_at") + " AS t6 "
                    + "FROM ordering.order_allocations a JOIN ordering.orders o USING(order_id) "
                    + "JOIN ordering.order_sagas s USING(order_id) LEFT JOIN ordering.order_outbox b ON b.saga_id=s.saga_id "
                    + "LEFT JOIN ordering.order_inbox i ON i.saga_id=s.saga_id WHERE a.created_at>=" + literal(start)
                    + "::timestamptz AND a.created_at<" + literal(end) + "::timestamptz "
                    + "ORDER BY a.order_id, b.created_at, i.received_at) x"))) {
                orders++;
                statuses.merge(row.path("status").asText(), 1, Integer::sum);
                double[] remote = ledger.getOrDefault(row.path("cmd").asText(), new double[]{Double.NaN, Double.NaN});
                double[] times = {number(row.path("t0")), number(row.path("t1")), number(row.path("t2")),
                        number(row.path("t3")), remote[0], remote[1], number(row.path("t6"))};
                boolean all = true;
                for (double time : times) all &= !Double.isNaN(time);
                if (!all || !row.path("status").asText().equals("CONFIRMED")) continue;
                complete++;
                for (int phase = 0; phase < 6; phase++) phases.get(PHASES.get(phase)).add(times[phase + 1] - times[phase]);
                phases.get("server").add(times[6] - times[0]);
            }
        }
        ObjectNode timeline = Json.newObject().put("windowStart", start).put("windowEnd", end)
                .put("orders", orders).put("completeConfirmed", complete);
        ObjectNode counts = timeline.putObject("statuses");
        statuses.forEach((status, count) -> counts.put(status, count.intValue()));
        ObjectNode summary = timeline.putObject("phases");
        phases.forEach((phase, values) -> summary.set(phase, statistics(values)));
        return timeline;
    }

    private static String millis(String column) {
        return "round((extract(epoch FROM " + column + ")*1000)::numeric,3)";
    }

    private static double number(JsonNode node) { return node.isNumber() ? node.asDouble() : Double.NaN; }

    private static ObjectNode statistics(List<Double> values) {
        List<Double> sorted = values.stream().sorted().toList();
        ObjectNode statistics = Json.newObject().put("count", sorted.size());
        if (sorted.isEmpty()) return statistics;
        double sum = 0;
        for (double value : sorted) sum += value;
        return statistics.put("meanMillis", round(sum / sorted.size())).put("p50Millis", rank(sorted, 0.50))
                .put("p95Millis", rank(sorted, 0.95)).put("maxMillis", sorted.getLast());
    }

    private static double rank(List<Double> sorted, double quantile) {
        return sorted.get((int) Math.ceil(quantile * sorted.size()) - 1);
    }

    /** Three decimals; NaN stays NaN, and {@link #number(ObjectNode, String, double)} writes it as null. */
    private static double round(double value) { return Double.isNaN(value) ? value : Math.round(value * 1000) / 1000.0; }

    private static ObjectNode number(ObjectNode node, String field, double value) {
        return Double.isFinite(value) ? node.put(field, value) : node.putNull(field);
    }

    private record Resources(long at, Map<String, String> databases, Map<String, String> applications) { }

    /**
     * Each PostgreSQL container gives its own cgroup counters and the /proc/stat of its node, because /proc/stat is
     * not in a namespace. Each JVM gives its process CPU time and the request counters of its HTTP server.
     */
    private Resources resources() throws IOException, InterruptedException {
        long at = System.nanoTime();
        Map<String, String> databases = new TreeMap<>();
        for (JsonNode pod : databasePods()) {
            // cat prints /proc/stat also when a cgroup v1 node has no cpu.stat file at this path.
            ClusterLab.CommandResult counters = lab.kubectlAttempt("exec", Deployment.name(pod), "-c", "postgres", "--",
                    "cat", "/proc/stat", "/sys/fs/cgroup/cpu.stat");
            if (counters.stdout().lines().anyMatch(line -> line.startsWith("cpu ")))
                databases.put(Deployment.name(pod) + "@" + pod.path("spec").path("nodeName").asText(), counters.stdout());
        }
        Map<String, String> applications = new TreeMap<>();
        for (String application : APPLICATIONS)
            for (JsonNode pod : deployment.pods("app.kubernetes.io/name=shardshop-" + application).stream().filter(Deployment::ready).toList()) {
                ClusterLab.CommandResult metrics = lab.kubectlAttempt("get", "--raw",
                        "/api/v1/namespaces/shardshop/pods/" + Deployment.name(pod) + ":8080/proxy/q/metrics");
                applications.put(application + "/" + Deployment.name(pod), metrics.exitCode() == 0 ? metrics.stdout() : "");
            }
        return new Resources(at, databases, applications);
    }

    private List<JsonNode> databasePods() throws IOException, InterruptedException {
        List<JsonNode> pods = new ArrayList<>();
        for (JsonNode pod : ClusterLab.items(ClusterLab.parse(lab.kubectl("get", "pods", "-l", "cnpg.io/cluster", "-o", "json")))) {
            boolean postgres = false;
            for (JsonNode container : pod.path("spec").path("containers"))
                postgres |= container.path("name").asText().equals("postgres");
            if (postgres && pod.path("status").path("phase").asText().equals("Running")) pods.add(pod);
        }
        return pods;
    }

    private static ObjectNode usage(Resources before, Resources after) throws IOException {
        double seconds = (after.at() - before.at()) / 1e9;
        ObjectNode usage = Json.newObject().put("seconds", seconds);
        ObjectNode nodes = usage.putObject("nodes");
        ObjectNode databases = usage.putObject("databasePods");
        for (Map.Entry<String, String> entry : after.databases().entrySet()) {
            String earlier = before.databases().get(entry.getKey());
            if (earlier == null) continue;
            String node = entry.getKey().substring(entry.getKey().indexOf('@') + 1);
            long[] start = procStat(earlier);
            long[] end = procStat(entry.getValue());
            double total = end[1] - start[1];
            if (!nodes.has(node) && total > 0)
                nodes.putObject(node).put("vcpus", end[3]).put("busy", round((end[0] - start[0]) / total))
                        .put("steal", round((end[2] - start[2]) / total));
            ObjectNode pod = databases.putObject(entry.getKey());
            number(pod, "cores", round((cgroup(entry.getValue(), "usage_usec") - cgroup(earlier, "usage_usec")) / 1e6 / seconds));
            number(pod, "throttledSeconds", round((cgroup(entry.getValue(), "throttled_usec") - cgroup(earlier, "throttled_usec")) / 1e6));
        }
        ObjectNode applications = usage.putObject("applications");
        for (Map.Entry<String, String> entry : after.applications().entrySet()) {
            String earlier = before.applications().getOrDefault(entry.getKey(), "");
            ObjectNode pod = number(applications.putObject(entry.getKey()), "cores",
                    round((metric(entry.getValue(), "process_cpu_time_ns_total") - metric(earlier, "process_cpu_time_ns_total")) / 1e9 / seconds));
            double gets = metric(entry.getValue(), "http_server_requests_seconds_count", "method=\"GET\"", STATUS_ROUTE)
                    - metric(earlier, "http_server_requests_seconds_count", "method=\"GET\"", STATUS_ROUTE);
            double time = metric(entry.getValue(), "http_server_requests_seconds_sum", "method=\"GET\"", STATUS_ROUTE)
                    - metric(earlier, "http_server_requests_seconds_sum", "method=\"GET\"", STATUS_ROUTE);
            if (gets > 0) pod.put("statusGets", gets).put("statusGetMeanMillis", round(time / gets * 1000));
        }
        return usage;
    }

    /** Busy, total and steal ticks of all CPUs, and the number of CPUs. */
    private static long[] procStat(String text) throws IOException {
        long[] result = new long[4];
        boolean found = false;
        for (String line : text.lines().toList()) {
            String[] fields = line.strip().split("\\s+");
            if (fields[0].equals("cpu")) {
                long total = 0;
                for (int index = 1; index <= 8; index++) total += Long.parseLong(fields[index]);
                long idle = Long.parseLong(fields[4]) + Long.parseLong(fields[5]);
                result[0] = total - idle;
                result[1] = total;
                result[2] = Long.parseLong(fields[8]);
                found = true;
            } else if (fields[0].matches("cpu[0-9]+")) {
                result[3]++;
            }
        }
        require(found, "The node CPU line is missing from /proc/stat");
        return result;
    }

    private static double cgroup(String text, String key) {
        for (String line : text.lines().toList())
            if (line.startsWith(key + " ")) return Double.parseDouble(line.substring(key.length() + 1).strip());
        return Double.NaN;
    }

    /** Sum of the samples of one metric with all the given label parts; NaN when there is no sample. */
    private static double metric(String text, String name, String... labels) {
        double sum = Double.NaN;
        for (String line : text.lines().toList()) {
            if (!line.startsWith(name + "{") && !line.startsWith(name + " ")) continue;
            boolean match = true;
            for (String label : labels) match &= line.contains(label);
            if (!match) continue;
            String rest = line.contains("}") ? line.substring(line.lastIndexOf('}') + 1) : line.substring(name.length());
            double value = Double.parseDouble(rest.strip().split("\\s+")[0]);
            sum = Double.isNaN(sum) ? value : sum + value;
        }
        return sum;
    }

    /** Zone and node of each running pod, the primaries and the leader of each quorum queue. */
    private ObjectNode placement() throws IOException, InterruptedException {
        ObjectNode placement = Json.newObject();
        Map<String, String> zones = new HashMap<>();
        ObjectNode nodes = placement.putObject("nodes");
        for (JsonNode node : ClusterLab.items(ClusterLab.parse(lab.kubectl("get", "nodes", "-o", "json")))) {
            JsonNode labels = node.path("metadata").path("labels");
            String zone = labels.path("topology.kubernetes.io/zone").asText("none");
            zones.put(Deployment.name(node), zone);
            nodes.putObject(Deployment.name(node)).put("zone", zone)
                    .put("instanceType", labels.path("node.kubernetes.io/instance-type").asText(""))
                    .put("cpu", node.path("status").path("capacity").path("cpu").asText());
        }
        ObjectNode primaries = placement.putObject("primaries");
        for (String cluster : lab.clusters())
            primaries.put(cluster, deployment.resource("cluster", cluster).path("status").path("currentPrimary").asText());
        Map<String, List<String>> byZone = new TreeMap<>();
        for (JsonNode pod : ClusterLab.items(ClusterLab.parse(lab.kubectl("get", "pods", "--field-selector=status.phase=Running", "-o", "json"))))
            byZone.computeIfAbsent(zones.getOrDefault(pod.path("spec").path("nodeName").asText(), "none"), zone -> new ArrayList<>())
                    .add(Deployment.name(pod));
        ObjectNode pods = placement.putObject("pods");
        byZone.forEach((zone, names) -> {
            ArrayNode list = pods.putArray(zone);
            names.stream().sorted().forEach(list::add);
        });
        ClusterLab.CommandResult queues = lab.kubectlAttempt("exec", "shardshop-rabbitmq-0", "-c", "rabbitmq", "--", "rabbitmqctl",
                "--quiet", "--formatter=json", "list_queues", "-p", "shardshop", "name", "type", "leader");
        ObjectNode leaders = placement.putObject("queueLeaders");
        if (queues.exitCode() == 0)
            for (JsonNode queue : ClusterLab.parse(queues.stdout()))
                if (queue.path("type").asText().equals("quorum")) leaders.put(queue.path("name").asText(), queue.path("leader").asText());
        return placement;
    }

    private JsonNode reader() throws IOException, InterruptedException {
        try (Tunnel tunnel = new Tunnel(lab, "service/shardshop-product-reader", 8080)) {
            JsonNode status = tunnel.call("GET", "/api/v1/product-reader/control", null, null, Set.of(200));
            return Json.newObject().put("targetRate", status.path("targetRate").asDouble())
                    .put("paused", status.path("paused").asBoolean()).put("achievedRate", status.path("achievedRate").asDouble());
        } catch (IOException failure) {
            return Json.newObject().put("error", failure.getMessage());
        }
    }

    /** One tunnel for the run. A failed call opens a new tunnel and tries once more; each control call is idempotent. */
    private JsonNode producer(String method, String path, JsonNode body) throws IOException, InterruptedException {
        for (int attempt = 1; ; attempt++) {
            try {
                if (producer == null) producer = new Tunnel(lab, "service/shardshop-order-producer", 8080);
                return producer.call(method, CONTROL + path, null, body, Set.of(200));
            } catch (IOException failure) {
                closeProducer();
                if (attempt == 2) throw failure;
            }
        }
    }

    private void closeProducer() {
        if (producer != null) producer.close();
        producer = null;
    }
}
