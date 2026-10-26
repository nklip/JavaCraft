package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static dev.nklip.javacraft.shardshop.testing.kind.KindTopology.equal;
import static dev.nklip.javacraft.shardshop.testing.kind.KindTopology.require;

/** Explicitly selected synchronous promotion and CDC qualification on the retained lab. */
public final class KindFailoverDrill {
    // This drill is qualified against this operator's election and fencing contract.
    static final String OPERATOR = "ghcr.io/cloudnative-pg/cloudnative-pg:1.30.1@sha256:"
            + "923c267ec29636db3bee20f993d0ec4973fa22998e1adad37da79e4d32b5bc07";
    private final KindLab lab;
    private final KindTopology topology;
    private final KindDeployment deployment;

    public KindFailoverDrill() { this(new KindLab()); }
    KindFailoverDrill(KindLab lab) {
        this.lab = lab;
        topology = new KindTopology(lab);
        deployment = new KindDeployment(lab);
    }

    public void verify() throws IOException, InterruptedException {
        requireOperator(lab.kubectl("-n", "cnpg-system", "get", "deployment", "cnpg-cloudnative-pg",
                "-o", "jsonpath={.spec.template.spec.containers[0].image}").strip());
        try (KindDrillGuard guard = new KindDrillGuard(lab).acquire()) {
            guard.run(() -> {
                deployment.deployed();
                Map<String, KindTopology.Shard> before = topology.observe();
                String selected = KindDatabaseDrill.selectShard(lab.shards(), System.getProperty("shardshop.kind.drillShard"));
                KindTopology.Shard initial = before.get(selected);
                require(initial != null && initial.standbys().size() == 2, "Failover drill requires a three-instance shard");
                // Last recovery check runs after original primary, fences and workload controls recover.
                guard.onClose("primary deployment and original peer topology after failover", () -> {
                    Map<String, KindTopology.Shard> after = topology.observe();
                    for (String peer : before.keySet())
                        equal(after.get(peer), before.get(peer), "Original primary, standbys and PVCs on " + peer);
                    deployment.deployed();
                    new KindSaga(lab).invariants();
                    return true;
                });
                controls(guard);
                KindFailoverCluster cluster = new KindFailoverCluster(lab, topology, guard, selected, initial);
                cluster.prepareSlotWitness();
                KindFailoverWitness witness = new KindFailoverWitness(lab, topology, selected);
                witness.initialize();

                // Test each initial standby's absence. The second pause also carries an
                // acknowledged but unpublished command across the planned switchover.
                phase("CDC pauses with the second standby absent while writes continue");
                String target = initial.standbys().getLast();
                cluster.fenceStandby(target);
                var first = witness.pausedCdc(target);
                cluster.restoreStandbys();
                witness.resumedCdc(first);

                phase("planned switchover preserves a pending command and its synchronized slot");
                String missing = initial.standbys().getFirst();
                cluster.fenceStandby(missing);
                var pendingSwitch = witness.pausedCdc(missing);
                var planned = witness.beforeTransition(pendingSwitch);
                witness.syncedSlot(target, planned);
                cluster.switchover(target);
                witness.afterTransition(planned);

                phase("primary failure preserves a pending command and resumes CDC");
                KindTopology.Shard current = topology.observe().get(selected);
                missing = current.standbys().getFirst();
                target = current.standbys().getLast();
                cluster.fenceStandby(missing);
                var pendingFailure = witness.pausedCdc(missing);
                var failed = witness.beforeTransition(pendingFailure);
                witness.syncedSlot(target, failed);
                cluster.failPrimary();
                witness.afterTransition(failed);

                phase("unconfirmed survivor remains read-only after primary and aligned standby loss");
                cluster.unsafeSurvivor();
                cluster.assertHealthy();
                witness.awaitConfirmed(witness.accepted());
                new KindSaga(lab).invariants();
            });
        }
    }

    static void requireOperator(String image) throws IOException {
        require(OPERATOR.equals(image), "Failover drill requires the explicitly qualified CNPG 1.30.1 image; requalify an operator change first");
    }

    private static void phase(String phase) { System.out.println("Synchronous failover drill: " + phase); }

    void controls(KindDrillGuard guard) throws IOException, InterruptedException {
        for (String workload : List.of("order-producer", "product-reader", "product-seeder")) {
            String service = workload.equals("product-seeder") ? "shardshop-product-seeder-control" : "shardshop-" + workload;
            try (KindSaga.Tunnel tunnel = new KindSaga.Tunnel(lab, "service/" + service, 8080)) {
                String path = "/api/v1/" + workload + "/control";
                JsonNode saved = tunnel.call("GET", path, null, null, Set.of(200));
                require(saved.path("targetRate").isNumber() && saved.path("paused").isBoolean(), "Missing live controls for " + workload);
                guard.onClose("restore " + workload + " live controls", () -> {
                    restoreControls(() -> controlSession(service, path), saved, new ControlTime() {
                        public long now() { return System.nanoTime(); }
                        public void sleep(long nanos) throws InterruptedException { TimeUnit.NANOSECONDS.sleep(nanos); }
                    });
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

    record ControlReply(int status, JsonNode body) { }
    interface ControlSession extends AutoCloseable {
        ControlReply exchange(String method, String suffix, JsonNode body) throws IOException, InterruptedException;
        @Override void close();
    }
    @FunctionalInterface interface ControlSessions { ControlSession open() throws IOException, InterruptedException; }
    interface ControlTime { long now(); void sleep(long nanos) throws InterruptedException; }

    /** Reopening the tunnel and replaying these exact setters is safe even after an unknown commit. */
    static void restoreControls(ControlSessions sessions, JsonNode saved, ControlTime time) throws IOException, InterruptedException {
        JsonNode original = saved.deepCopy();
        JsonNode rate = Json.newObject().set("rate", original.path("targetRate"));
        String action = original.path("paused").asBoolean() ? "/pause" : "/resume";
        long deadline = time.now() + Duration.ofSeconds(180).toNanos();
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Control restoration interrupted");
            // Tunnel readiness <=20s, three requests <=10s each, plus bounded tunnel close.
            require(time.now() + Duration.ofSeconds(55).toNanos() <= deadline, "Saved control restoration exceeded its recovery deadline");
            try (ControlSession session = sessions.open()) {
                controlOk(session.exchange("PUT", "/rate", rate));
                controlOk(session.exchange("POST", action, null));
                JsonNode actual = controlOk(session.exchange("GET", "", null));
                require(actual.path("paused").equals(original.path("paused")) && actual.path("targetRate").isNumber()
                                && actual.path("targetRate").decimalValue().compareTo(original.path("targetRate").decimalValue()) == 0,
                        "Live controls were not restored to their exact saved state");
                return;
            } catch (IOException failure) {
                if (!transientControlFailure(failure)) throw failure;
                last = failure;
                if (attempt < 2) time.sleep(Duration.ofMillis(400 + java.util.concurrent.ThreadLocalRandom.current().nextLong(200)).toNanos());
            }
        }
        throw new IOException("Saved control restoration exhausted three bounded attempts", last);
    }

    private static JsonNode controlOk(ControlReply response) throws IOException {
        if (response.status() == 503) throw new UnavailableControl();
        require(response.status() == 200, "Saved control HTTP expected 200; received " + response.status());
        return response.body();
    }

    private static boolean transientControlFailure(IOException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof UnavailableControl || cause instanceof java.io.EOFException
                    || cause instanceof java.net.SocketException || cause instanceof java.net.ConnectException
                    || cause instanceof java.net.http.HttpTimeoutException) return true;
        return false;
    }

    private static final class UnavailableControl extends IOException { }

    private ControlSession controlSession(String service, String path) throws IOException, InterruptedException {
        KindSaga.Tunnel tunnel = new KindSaga.Tunnel(lab, "service/" + service, 8080);
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build();
        return new ControlSession() {
            public ControlReply exchange(String method, String suffix, JsonNode body) throws IOException, InterruptedException {
                HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + tunnel.port + path + suffix))
                        .timeout(Duration.ofSeconds(10)).method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofByteArray(Json.bytes(body)));
                if (body != null) request.header("Content-Type", "application/json");
                HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
                return new ControlReply(response.statusCode(), response.statusCode() == 200 ? KindLab.parse(response.body()) : Json.newObject());
            }
            public void close() { client.close(); tunnel.close(); }
        };
    }

}
