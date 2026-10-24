package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.Json;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static dev.nklip.javacraft.shardshop.testing.kind.KindTopology.require;

/** Explicit outage drill: a parked result is recovered by a new reconciliation command. */
public final class KindRetryRecoveryDrill {
    private static final String FENCE = "cnpg.io/fencedInstances";
    private static final String LEDGER = "shardshop-ledger";
    private static final String RESULT_DLQ = "order.ledger-results.dlq";
    private final KindLab lab;
    private final KindSaga saga;
    private final KindTopology topology;
    private final KindDeployment deployment;

    public KindRetryRecoveryDrill() { this(new KindLab()); }

    KindRetryRecoveryDrill(KindLab lab) {
        this.lab = lab;
        saga = new KindSaga(lab);
        topology = new KindTopology(lab);
        deployment = new KindDeployment(lab);
    }

    public void verify() throws IOException, InterruptedException {
        try (KindDrillGuard guard = new KindDrillGuard(lab).acquire()) {
            guard.run(() -> {
                saga.deployed();
                saga.normalPolicy();
                new KindRabbitMq(lab).verify();
                topology.publishedTopology();
                topology.observe();
                PodState source = podState(saga.pod("order-saga"), "order");
                PodState broker = brokerState();

                DeploymentState ledger = deploymentState(LEDGER);
                require(ledger.replicas() == 1, "Retry drill requires one ledger replica");
                // Register in reverse recovery order. The producer resumes only after the
                // ledger and shard have recovered; the ledger remains stopped until the
                // scoped command is confirmed in the broker.
                ControlState producer = producerControl();
                guard.onClose("restore the producer's live pause and rate settings", () -> {
                    restoreProducer(producer);
                    return true;
                });
                pauseProducer(producer);
                drained();
                require(queueDepth("ledger.record-order") == 0 && queueDepth("order.ledger-results") == 0,
                        "Processing queues must be drained before a scoped outage");

                Buyer buyer = buyer();
                JsonNode product = product();
                long parkedBefore = queueDepth(RESULT_DLQ);
                require(parkedBefore == 0, "Existing parked results require inspection before the scoped retry drill");
                String run = "kind-retry-recovery-" + UUID.randomUUID();
                guard.onClose("restore the original ledger replica count", () -> {
                    replicas(LEDGER, ledger, ledger.replicas());
                    readyDeployment("ledger", ledger.replicas());
                    return true;
                });
                replicas(LEDGER, ledger, 0);
                noPods("ledger");

                try (KindSaga.Tunnel http = new KindSaga.Tunnel(lab, "service/shardshop-order", 8080)) {
                    String orderId = submit(http, buyer.id(), product, run);
                    String home = buyer.shard();
                    lab.await("scoped command is confirmed while ledger is stopped", Duration.ofSeconds(90), () ->
                            saga.number(home, "SELECT count(*) FROM ordering.order_outbox o JOIN ordering.order_sagas s USING(saga_id) "
                                    + "WHERE s.order_id=" + orderId + " AND o.published_at IS NOT NULL") == 1
                                    && queueDepth("ledger.record-order") == 1);
                    JsonNode original = command(home, orderId);
                    String firstMessage = KindSaga.id(original.path("messageId").asText());
                    String firstResult = KindSaga.id(original.path("resultMessageId").asText());
                    require(saga.sql(home, "SELECT status FROM ordering.order_sagas WHERE order_id=" + orderId)
                                    .strip().equals("PENDING_LEDGER"), "Scoped saga must await its ledger result");

                    KindTopology.Shard shard = topology.observe().get(home);
                    require(shard != null && shard.standbys().size() == 2,
                            "Buyer shard must have a healthy three-instance quorum");
                    JsonNode cluster = topology.cluster(home);
                    KindDatabaseDrill.rejectPreexistingFence(cluster);
                    FenceState initialFence = fenceState(cluster);
                    FenceState outageFence = new FenceState(true, fenceValue(shard));
                    guard.onClose("restore all buyer shard instances and their original fence", () -> {
                        restoreFence(home, cluster.path("metadata").path("uid").asText(), initialFence, outageFence);
                        topology.ready("cluster/" + home);
                        topology.observe();
                        return true;
                    });
                    patchFence(home, cluster, outageFence);
                    for (String instance : instances(shard))
                        lab.kubectl(Duration.ofSeconds(140), "--request-timeout=130s", "wait",
                                "--for=condition=Ready=false", "pod/" + instance, "--timeout=120s");

                    replicas(LEDGER, ledger, ledger.replicas());
                    readyDeployment("ledger", ledger.replicas());
                    lab.await("scoped immutable ledger result published during buyer shard outage", Duration.ofSeconds(90), () ->
                            saga.number("ledger-db", "SELECT count(*) FROM ledger.ledger_result_ids r "
                                    + "JOIN ledger.ledger_outbox o ON o.message_id=r.result_message_id "
                                    + "WHERE r.order_id=" + orderId + " AND r.result_message_id=" + firstResult
                                    + " AND o.published_at IS NOT NULL") == 1);
                    String decision = saga.sql("ledger-db", "SELECT row_to_json(o)::text FROM ledger.ledger_operations o "
                            + "WHERE order_id=" + orderId).strip();
                    require(!decision.isEmpty(), "Ledger did not retain the scoped decision");
                    JsonNode storedResult = KindLab.parse(saga.sql("ledger-db", "SELECT envelope::text FROM ledger.ledger_result_ids "
                            + "WHERE result_message_id=" + firstResult));
                    long publishedNanos = System.nanoTime();
                    lab.await("result delivery exhausts the broker retry limit and parks", Duration.ofSeconds(100), () ->
                            queueDepth(RESULT_DLQ) == parkedBefore + 1);
                    long parkedMillis = Duration.ofNanos(System.nanoTime() - publishedNanos).toMillis();
                    require(queueDepth("order.ledger-results") == 0,
                            "Result source queue still contains the scoped exhausted delivery");
                    require(podState(saga.pod("order-saga"), "order").equals(source)
                                    && brokerState().equals(broker),
                            "Saga source or broker restarted during the scoped retry outage");
                    brokerHealthy();
                    try (ParkedDelivery parked = inspectParkedResult(firstResult, storedResult)) {
                        System.out.println("Scoped order " + orderId + " result " + firstResult
                                + " parked with delivery_limit after " + parkedMillis
                                + " ms since confirmed result publication observation; x-delivery-count="
                                + parked.failureCount());
                        restoreFence(home, cluster.path("metadata").path("uid").asText(), initialFence, outageFence);
                        topology.ready("cluster/" + home);
                        topology.observe();
                        lab.await("automatic reconciliation publishes a new scoped command", Duration.ofSeconds(150), () ->
                                saga.number(home, "SELECT count(*) FROM ordering.order_outbox o JOIN ordering.order_sagas s "
                                        + "USING(saga_id) WHERE s.order_id=" + orderId + " AND o.message_id<>" + firstMessage
                                        + " AND o.published_at IS NOT NULL") == 1);
                        lab.await("reconciled order confirms with exactly one set of effects", Duration.ofSeconds(150), () -> {
                            require(saga.sql(home, "SELECT status FROM ordering.orders WHERE order_id=" + orderId)
                                    .strip().equals("CONFIRMED"), "Scoped order has not confirmed");
                            saga.verifyOrder(home, orderId, "CONFIRMED");
                            return true;
                        });
                        require(saga.number(home, "SELECT reconciliation_attempts FROM ordering.order_sagas WHERE order_id=" + orderId) == 1,
                                "One automatic reconciliation attempt must recover the scoped order");
                        require(saga.number(home, "SELECT count(*) FROM ordering.order_result_ids r JOIN ordering.order_sagas s "
                                + "USING(saga_id) WHERE s.order_id=" + orderId) == 2,
                                "Recovery must reserve exactly one new command/result pair");
                        require(saga.number("ledger-db", "SELECT count(*) FROM ledger.ledger_result_ids WHERE order_id=" + orderId) == 2,
                                "Ledger must regenerate a stored result for the reconciliation command");
                        require(decision.equals(saga.sql("ledger-db", "SELECT row_to_json(o)::text FROM ledger.ledger_operations o "
                                + "WHERE order_id=" + orderId).strip()), "Replay changed the permanent ledger decision");
                        JsonNode replayedResult = KindLab.parse(saga.sql("ledger-db", "SELECT envelope::text FROM ledger.ledger_result_ids "
                                + "WHERE order_id=" + orderId + " AND result_message_id<>" + firstResult));
                        require(storedResult.isObject() && replayedResult.isObject()
                                        && storedResult.hasNonNull("messageId") && replayedResult.hasNonNull("messageId")
                                        && storedResult.hasNonNull("occurredAt") && replayedResult.hasNonNull("occurredAt"),
                                "Stored result envelopes are missing transport fields");
                        require(!replayedResult.path("messageId").equals(storedResult.path("messageId")),
                                "Reconciled result reused the parked transport identity");
                        ObjectNode originalDecisionResult = ((ObjectNode) storedResult).deepCopy();
                        ObjectNode replayDecisionResult = ((ObjectNode) replayedResult).deepCopy();
                        originalDecisionResult.remove(List.of("messageId", "occurredAt"));
                        replayDecisionResult.remove(List.of("messageId", "occurredAt"));
                        require(originalDecisionResult.equals(replayDecisionResult),
                                "Reconciled result changed the immutable ledger outcome or correlation");
                        require(queueDepth(RESULT_DLQ) == 1,
                                "Original parked diagnostic was consumed before operator inspection");
                        saga.invariants();
                        require(podState(saga.pod("order-saga"), "order").equals(source)
                                        && brokerState().equals(broker),
                                "Saga source or broker restarted before recovery completed");
                        brokerHealthy();
                        parked.acknowledge();
                        lab.await("inspected scoped parked result is removed", Duration.ofSeconds(20), () ->
                                queueDepth(RESULT_DLQ) == 0);
                        System.out.println("Scoped order " + orderId
                                + " confirmed after one reconciliation; inspected parked result removed");
                    }
                }
            });
        }
    }

    private void drained() throws IOException, InterruptedException {
        lab.await("producer stops new submissions and drains its observer backlog", Duration.ofSeconds(180), () -> {
            JsonNode state = producerStatus();
            require(state.path("paused").asBoolean(), "Producer pause state changed outside the drill");
            return state.path("inFlight").asInt(-1) == 0
                    && state.path("producer").path("nonTerminalBacklog").asInt(-1) == 0;
        });
        lab.await("existing order work drains before the scoped outage", Duration.ofSeconds(180), () -> {
            for (String shard : lab.shards())
                if (saga.number(shard, "SELECT count(*) FROM ordering.order_sagas WHERE status IN "
                        + "('PENDING_STOCK','PENDING_LEDGER') OR (status='CANCELLED' AND stock_released_at IS NULL)") != 0)
                    return false;
            return true;
        });
    }

    private ControlState producerControl() throws IOException, InterruptedException {
        JsonNode status = producerStatus();
        require(status.path("workload").asText().equals("order-producer")
                        && Set.of("RUNNING", "PAUSED").contains(status.path("state").asText())
                        && status.path("targetRate").isNumber(),
                "Order producer has no healthy live control state");
        return new ControlState(status.path("targetRate").decimalValue(), status.path("paused").asBoolean());
    }

    private JsonNode producerStatus() throws IOException, InterruptedException {
        try (KindSaga.Tunnel tunnel = new KindSaga.Tunnel(lab, "service/shardshop-order-producer", 8080)) {
            return tunnel.call("GET", "/api/v1/order-producer/control", null, null, Set.of(200));
        }
    }

    private void pauseProducer(ControlState original) throws IOException, InterruptedException {
        if (original.paused()) return;
        try (KindSaga.Tunnel tunnel = new KindSaga.Tunnel(lab, "service/shardshop-order-producer", 8080)) {
            require(tunnel.call("POST", "/api/v1/order-producer/control/pause", null, null, Set.of(200))
                    .path("paused").asBoolean(), "Producer did not accept Pause");
        }
    }

    private void restoreProducer(ControlState original) throws IOException, InterruptedException {
        try (KindSaga.Tunnel tunnel = new KindSaga.Tunnel(lab, "service/shardshop-order-producer", 8080)) {
            String root = "/api/v1/order-producer/control";
            JsonNode status = tunnel.call("GET", root, null, null, Set.of(200));
            if (status.path("targetRate").decimalValue().compareTo(original.targetRate()) != 0)
                tunnel.call("PUT", root + "/rate", null,
                        Json.newObject().put("rate", original.targetRate()), Set.of(200));
            if (status.path("paused").asBoolean() != original.paused())
                tunnel.call("POST", root + (original.paused() ? "/pause" : "/resume"), null, null, Set.of(200));
            JsonNode restored = tunnel.call("GET", root, null, null, Set.of(200));
            require(restored.path("paused").asBoolean() == original.paused()
                            && restored.path("targetRate").decimalValue().compareTo(original.targetRate()) == 0,
                    "Producer live controls were not restored");
        }
    }

    private Buyer buyer() throws IOException, InterruptedException {
        for (String shard : lab.shards()) {
            String id = saga.sql(shard, "SELECT buyer_id::text FROM ordering.buyers ORDER BY buyer_id LIMIT 1").strip();
            if (!id.isEmpty()) return new Buyer(KindSaga.id(id), shard);
        }
        throw new IOException("No seeded buyer is available for the scoped retry drill");
    }

    private JsonNode product() throws IOException, InterruptedException {
        for (String shard : lab.shards()) {
            String row = saga.sql(shard, "SELECT row_to_json(x)::text FROM (SELECT seller_id::text, product_id::text, "
                    + "stock FROM catalog.products WHERE currency='USD' AND stock>4 "
                    + "AND starts_with(creation_key, 'catalog-v2.') ORDER BY product_id LIMIT 1) x").strip();
            if (!row.isEmpty()) return KindLab.parse(row);
        }
        throw new IOException("No seeded catalog-v2 USD product has enough remaining stock");
    }

    private String submit(KindSaga.Tunnel http, String buyer, JsonNode product, String run)
            throws IOException, InterruptedException {
        String base = "/api/v1/buyers/" + buyer;
        JsonNode allocation = http.call("POST", base + "/order-allocations", null,
                Json.newObject().put("runName", run).put("requestOrdinal", "0"), Set.of(201));
        String orderId = KindSaga.id(allocation.path("orderId").asText());
        ObjectNode request = Json.newObject().put("currency", "USD");
        request.putArray("items").addObject().put("sellerId", KindSaga.id(product.path("seller_id").asText()))
                .put("productId", KindSaga.id(product.path("product_id").asText())).put("quantity", 1);
        http.call("PUT", base + "/orders/" + orderId, null, request, Set.of(202));
        return orderId;
    }

    private JsonNode command(String home, String orderId) throws IOException, InterruptedException {
        String envelope = saga.sql(home, "SELECT o.envelope::text FROM ordering.order_outbox o "
                + "JOIN ordering.order_sagas s USING(saga_id) WHERE s.order_id=" + orderId).strip();
        require(!envelope.isEmpty(), "Scoped published command envelope is missing");
        return KindLab.parse(envelope);
    }

    private long queueDepth(String name) throws IOException, InterruptedException {
        JsonNode queues = KindLab.parse(lab.kubectl("exec", "shardshop-rabbitmq-0", "-c", "rabbitmq", "--",
                "rabbitmqctl", "--quiet", "--timeout", "10", "--formatter=json", "list_queues", "-p", "shardshop",
                "name", "messages_ready", "messages_unacknowledged"));
        for (JsonNode queue : queues) if (queue.path("name").asText().equals(name)) {
            long ready = queue.path("messages_ready").asLong(-1);
            long unacknowledged = queue.path("messages_unacknowledged").asLong(-1);
            require(ready >= 0 && unacknowledged >= 0, "Broker queue counters are unavailable for " + name);
            return ready + unacknowledged;
        }
        throw new IOException("Broker queue is missing: " + name);
    }

    private PodState brokerState() throws IOException, InterruptedException {
        return podState(deployment.resource("pod", "shardshop-rabbitmq-0"), "rabbitmq");
    }

    private static PodState podState(JsonNode pod, String containerName) throws IOException {
        String uid = pod.path("metadata").path("uid").asText();
        for (JsonNode container : pod.path("status").path("containerStatuses"))
            if (container.path("name").asText().equals(containerName)) {
                String containerId = container.path("containerID").asText();
                int restarts = container.path("restartCount").asInt(-1);
                require(!uid.isBlank() && !containerId.isBlank() && restarts >= 0,
                        containerName + " has no stable pod/container identity or restart counter");
                return new PodState(uid, containerId, restarts);
            }
        throw new IOException(containerName + " container status is missing");
    }

    private void brokerHealthy() throws IOException, InterruptedException {
        for (String check : List.of("check_running", "check_local_alarms"))
            lab.kubectl(Duration.ofSeconds(20), "exec", "shardshop-rabbitmq-0", "-c", "rabbitmq", "--",
                    "rabbitmq-diagnostics", "-q", "-t", "10", check);
    }

    private ParkedDelivery inspectParkedResult(String resultId, JsonNode storedResult)
            throws IOException, InterruptedException {
        // A nonempty DLQ was rejected before injection. Hold this exact delivery
        // unacknowledged until independent reconciliation has completed.
        String encoded = deployment.resource("secret", "shardshop-rabbitmq").path("data")
                .path("admin-password").asText();
        require(!encoded.isBlank(), "Broker administrator credential is unavailable for diagnostic inspection");
        String password;
        try { password = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException invalid) { throw new IOException("Broker administrator credential is invalid"); }
        KindSaga.Tunnel tunnel = new KindSaga.Tunnel(lab, "service/shardshop-rabbitmq", 5672);
        Connection connection = null;
        Channel channel = null;
        try {
            ConnectionFactory factory = new ConnectionFactory();
            factory.setHost("127.0.0.1");
            factory.setPort(tunnel.port);
            factory.setVirtualHost("shardshop");
            factory.setUsername("shardshop-admin");
            factory.setPassword(password);
            factory.setConnectionTimeout(5_000);
            factory.setRequestedHeartbeat(10);
            factory.setAutomaticRecoveryEnabled(false);
            connection = factory.newConnection();
            channel = connection.createChannel();
            GetResponse delivery = channel.basicGet(RESULT_DLQ, false);
            require(delivery != null, "Scoped parked result disappeared before inspection");
            require(resultId.equals(delivery.getProps().getMessageId()),
                    "Parked result message ID differs from the scoped result");
            JsonNode body;
            try { body = KindLab.parse(new String(delivery.getBody(), StandardCharsets.UTF_8)); }
            catch (RuntimeException invalid) { throw new IOException("Parked result body is invalid JSON"); }
            require(storedResult.equals(body), "Parked result body differs from the stored immutable result");
            Map<String, Object> headers = delivery.getProps().getHeaders();
            require(headers != null && headers.get("x-death") instanceof List<?>,
                    "Parked result has no x-death diagnostic");
            List<?> deaths = (List<?>) headers.get("x-death");
            require(deaths.size() == 1 && deaths.getFirst() instanceof Map<?, ?>,
                    "Parked result has an unexpected death history");
            Map<?, ?> death = (Map<?, ?>) deaths.getFirst();
            require("order.ledger-results".equals(String.valueOf(death.get("queue")))
                            && "delivery_limit".equals(String.valueOf(death.get("reason")))
                            && death.get("count") instanceof Number count && count.longValue() == 1,
                    "Scoped result did not park from the result queue at its delivery limit");
            long failures = headers.get("x-delivery-count") instanceof Number count ? count.longValue() : -1;
            return new ParkedDelivery(tunnel, connection, channel, delivery.getEnvelope().getDeliveryTag(), failures);
        } catch (Exception failure) {
            closeQuietly(channel, connection, tunnel);
            if (failure instanceof IOException io) throw io;
            throw new IOException("Cannot inspect the scoped parked result", failure);
        }
    }

    private static void closeQuietly(Channel channel, Connection connection, KindSaga.Tunnel tunnel) {
        try { if (channel != null) channel.close(); } catch (Exception ignored) { /* The connection closes next. */ }
        try { if (connection != null) connection.close(); } catch (Exception ignored) { /* The tunnel closes next. */ }
        tunnel.close();
    }

    private DeploymentState deploymentState(String name) throws IOException, InterruptedException {
        JsonNode node = deployment.resource("deployment", name);
        String uid = node.path("metadata").path("uid").asText();
        int replicas = node.path("spec").path("replicas").asInt(-1);
        require(!uid.isBlank() && replicas >= 0, name + " has no stable deployment identity or replica count");
        return new DeploymentState(uid, replicas);
    }

    private void replicas(String name, DeploymentState original, int desired) throws IOException, InterruptedException {
        JsonNode node = deployment.resource("deployment", name);
        String uid = node.path("metadata").path("uid").asText();
        int current = node.path("spec").path("replicas").asInt(-1);
        require(uid.equals(original.uid()) && (current == original.replicas() || current == 0),
                name + " changed outside this drill; inspect it before restoring replicas");
        if (current == desired) return;
        ArrayNode patch = Json.newObject().arrayNode();
        patch.addObject().put("op", "test").put("path", "/metadata/uid").put("value", uid);
        patch.addObject().put("op", "test").put("path", "/metadata/resourceVersion")
                .put("value", node.path("metadata").path("resourceVersion").asText());
        patch.addObject().put("op", "test").put("path", "/spec/replicas").put("value", current);
        patch.addObject().put("op", "replace").put("path", "/spec/replicas").put("value", desired);
        lab.kubectl("patch", "deployment", name, "--type=json", "-p", patch.toString());
    }

    private void noPods(String application) throws IOException, InterruptedException {
        lab.await(application + " pods terminate", Duration.ofSeconds(90), () ->
                deployment.pods("app.kubernetes.io/name=shardshop-" + application).isEmpty());
    }

    private void readyDeployment(String application, int expected) throws IOException, InterruptedException {
        lab.await(application + " Deployment restores its original replicas", Duration.ofSeconds(150), () -> {
            JsonNode state = deployment.resource("deployment", "shardshop-" + application);
            return state.path("spec").path("replicas").asInt(-1) == expected
                    && state.path("status").path("readyReplicas").asInt(-1) == expected
                    && state.path("status").path("updatedReplicas").asInt(-1) == expected
                    && state.path("status").path("observedGeneration").asLong(-1)
                    >= state.path("metadata").path("generation").asLong();
        });
    }

    private static List<String> instances(KindTopology.Shard shard) {
        return List.of(shard.primary(), shard.standbys().get(0), shard.standbys().get(1));
    }

    private static String fenceValue(KindTopology.Shard shard) {
        ArrayNode names = Json.newObject().arrayNode();
        for (String instance : instances(shard)) names.add(instance);
        return names.toString();
    }

    private static FenceState fenceState(JsonNode cluster) {
        JsonNode value = cluster.path("metadata").path("annotations").path(FENCE);
        return new FenceState(!value.isMissingNode(), value.asText());
    }

    private void restoreFence(String shard, String uid, FenceState original, FenceState issued)
            throws IOException, InterruptedException {
        JsonNode cluster = topology.cluster(shard);
        require(cluster.path("metadata").path("uid").asText().equals(uid),
                "Buyer shard identity changed during the retry drill");
        FenceState current = fenceState(cluster);
        require(current.equals(original) || current.equals(issued),
                "Buyer shard fence changed outside this drill; inspect it before restoration");
        if (!current.equals(original)) patchFence(shard, cluster, original);
    }

    private void patchFence(String shard, JsonNode cluster, FenceState target) throws IOException, InterruptedException {
        JsonNode metadata = cluster.path("metadata");
        String uid = metadata.path("uid").asText();
        String version = metadata.path("resourceVersion").asText();
        require(!uid.isBlank() && !version.isBlank(), "Buyer shard has no identity/version for conditional fencing");
        ArrayNode patch = Json.newObject().arrayNode();
        patch.addObject().put("op", "test").put("path", "/metadata/uid").put("value", uid);
        patch.addObject().put("op", "test").put("path", "/metadata/resourceVersion").put("value", version);
        String path = "/metadata/annotations/cnpg.io~1fencedInstances";
        if (target.present()) {
            if (metadata.path("annotations").isMissingNode()) {
                ObjectNode annotations = Json.newObject().put(FENCE, target.value());
                patch.addObject().put("op", "add").put("path", "/metadata/annotations").set("value", annotations);
            } else {
                patch.addObject().put("op", "add").put("path", path).put("value", target.value());
            }
        } else {
            patch.addObject().put("op", "remove").put("path", path);
        }
        lab.kubectl("patch", "cluster", shard, "--type=json", "-p", patch.toString());
    }

    private record Buyer(String id, String shard) { }
    private record ControlState(BigDecimal targetRate, boolean paused) { }
    private record DeploymentState(String uid, int replicas) { }
    private record PodState(String podUid, String containerId, int restarts) { }
    private record FenceState(boolean present, String value) { }

    private static final class ParkedDelivery implements AutoCloseable {
        private final KindSaga.Tunnel tunnel;
        private final Connection connection;
        private final Channel channel;
        private final long tag;
        private final long failureCount;

        private ParkedDelivery(KindSaga.Tunnel tunnel, Connection connection, Channel channel, long tag, long failureCount) {
            this.tunnel = tunnel;
            this.connection = connection;
            this.channel = channel;
            this.tag = tag;
            this.failureCount = failureCount;
        }

        long failureCount() { return failureCount; }

        void acknowledge() throws IOException { channel.basicAck(tag, false); }

        @Override public void close() throws IOException {
            IOException failure = null;
            try { channel.close(); } catch (Exception problem) { failure = new IOException("Cannot close diagnostic channel", problem); }
            try { connection.close(); } catch (Exception problem) {
                if (failure == null) failure = new IOException("Cannot close diagnostic connection", problem);
                else failure.addSuppressed(problem);
            } finally { tunnel.close(); }
            if (failure != null) throw failure;
        }
    }
}
