package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static dev.nklip.javacraft.shardshop.testing.kind.KindTopology.equal;
import static dev.nklip.javacraft.shardshop.testing.kind.KindTopology.require;

/** Opt-in strict replica reads; recovery completes before the shared drill lock is released. */
public final class KindReplicaReadDrill {
    private final KindLab lab;
    private final KindTopology topology;
    private final KindDeployment deployment;

    public KindReplicaReadDrill() { this(new KindLab()); }
    KindReplicaReadDrill(KindLab lab) {
        this.lab = lab;
        topology = new KindTopology(lab);
        deployment = new KindDeployment(lab);
    }

    public void verify() throws IOException, InterruptedException {
        try (KindDrillGuard guard = new KindDrillGuard(lab).acquire()) {
            guard.run(() -> {
                KindDeployment.Workload original = deployment.deployed();
                Map<String, KindTopology.Shard> shards = topology.observe();
                String selected = KindDatabaseDrill.selectShard(lab.shards(), System.getProperty("shardshop.kind.drillShard"));
                KindTopology.Shard shard = shards.get(selected);
                // Registered first, therefore checked last, after profile and workload restoration.
                guard.onClose("kind-deployment primary-profile assertion", () -> {
                    deployment.deployed();
                    return true;
                });
                controls(guard);
                Map<String, Product> products = products(shards);
                Product existing = products.get(selected);
                List<String> fixtureKeys = new ArrayList<>();
                // Restore standbys/profile first, then remove only keys owned by this invocation.
                guard.onClose("delete owned replica-read products", () -> {
                    equal(topology.primary(shard.name()), shard.primary(), "Primary before fixture cleanup");
                    if (!fixtureKeys.isEmpty()) topology.sql(shard.primary(),
                            "DELETE FROM catalog.products WHERE seller_id=" + existing.seller()
                                    + " AND creation_key IN (" + String.join(",", fixtureKeys.stream().map(key -> "'" + key + "'").toList()) + ")");
                    return true;
                });
                try (KindSaga.Tunnel api = new KindSaga.Tunnel(lab, "service/shardshop-product", 8080)) {
                    Product fresh = create(fixtureKeys, api, existing);
                    assertRead(read(api, fresh), 200, fresh.id());
                }
                new KindReplicaProfile(lab).activate(guard);
                KindReplicaStandbys standbys = new KindReplicaStandbys(lab, topology, shard, guard);

                reader(true);
                deployment.traffic(deployment.readyProducts());
                deployment.cleanInterval(original.reader(), Instant.now(), 180);
                reader(false);

                try (KindSaga.Tunnel api = new KindSaga.Tunnel(lab, "service/shardshop-product", 8080)) {
                    standbys.pauseReplay();
                    Product lagged = create(fixtureKeys, api, existing);
                    equal(topology.sql(shard.primary(), "SELECT product_id::text FROM catalog.products WHERE product_id=" + lagged.id()),
                            lagged.id(), "Acknowledged primary write during replay pause");
                    for (String standby : shard.standbys())
                        equal(topology.sql(standby, "SELECT count(*) FROM catalog.products WHERE product_id=" + lagged.id()), "0", "Controlled replay lag");
                    assertRead(read(api, lagged), 404, lagged.id());
                    standbys.resumeReplay();
                    lab.await("replica reads observe the acknowledged product after replay resumes", Duration.ofSeconds(45), () -> {
                        assertRead(read(api, lagged), 200, lagged.id());
                        return true;
                    });

                    standbys.fence(List.of(shard.standbys().getFirst()));
                    lab.await("reads reconnect to the remaining standby", Duration.ofSeconds(45), () -> {
                        assertRead(read(api, existing), 200, existing.id());
                        return true;
                    });
                    for (int attempt = 0; attempt < 3; attempt++) assertRead(read(api, existing), 200, existing.id());
                    Product oneStandby = create(fixtureKeys, api, existing);
                    topology.awaitSql(shard.standbys().getLast(), "SELECT count(*) FROM catalog.products WHERE product_id=" + oneStandby.id(), "1");
                    assertRead(read(api, oneStandby), 200, oneStandby.id());

                    standbys.fence(shard.standbys());
                    unavailableOnEveryProduct(existing);
                    for (var peer : products.entrySet())
                        if (!peer.getKey().equals(selected)) assertRead(read(api, peer.getValue()), 200, peer.getValue().id());
                    // A healthy primary still has the row: 503 establishes absence of primary fallback.
                    equal(topology.sql(shard.primary(), "SELECT count(*) FROM catalog.products WHERE product_id=" + existing.id()), "1", "Primary remains readable during replica outage");
                    readerFaultInterval(original.reader());
                    standbys.restore();
                    lab.await("reads reconnect through restored standby endpoints", Duration.ofSeconds(45), () -> {
                        assertRead(read(api, existing), 200, existing.id());
                        return true;
                    });

                    try (KindReplicaQueryBlock blocked = new KindReplicaQueryBlock(lab, topology, shard, guard)) {
                        blocked.start();
                        unavailableOnEveryProduct(existing);
                        readerFaultInterval(original.reader());
                    }
                    assertRead(read(api, existing), 200, existing.id());
                }
                reader(true);
                deployment.traffic(deployment.readyProducts());
                deployment.cleanInterval(original.reader(), Instant.now(), 180);
            });
        }
    }

    private Map<String, Product> products(Map<String, KindTopology.Shard> shards) throws IOException, InterruptedException {
        Map<String, Product> result = new LinkedHashMap<>();
        for (var entry : shards.entrySet()) {
            String row = topology.sql(entry.getValue().primary(), "SELECT seller_id::text || '|' || product_id::text || '|' || currency FROM catalog.products WHERE starts_with(creation_key, 'catalog-v2.') ORDER BY product_id LIMIT 1");
            String[] parts = row.split("\\|", -1);
            require(parts.length == 3, "Each shard needs a product for the strict-read drill");
            result.put(entry.getKey(), new Product(KindSaga.id(parts[0]), KindSaga.id(parts[1]), parts[2]));
        }
        return result;
    }

    private static Product create(List<String> fixtureKeys, KindSaga.Tunnel api, Product existing)
            throws IOException, InterruptedException {
        String key = "replica-drill-" + UUID.randomUUID();
        fixtureKeys.add(key); // Includes a committed creation whose HTTP response is lost.
        JsonNode result = api.call("POST", "/api/v1/sellers/" + existing.seller() + "/products", key,
                Json.newObject().put("name", "Strict replica drill product").put("description", "Read-after-write witness")
                        .put("price", "1.00").put("unitCost", "1.00").put("currency", existing.currency()).put("initialStock", 1), Set.of(201));
        return new Product(existing.seller(), KindSaga.id(result.path("productId").asText()), existing.currency());
    }

    private void controls(KindDrillGuard guard) throws IOException, InterruptedException {
        for (String workload : List.of("order-producer", "product-reader", "product-seeder")) {
            String service = workload.equals("product-seeder") ? "shardshop-product-seeder-control" : "shardshop-" + workload;
            try (KindSaga.Tunnel tunnel = new KindSaga.Tunnel(lab, "service/" + service, 8080)) {
                String path = "/api/v1/" + workload + "/control";
                JsonNode saved = tunnel.call("GET", path, null, null, Set.of(200));
                require(saved.path("targetRate").isNumber() && saved.path("paused").isBoolean(), "Missing live controls for " + workload);
                guard.onClose("restore " + workload + " live controls", () -> {
                    try (KindSaga.Tunnel restored = new KindSaga.Tunnel(lab, "service/" + service, 8080)) {
                        restored.call("PUT", path + "/rate", null, Json.newObject().set("rate", saved.path("targetRate")), Set.of(200));
                        restored.call("POST", path + (saved.path("paused").asBoolean() ? "/pause" : "/resume"), null, null, Set.of(200));
                        JsonNode actual = restored.call("GET", path, null, null, Set.of(200));
                        require(actual.path("paused").equals(saved.path("paused"))
                                        && actual.path("targetRate").decimalValue().compareTo(saved.path("targetRate").decimalValue()) == 0,
                                "Live controls were not restored for " + workload);
                    }
                    return true;
                });
                tunnel.call("POST", path + "/pause", null, null, Set.of(200));
                lab.await(workload + " in-flight work drains", Duration.ofSeconds(180), () -> {
                    JsonNode state = tunnel.call("GET", path, null, null, Set.of(200));
                    return state.path("inFlight").asInt(-1) == 0
                            && (!workload.equals("order-producer") || state.path("producer").path("nonTerminalBacklog").asInt(-1) == 0);
                });
            }
        }
    }

    private void reader(boolean running) throws IOException, InterruptedException {
        try (KindSaga.Tunnel tunnel = new KindSaga.Tunnel(lab, "service/shardshop-product-reader", 8080)) {
            String path = "/api/v1/product-reader/control";
            if (running) tunnel.call("PUT", path + "/rate", null, Json.newObject().put("rate", 200), Set.of(200));
            tunnel.call("POST", path + (running ? "/resume" : "/pause"), null, null, Set.of(200));
            if (!running) lab.await("reader requests drain", Duration.ofSeconds(15), () ->
                    tunnel.call("GET", path, null, null, Set.of(200)).path("inFlight").asInt(-1) == 0);
        }
    }

    private void readerFaultInterval(JsonNode original) throws IOException, InterruptedException {
        Instant boundary = Instant.now();
        reader(true);
        IOException[] invalid = new IOException[1];
        try {
            lab.await("reader reports replica failures and successful peer reads without client deadlines", Duration.ofSeconds(75), () -> {
                String logs = lab.kubectl("logs", KindDeployment.name(original), "--timestamps=true", "--since-time=" + boundary,
                        "--tail=100", "--limit-bytes=65536");
                for (KindDeployment.Interval interval : KindDeployment.intervals(logs)) {
                    if (interval.ended().minusNanos((long) Math.ceil((interval.seconds() + 0.1) * 1_000_000_000)).isBefore(boundary)) continue;
                    try {
                        if (replicaOutcomes(interval.outcomes())) return true;
                    } catch (IOException failure) {
                        invalid[0] = failure;
                        return true; // Stop polling: a later interval must not hide a failed one.
                    }
                }
                return false;
            });
            if (invalid[0] != null) throw invalid[0];
        } finally {
            reader(false);
        }
    }

    private void unavailableOnEveryProduct(Product product) throws IOException, InterruptedException {
        List<String> pods = deployment.readyProducts();
        require(pods.size() == 2, "Both product pods must participate in the replica outage check");
        for (String pod : pods) {
            try (KindSaga.Tunnel api = new KindSaga.Tunnel(lab, "pod/" + pod, 8080)) {
                assertRead(read(api, product), 503, product.id());
            }
        }
    }

    static boolean replicaOutcomes(String outcomes) throws IOException {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String part : outcomes.split(", ")) {
            String[] pair = part.split("=", -1);
            require(pair.length == 2 && pair[1].matches("[0-9]+"), "Malformed reader outcomes");
            counts.put(pair[0], Long.parseLong(pair[1]));
        }
        require(counts.keySet().stream().allMatch(Set.of("OK", "REPLICA_UNAVAILABLE")::contains),
                "Reader reported an unexpected outcome during replica outage: " + outcomes);
        return counts.getOrDefault("OK", 0L) > 0 && counts.getOrDefault("REPLICA_UNAVAILABLE", 0L) > 0;
    }

    private static Reply read(KindSaga.Tunnel tunnel, Product product) throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(1)).build()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + tunnel.port
                    + "/api/v1/sellers/" + product.seller() + "/products/" + product.id())).timeout(Duration.ofSeconds(5)).GET().build();
            long started = System.nanoTime();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new Reply(response.statusCode(), KindLab.parse(response.body()), Duration.ofNanos(System.nanoTime() - started));
        }
    }

    static void assertRead(Reply reply, int status, String product) throws IOException {
        require(reply.status() == status, "Replica read expected HTTP " + status + ", received " + reply.status());
        require(reply.elapsed().compareTo(Duration.ofSeconds(4)) < 0, "Replica read exceeded the four-second server budget");
        if (status == 200) equal(reply.body().path("productId").asText(), product, "Read product identity");
        else equal(reply.body().path("code").asText(), status == 503 ? "READ_REPLICA_UNAVAILABLE" : "PRODUCT_NOT_FOUND", "Replica read error code");
        System.out.println("Strict replica read: HTTP " + status + " in " + reply.elapsed().toMillis() + " ms");
    }

    record Reply(int status, JsonNode body, Duration elapsed) { }
    private record Product(String seller, String id, String currency) { }
}
