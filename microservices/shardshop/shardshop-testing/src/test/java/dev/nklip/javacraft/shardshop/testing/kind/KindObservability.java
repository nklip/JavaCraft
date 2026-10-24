package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Read-only observation of application gauges and the broker/database's native backlog counters. */
public final class KindObservability {
    private static final Pattern SAMPLE = Pattern.compile("([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\\{([^}]*)})?\\s+([^\\s]+)(?:\\s+[0-9]+)?");
    private static final Pattern LABEL = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)=\"([^\"]*)\"");
    private static final List<String> POOL = List.of("active", "available", "awaiting", "max");
    private static final List<String> STATUSES = List.of("PENDING_STOCK", "PENDING_LEDGER", "CONFIRMED", "CANCELLED");
    private static final List<String> QUEUES = List.of("ledger.record-order", "order.ledger-results",
            "ledger.record-order.dlq", "order.ledger-results.dlq");
    private final KindLab lab;

    public KindObservability() { this(new KindLab()); }
    KindObservability(KindLab lab) { this.lab = lab; }

    public void verify() throws IOException, InterruptedException {
        List<String> shards = lab.shards();
        checkOrderPods("order", 2, shards);
        checkOrderPods("order-saga", 1, shards);
        List<JsonNode> ledgers = pods("shardshop-ledger");
        require(ledgers.size() == 1 && KindDeployment.ready(ledgers.getFirst()), "Expected one ready ledger pod");
        String ledger = KindDeployment.name(ledgers.getFirst());
        lab.await("fresh ledger metrics on " + ledger, Duration.ofSeconds(45), () -> {
            validateLedger(parse(metrics(ledger)));
            return true;
        });
        checkBroker();
        checkReplication(shards);
    }

    private void checkOrderPods(String process, int count, List<String> shards) throws IOException, InterruptedException {
        List<JsonNode> selected = pods("shardshop-" + process);
        require(selected.size() == count && selected.stream().allMatch(KindDeployment::ready),
                "Expected " + count + " ready " + process + " pod(s)");
        for (JsonNode pod : selected) {
            String name = KindDeployment.name(pod);
            lab.await("fresh " + process + " metrics on " + name, Duration.ofSeconds(45), () -> {
                validateOrder(parse(metrics(name)), process.equals("order") ? "http" : "saga", shards);
                return true;
            });
        }
    }

    private List<JsonNode> pods(String application) throws IOException, InterruptedException {
        return KindLab.items(KindLab.parse(lab.kubectl("get", "pods", "-l",
                "app.kubernetes.io/name=" + application, "-o", "json")));
    }

    private String metrics(String pod) throws IOException, InterruptedException {
        return lab.kubectl("get", "--raw", "/api/v1/namespaces/shardshop/pods/" + pod + ":8080/proxy/q/metrics");
    }

    static void validateOrder(List<Series> samples, String process, List<String> shards) throws IOException {
        for (String shard : shards) {
            Map<String, String> tags = Map.of("shard", shard, "process", process);
            for (String field : POOL) nonnegative(samples, "shardshop_order_db_pool_" + field, tags);
            nonnegative(samples, "shardshop_order_db_pool_max_used", tags);
            require(value(samples, "shardshop_order_db_pool_max", tags) > 0, shard + " order pool has no capacity");
            if (process.equals("http")) {
                require(samples.stream().noneMatch(series -> series.name().equals("shardshop_order_metrics_snapshot_success")
                        && series.tags().equals(tags)), "HTTP pod exposes saga snapshot metrics");
                continue;
            }
            require(value(samples, "shardshop_order_metrics_snapshot_success", tags) == 1,
                    shard + " order metrics snapshot failed");
            fresh(samples, "shardshop_order_metrics_snapshot_age_seconds", tags);
            for (String metric : List.of("outbox_pending", "outbox_oldest_pending_seconds",
                    "sagas_reconciliation_exhausted", "quarantine_orphan_rows", "quarantine_orphan_unresolved",
                    "quarantine_conflicting_result_rows", "cdc_retained_wal_bytes", "cdc_checkpoint_lag_bytes")) {
                nonnegative(samples, "shardshop_order_" + metric, tags);
            }
            for (String status : STATUSES) nonnegative(samples, "shardshop_order_sagas",
                    Map.of("shard", shard, "process", process, "status", status));
            for (String metric : List.of("cdc_enabled", "cdc_slot_exists", "cdc_slot_active", "cdc_checkpoint_exists"))
                require(value(samples, "shardshop_order_" + metric, tags) == 1,
                        shard + " " + metric + " is not ready");
            require(value(samples, "shardshop_order_cdc_recovery_required", tags) == 0,
                    shard + " CDC requires explicit recovery");
            binary(samples, "shardshop_order_cdc_retrying", tags);
        }
    }

    static void validateLedger(List<Series> samples) throws IOException {
        Map<String, String> tags = Map.of();
        for (String field : POOL) nonnegative(samples, "shardshop_ledger_pool_" + field, tags);
        require(value(samples, "shardshop_ledger_pool_max", tags) > 0, "Ledger pool has no capacity");
        require(value(samples, "shardshop_ledger_snapshot_success", tags) == 1,
                "Ledger metrics snapshot failed");
        fresh(samples, "shardshop_ledger_snapshot_age_seconds", tags);
        for (String metric : List.of("pending_results", "oldest_pending_age_seconds", "conflicting_commands"))
            nonnegative(samples, "shardshop_ledger_" + metric, tags);
    }

    private void checkBroker() throws IOException, InterruptedException {
        String encoded = new KindDeployment(lab).resource("secret", "shardshop-rabbitmq")
                .path("data").path("admin-password").asText();
        require(!encoded.isBlank(), "RabbitMQ management credential is missing");
        String password;
        try { password = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException invalid) { throw new IOException("RabbitMQ management credential is invalid"); }
        String authorization = "Basic " + Base64.getEncoder().encodeToString(
                ("shardshop-admin:" + password).getBytes(StandardCharsets.UTF_8));
        try (KindSaga.Tunnel tunnel = new KindSaga.Tunnel(lab, "service/shardshop-rabbitmq", 15672);
             HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                     .connectTimeout(Duration.ofSeconds(3)).build()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + tunnel.port
                            + "/api/queues/shardshop"))
                    .timeout(Duration.ofSeconds(10)).header("Authorization", authorization).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            require(response.statusCode() == 200, "RabbitMQ management queue counters are unavailable");
            try { validateQueues(KindLab.parse(response.body())); }
            catch (RuntimeException malformed) { throw new IOException("RabbitMQ management queue counters are not JSON"); }
        }
    }

    static void validateQueues(JsonNode queues) throws IOException {
        require(queues.isArray(), "RabbitMQ queue counters are missing");
        Map<String, JsonNode> byName = new HashMap<>();
        for (JsonNode queue : queues) {
            String name = queue.path("name").asText();
            require(byName.put(name, queue) == null, "Duplicate RabbitMQ queue counters: " + name);
        }
        for (String name : QUEUES) {
            JsonNode queue = byName.get(name);
            require(queue != null, "Missing RabbitMQ queue counters for " + name);
            counter(queue, "messages_ready", name);
            counter(queue, "messages_unacknowledged", name);
            counter(queue, "message_bytes_ready", name);
            counter(queue, "message_bytes_unacknowledged", name);
            JsonNode policy = queue.path("effective_policy_definition");
            if (policy.isTextual()) {
                try { policy = KindLab.parse(policy.asText()); }
                catch (RuntimeException malformed) { throw new IOException(name + " has invalid effective policy"); }
            }
            if (policy.isArray()) {
                var converted = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
                for (JsonNode item : policy) {
                    require(item.isArray() && item.size() == 3 && item.get(0).isTextual(),
                            name + " has invalid typed effective policy");
                    require(!converted.has(item.get(0).asText()), name + " has duplicate effective policy fields");
                    converted.set(item.get(0).asText(), item.get(2));
                }
                policy = converted;
            }
            require(policy.isObject() && policy.path("overflow").asText().equals("reject-publish"),
                    name + " must reject publication at its capacity limit");
            long countLimit = counter(policy, "max-length", name);
            long byteLimit = counter(policy, "max-length-bytes", name);
            require(countLimit > 0 && byteLimit > 0, name + " has no effective capacity limit");
        }
    }

    private void checkReplication(List<String> shards) throws IOException, InterruptedException {
        KindTopology topology = new KindTopology(lab);
        for (String shard : shards) {
            String primary = topology.primary(shard);
            String nativeMetrics = lab.kubectl("get", "--raw", "/api/v1/namespaces/shardshop/pods/"
                    + primary + ":9187/proxy/metrics");
            require(nativeMetrics.contains("cnpg_"), shard + " CNPG metrics endpoint is empty");
            String result = topology.sql(primary, "SELECT count(*), "
                    + "COALESCE(max(pg_wal_lsn_diff(pg_current_wal_lsn(), replay_lsn)), 0)::bigint "
                    + KindTopology.STANDBY_SENDERS_SQL + " AND state='streaming'");
            validateReplication(result, shard);
        }
    }

    static void validateReplication(String output, String shard) throws IOException {
        String[] values = output.strip().split("\\|", -1);
        require(values.length == 2 && values[0].matches("[0-9]+") && values[1].matches("[0-9]+"),
                shard + " replication counters are invalid");
        try {
            require(Long.parseLong(values[0]) >= 2, shard + " has fewer than two streaming standbys");
            Long.parseLong(values[1]);
        } catch (NumberFormatException invalid) {
            throw new IOException(shard + " replication counters overflow", invalid);
        }
    }

    static List<Series> parse(String text) throws IOException {
        List<Series> samples = new ArrayList<>();
        for (String line : text.lines().toList()) {
            if (!line.startsWith("shardshop_")) continue;
            Matcher found = SAMPLE.matcher(line);
            require(found.matches(), "Invalid Prometheus sample");
            Map<String, String> tags = new HashMap<>();
            String labels = found.group(2);
            if (labels != null && !labels.isEmpty()) {
                int position = 0;
                while (position < labels.length()) {
                    Matcher label = LABEL.matcher(labels);
                    label.region(position, labels.length());
                    require(label.lookingAt(), "Invalid Prometheus labels");
                    require(tags.put(label.group(1), label.group(2)) == null, "Duplicate Prometheus label");
                    position = label.end();
                    if (position < labels.length()) {
                        require(labels.charAt(position) == ',', "Invalid Prometheus label separator");
                        position++;
                    }
                }
            }
            double value;
            try {
                value = switch (found.group(3)) {
                    case "+Inf" -> Double.POSITIVE_INFINITY;
                    case "-Inf" -> Double.NEGATIVE_INFINITY;
                    default -> Double.parseDouble(found.group(3));
                };
            }
            catch (NumberFormatException invalid) { throw new IOException("Invalid Prometheus value", invalid); }
            samples.add(new Series(found.group(1), Map.copyOf(tags), value));
        }
        return List.copyOf(samples);
    }

    static double value(List<Series> samples, String metric, Map<String, String> tags) throws IOException {
        List<Series> matches = samples.stream().filter(sample -> sample.name().equals(metric)
                && sample.tags().equals(tags)).toList();
        require(matches.size() == 1, "Expected exactly one " + metric + tags + " sample");
        double value = matches.getFirst().value();
        require(Double.isFinite(value), "Non-finite " + metric + tags + " sample");
        return value;
    }

    private static void fresh(List<Series> samples, String name, Map<String, String> tags) throws IOException {
        double age = value(samples, name, tags);
        require(age >= 0 && age <= 30, name + tags + " is stale");
    }

    private static void nonnegative(List<Series> samples, String name, Map<String, String> tags) throws IOException {
        require(value(samples, name, tags) >= 0, name + tags + " is negative");
    }

    private static void binary(List<Series> samples, String name, Map<String, String> tags) throws IOException {
        double value = value(samples, name, tags);
        require(value == 0 || value == 1, name + tags + " is not a binary state");
    }

    private static long counter(JsonNode node, String field, String owner) throws IOException {
        JsonNode value = node.path(field);
        require(value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0,
                owner + " has invalid " + field);
        return value.longValue();
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }

    record Series(String name, Map<String, String> tags, double value) { }
}
