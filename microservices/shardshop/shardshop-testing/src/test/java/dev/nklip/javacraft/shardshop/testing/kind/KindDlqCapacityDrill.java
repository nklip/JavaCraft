package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.Json;
import dev.nklip.javacraft.shardshop.testing.kind.KindSaga.Tunnel;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static dev.nklip.javacraft.shardshop.testing.kind.KindDeployment.require;

/** Explicit live DLQ failure drills. Each invocation owns its filler and its temporary broker policy. */
public final class KindDlqCapacityDrill {
    private static final String BROKER = "shardshop-rabbitmq-0";
    private static final String VHOST = "shardshop";
    private static final int LIMIT = 8;
    private static final Duration RECOVERY = Duration.ofSeconds(270);
    // The pinned broker retries retained dead letters on a 180-second timer; sequential filler
    // timestamps can straddle a tick.
    // https://github.com/rabbitmq/rabbitmq-server/blob/v4.3.6/deps/rabbit/src/rabbit_fifo_dlx_worker.erl
    private static final Duration FULL_RECOVERY = Duration.ofSeconds(450);

    private final KindLab lab;
    private final KindSaga saga;
    private final KindDeployment deployment;
    private final String run = "kind-dlq-" + UUID.randomUUID();
    private final String marker = "shardshop-dlq-drill:" + run;

    public KindDlqCapacityDrill() { this(new KindLab()); }
    KindDlqCapacityDrill(KindLab lab) {
        this.lab = lab;
        this.saga = new KindSaga(lab);
        this.deployment = new KindDeployment(lab);
    }

    /** Run the selected unbound/full parking scenario against both application publisher paths. */
    public void verify(String mode) throws IOException, InterruptedException {
        verify(mode, "ledger.record-order");
        verify(mode, "order.ledger-results");
    }

    /** Source is ledger.record-order (order CDC) or order.ledger-results (ledger result relay). */
    public void verify(String mode, String source) throws IOException, InterruptedException {
        require(List.of("unbound", "full").contains(mode), "Unknown DLQ capacity drill mode");
        Target target = Target.of(source);
        try (KindDrillGuard guard = new KindDrillGuard(lab).acquire()) {
            guard.run(() -> {
                try { run(guard, mode, target); }
                catch (IOException | InterruptedException problem) { throw problem; }
                catch (Exception problem) { throw new IOException("DLQ capacity drill failed for " + source + "/" + mode, problem); }
            });
        }
    }

    private void run(KindDrillGuard guard, String mode, Target target) throws Exception {
        saga.deployed();
        new KindRabbitMq(lab).verify();
        pauseProducer(guard);
        BrokerInstance broker = brokerInstance();
        checkBroker(broker);
        require(depth(target.source()) == 0 && depth(target.dlq()) == 0,
                "Selected source and DLQ must be empty; inspect parked messages before the drill");
        for (String shard : lab.shards())
            require(saga.number(shard, "SELECT count(*) FROM ordering.order_outbox WHERE published_at IS NULL") == 0,
                    "An earlier order command outbox is still unpublished on " + shard);
        require(saga.number("ledger-db", "SELECT count(*) FROM ledger.ledger_outbox WHERE published_at IS NULL") == 0,
                "An earlier ledger result outbox is still unpublished");
        require(bindingPresent(target), "Expected DLQ binding is absent before drill");

        // Register selective cleanup before any filler is sent. Recovery actions run after policy/binding restoration.
        guard.onClose("remove only owned DLQ filler and verify source drains", () -> {
            lab.await("owned source dead letters transfer after DLQ restoration", RECOVERY,
                    () -> depth(target.source()) == 0);
            removeOwned(target.dlq());
            lab.await("selected source and DLQ return to empty", Duration.ofSeconds(40),
                    () -> depth(target.source()) == 0 && depth(target.dlq()) == 0);
            checkBroker(broker);
            return true;
        });
        scopedPolicy(guard, target.source(), sourceDefinition(target), LIMIT);
        String parkingPolicy = "";
        if (mode.equals("full")) parkingPolicy = scopedPolicy(guard, target.dlq(), parkingDefinition(), LIMIT);
        else unbind(guard, target);
        checkBroker(broker);

        int parkingAccepted = 0;
        int parkingNacks = 0;
        try (Tunnel amqp = new Tunnel(lab, "service/shardshop-rabbitmq", 5672);
             Connection connection = connect(amqp);
             Channel channel = connection.createChannel()) {
            channel.confirmSelect();
            if (mode.equals("full")) {
                Fill parking = fill(channel, "shardshop.dlx", target.dlq(), target.dlq());
                parkingAccepted = parking.accepted();
                parkingNacks = parking.nacks();
                require(parkingNacks > 0 && parkingAccepted >= LIMIT,
                        "Parking queue did not reject the drill's owned filler: accepted="
                                + parkingAccepted + ", nacks=" + parkingNacks + ", depth=" + depth(target.dlq()));
                try {
                    lab.await("parking depth reflects accepted filler", Duration.ofSeconds(40),
                            () -> depth(target.dlq()) >= LIMIT);
                } catch (IOException problem) {
                    throw new IOException("Parking queue stayed below its finite limit: accepted="
                            + parkingAccepted + ", nacks=" + parkingNacks + ", depth=" + depth(target.dlq()), problem);
                }
            }
            Fill sourceFill = fill(channel, target.exchange(), target.source(), target.source());
            require(sourceFill.nacks() > 0 && sourceFill.accepted() >= LIMIT,
                    "Retained dead letters did not drive the source to negative publisher confirms");
            lab.await("source retains dead letters at its finite limit", Duration.ofSeconds(40),
                    () -> retainedDeadLetters(target.source()));
            require(mode.equals("unbound") ? depth(target.dlq()) == 0 : depth(target.dlq()) >= LIMIT,
                    "DLQ state does not distinguish missing binding from full capacity");
            checkBroker(broker);

            Instant sentAfter = Instant.now();
            Order order = acceptedOrder();
            lab.await("real " + target.publisher() + " outbox exists and remains unpublished", Duration.ofSeconds(90), () -> {
                Outbox state = outbox(target, order);
                return state.exists() && !state.published();
            });
            require(noOtherPendingOutboxes(target, order),
                    "Another pending outbox prevents attribution of the publisher failure to the scoped order");
            lab.await("real " + target.publisher() + " receives a negative broker confirm", Duration.ofSeconds(90), () ->
                    publisherFailure(target, order, sentAfter));
            require(noOtherPendingOutboxes(target, order),
                    "Another pending outbox appeared during the scoped publisher failure");
            Outbox pending = outbox(target, order);
            require(pending.exists() && !pending.published(), "Real outbox unexpectedly published under source pressure");
            if (target.source().equals("ledger.record-order")) {
                long afterInsert = saga.number(order.home(),
                        "SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), '0/0')::bigint");
                require(checkpoint(order.home()) < afterInsert,
                        "CDC checkpoint reached the WAL observation after its failed outbox insert");
                long stopped = checkpoint(order.home());
                long stableAt = System.nanoTime() + Duration.ofSeconds(2).toNanos();
                lab.await("failed CDC batch holds its durable checkpoint", Duration.ofSeconds(8), () ->
                        System.nanoTime() >= stableAt && checkpoint(order.home()) == stopped);
            }
            require(retainedDeadLetters(target.source()),
                    "Source retained-dead-letter pressure disappeared before the outbox observation");
            checkBroker(broker);
            System.out.printf("DLQ pressure mode=%s source=%s orderId=%s outboxId=%s sourceDepth=%d dlqDepth=%d "
                            + "sourceAccepted=%d sourceNacks=%d parkingAccepted=%d parkingNacks=%d brokerUid=%s%n",
                    mode, target.source(), order.id(), pending.messageId(), depth(target.source()), depth(target.dlq()),
                    sourceFill.accepted(), sourceFill.nacks(), parkingAccepted, parkingNacks, broker.uid());

            // Restore only the blocked DLQ condition; keep the low source limit while observing application progress.
            long recoveryStarted = System.nanoTime();
            if (mode.equals("unbound")) bind(target);
            else {
                removeOwned(target.dlq());
                clearPolicy(parkingPolicy);
            }
            lab.await("real " + target.publisher() + " outbox publishes after DLQ recovery",
                    mode.equals("full") ? FULL_RECOVERY : RECOVERY,
                    () -> outbox(target, order).published());
            lab.await("real scoped order confirms after DLQ recovery", RECOVERY,
                    () -> saga.sql(order.home(), "SELECT status FROM ordering.orders WHERE order_id=" + order.id())
                            .strip().equals("CONFIRMED"));
            lab.await("scoped order has complete ledger, stock and profit effects", Duration.ofSeconds(120), () -> {
                saga.verifyOrder(order.home(), order.id(), "CONFIRMED");
                return true;
            });
            checkBroker(broker);
            System.out.printf("DLQ recovered mode=%s source=%s orderId=%s outboxId=%s recoveryMs=%d sourceDepth=%d dlqDepth=%d "
                            + "brokerUid=%s%n", mode, target.source(), order.id(), pending.messageId(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - recoveryStarted),
                    depth(target.source()), depth(target.dlq()), broker.uid());
        }
    }

    private record Target(String source, String dlq, String exchange, String publisher) {
        static Target of(String source) throws IOException {
            return switch (source) {
                case "ledger.record-order" -> new Target(source, source + ".dlq", "ledger.commands", "order CDC");
                case "order.ledger-results" -> new Target(source, source + ".dlq", "order.saga-results", "ledger result relay");
                default -> throw new IOException("Unknown DLQ source queue: " + source);
            };
        }
    }

    private record Fill(int accepted, int nacks) { }
    private record Order(String id, String home) { }
    private record Outbox(boolean exists, boolean published, String messageId) { }
    private record BrokerInstance(String uid, String containerId, int restartCount) { }

    private Fill fill(Channel channel, String exchange, String routingKey, String destination) throws Exception {
        int accepted = 0;
        for (int attempt = 0; attempt < 128; attempt++) {
            String body = marker + ":" + destination + ":" + attempt;
            AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder().deliveryMode(2)
                    .contentType("application/octet-stream").headers(Map.of("shardshop-drill-owner", run)).build();
            channel.basicPublish(exchange, routingKey, true, properties, body.getBytes(StandardCharsets.UTF_8));
            if (!channel.waitForConfirms(5_000)) return new Fill(accepted, 1);
            accepted++;
        }
        throw new IOException("Broker did not negatively confirm owned filler within 128 publishes to " + destination);
    }

    private Outbox outbox(Target target, Order order) throws IOException, InterruptedException {
        String sql = target.source().equals("ledger.record-order")
                ? "SELECT row_to_json(x)::text FROM (SELECT o.message_id::text, o.published_at IS NOT NULL AS published "
                + "FROM ordering.order_outbox o JOIN ordering.order_sagas s USING(saga_id) WHERE s.order_id=" + order.id()
                + " ORDER BY o.created_at LIMIT 1) x"
                : "SELECT row_to_json(x)::text FROM (SELECT o.message_id::text, o.published_at IS NOT NULL AS published "
                + "FROM ledger.ledger_outbox o JOIN ledger.ledger_result_ids r ON r.result_message_id=o.message_id "
                + "WHERE r.order_id=" + order.id() + " ORDER BY o.created_at LIMIT 1) x";
        String row = saga.sql(target.source().equals("ledger.record-order") ? order.home() : "ledger-db", sql).strip();
        if (row.isBlank()) return new Outbox(false, false, "");
        JsonNode value = KindLab.parse(row);
        return new Outbox(true, value.path("published").asBoolean(), KindSaga.id(value.path("message_id").asText()));
    }

    private boolean noOtherPendingOutboxes(Target target, Order order) throws IOException, InterruptedException {
        if (target.source().equals("ledger.record-order")) {
            for (String shard : lab.shards())
                if (saga.number(shard, "SELECT count(*) FROM ordering.order_outbox o "
                        + "JOIN ordering.order_sagas s USING(saga_id) WHERE o.published_at IS NULL "
                        + "AND s.order_id<>" + order.id()) != 0) return false;
            return true;
        }
        return saga.number("ledger-db", "SELECT count(*) FROM ledger.ledger_outbox o "
                + "JOIN ledger.ledger_result_ids r ON r.result_message_id=o.message_id "
                + "WHERE o.published_at IS NULL AND r.order_id<>" + order.id()) == 0;
    }

    private boolean publisherFailure(Target target, Order order, Instant after) throws IOException, InterruptedException {
        String app = target.source().equals("ledger.record-order") ? "order-saga" : "ledger";
        String container = app.equals("order-saga") ? "order" : "ledger";
        String pod = KindDeployment.name(saga.pod(app));
        String logs = lab.kubectl("logs", pod, "-c", container, "--since-time=" + after, "--tail=500");
        String failure = target.source().equals("ledger.record-order")
                ? "RabbitMQ negatively confirmed command publication"
                : "RabbitMQ negatively confirmed result publication";
        String worker = target.source().equals("ledger.record-order")
                ? "CDC reader for " + order.home() + " stopped; retrying"
                : "Ledger result publisher retry";
        return logs.lines().anyMatch(line -> line.contains(worker) && line.contains(failure));
    }

    private long checkpoint(String shard) throws IOException, InterruptedException {
        return saga.number(shard, "SELECT coalesce(max(coalesce((offset_val::jsonb->>'lsn_commit')::bigint, "
                + "(offset_val::jsonb->>'lsn')::bigint)),0) FROM ordering.debezium_offset_storage");
    }

    private Order acceptedOrder() throws IOException, InterruptedException {
        String buyer = "";
        String home = "";
        JsonNode product = null;
        for (String shard : lab.shards()) {
            if (buyer.isEmpty()) {
                String candidate = saga.sql(shard, "SELECT buyer_id::text FROM ordering.buyers ORDER BY buyer_id LIMIT 1").strip();
                if (!candidate.isEmpty()) { buyer = KindSaga.id(candidate); home = shard; }
            }
            if (product == null) {
                String candidate = saga.sql(shard, "SELECT row_to_json(x)::text FROM (SELECT seller_id::text, product_id::text "
                        + "FROM catalog.products WHERE currency='USD' AND stock>4 AND starts_with(creation_key, 'catalog-v2.') "
                        + "ORDER BY product_id LIMIT 1) x").strip();
                if (!candidate.isEmpty()) product = KindLab.parse(candidate);
            }
        }
        require(!buyer.isEmpty() && product != null, "DLQ drill needs a seeded buyer and catalog-v2 USD stock");
        String seller = KindSaga.id(product.path("seller_id").asText());
        String productId = KindSaga.id(product.path("product_id").asText());
        try (Tunnel order = new Tunnel(lab, "service/shardshop-order", 8080)) {
            String id = allocateWithRetry(order, buyer, run + "-" + UUID.randomUUID());
            ObjectNode request = Json.newObject().put("currency", "USD");
            request.putArray("items").addObject().put("sellerId", seller).put("productId", productId).put("quantity", 1);
            order.call("PUT", "/api/v1/buyers/" + buyer + "/orders/" + id, null, request, Set.of(202));
            return new Order(id, home);
        }
    }

    private String allocateWithRetry(Tunnel order, String buyer, String runName)
            throws IOException, InterruptedException {
        ObjectNode identity = Json.newObject().put("runName", runName).put("requestOrdinal", "0");
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        for (int attempt = 1; attempt <= 8; attempt++) {
            JsonNode response = order.call("POST", "/api/v1/buyers/" + buyer + "/order-allocations", null,
                    identity, Set.of(200, 201, 503));
            if (response.path("orderId").isTextual()) return KindSaga.id(response.path("orderId").asText());
            String code = response.path("code").asText();
            require(Set.of("ORDER_STORE_UNAVAILABLE", "ID_GENERATION_UNAVAILABLE").contains(code),
                    "Allocation response is neither an ID nor a documented transient 503");
            if (attempt == 8 || System.nanoTime() >= deadline)
                throw new IOException("Scoped allocation remained unavailable after " + attempt
                        + " same-identity attempts: " + code);
            long baseMillis = Math.min(2_000, 500L * attempt);
            long jitteredMillis = Math.min(2_000,
                    Math.round(baseMillis * ThreadLocalRandom.current().nextDouble(0.75, 1.25)));
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) throw new IOException("Scoped allocation retry deadline expired: " + code);
            TimeUnit.NANOSECONDS.sleep(Math.min(remainingNanos, Duration.ofMillis(jitteredMillis).toNanos()));
        }
        throw new IOException("Scoped allocation retry exhausted");
    }

    private void pauseProducer(KindDrillGuard guard) throws IOException, InterruptedException {
        JsonNode original = producerStatus();
        require(original.path("workload").asText().equals("order-producer")
                && original.path("targetRate").isNumber()
                && Set.of("RUNNING", "PAUSED").contains(original.path("state").asText()),
                "Order producer has no healthy live control state");
        BigDecimal rate = original.path("targetRate").decimalValue();
        boolean paused = original.path("paused").asBoolean();
        guard.onClose("restore producer's prior live pause and rate after DLQ cleanup", () -> {
            try (Tunnel tunnel = new Tunnel(lab, "service/shardshop-order-producer", 8080)) {
                String root = "/api/v1/order-producer/control";
                JsonNode current = tunnel.call("GET", root, null, null, Set.of(200));
                if (current.path("targetRate").decimalValue().compareTo(rate) != 0)
                    tunnel.call("PUT", root + "/rate", null, Json.newObject().put("rate", rate), Set.of(200));
                if (current.path("paused").asBoolean() != paused)
                    tunnel.call("POST", root + (paused ? "/pause" : "/resume"), null, null, Set.of(200));
                JsonNode restored = tunnel.call("GET", root, null, null, Set.of(200));
                require(restored.path("paused").asBoolean() == paused
                        && restored.path("targetRate").decimalValue().compareTo(rate) == 0,
                        "Producer live controls were not restored");
                return true;
            }
        });
        if (!paused) try (Tunnel tunnel = new Tunnel(lab, "service/shardshop-order-producer", 8080)) {
            require(tunnel.call("POST", "/api/v1/order-producer/control/pause", null, null, Set.of(200))
                    .path("paused").asBoolean(), "Producer did not accept Pause");
        }
        lab.await("producer finishes in-flight requests and observer backlog", Duration.ofSeconds(180), () -> {
            JsonNode status = producerStatus();
            return status.path("paused").asBoolean() && status.path("inFlight").asInt(-1) == 0
                    && status.path("producer").path("nonTerminalBacklog").asInt(-1) == 0;
        });
        lab.await("prior work drains", Duration.ofSeconds(180), () -> {
            for (String shard : lab.shards())
                if (saga.number(shard, "SELECT count(*) FROM ordering.order_sagas WHERE status IN ('PENDING_STOCK','PENDING_LEDGER') "
                        + "OR (status='CANCELLED' AND stock_released_at IS NULL)") != 0) return false;
            return true;
        });
    }

    private JsonNode producerStatus() throws IOException, InterruptedException {
        try (Tunnel tunnel = new Tunnel(lab, "service/shardshop-order-producer", 8080)) {
            return tunnel.call("GET", "/api/v1/order-producer/control", null, null, Set.of(200));
        }
    }

    private String scopedPolicy(KindDrillGuard guard, String queue, ObjectNode definition, int maxLength)
            throws IOException, InterruptedException {
        String name = "kind-dlq-" + UUID.randomUUID().toString().substring(0, 8);
        require(!policyPresent(name), "Scoped RabbitMQ policy name already exists");
        guard.onClose("clear owned broker policy " + name, () -> { clearPolicy(name); return !policyPresent(name); });
        definition.put("max-length", maxLength);
        brokerCtl("set_policy", "-p", VHOST, "--priority", "10", "--apply-to", "queues", name,
                "^" + queue.replace(".", "\\.") + "$", definition.toString());
        require(policyPresent(name), "Temporary queue policy did not apply");
        return name;
    }

    private ObjectNode sourceDefinition(Target target) throws IOException, InterruptedException {
        String name = target.source().equals("ledger.record-order") ? "ledger-command-delivery" : "order-result-delivery";
        JsonNode existing = policy(name);
        require(existing != null && existing.path("priority").asInt() == 0,
                "Expected complete source delivery policy is absent");
        JsonNode definition = policyDefinition(existing);
        require(definition.path("dead-letter-routing-key").asText().equals(target.dlq())
                        && definition.path("max-length").asInt() == 1000
                        && definition.path("dead-letter-strategy").asText().equals("at-least-once"),
                "Source delivery policy differs from the documented reliable topology");
        return definition.deepCopy();
    }

    private ObjectNode parkingDefinition() throws IOException, InterruptedException {
        JsonNode existing = policy("parking-capacity");
        require(existing != null && existing.path("priority").asInt() == 0,
                "Expected complete parking policy is absent");
        JsonNode definition = policyDefinition(existing);
        require(definition.path("max-length").asInt() == 1000
                && definition.path("overflow").asText().equals("reject-publish"),
                "Parking policy differs from the documented capacity topology");
        return definition.deepCopy();
    }

    private static ObjectNode policyDefinition(JsonNode policy) throws IOException {
        JsonNode definition = policy.path("definition");
        if (definition.isTextual()) definition = KindLab.parse(definition.asText());
        require(definition.isObject(), "Broker policy definition is not an object");
        return (ObjectNode) definition;
    }

    private JsonNode policy(String name) throws IOException, InterruptedException {
        for (JsonNode policy : brokerList("list_policies"))
            if (policy.path("name").asText().equals(name)) return policy;
        return null;
    }

    private boolean policyPresent(String name) throws IOException, InterruptedException { return policy(name) != null; }

    private void clearPolicy(String name) throws IOException, InterruptedException {
        if (policyPresent(name)) brokerCtl("clear_policy", "-p", VHOST, name);
    }

    private void unbind(KindDrillGuard guard, Target target) throws IOException, InterruptedException {
        guard.onClose("restore exact DLQ binding " + target.dlq(), () -> {
            bind(target);
            return bindingPresent(target);
        });
        try (Tunnel tunnel = new Tunnel(lab, "service/shardshop-rabbitmq", 5672);
             Connection connection = connect(tunnel); Channel channel = connection.createChannel()) {
            channel.queueUnbind(target.dlq(), "shardshop.dlx", target.dlq());
        } catch (Exception problem) { throw io(problem); }
        require(!bindingPresent(target), "DLQ binding remained present after unbind");
    }

    private void bind(Target target) throws IOException, InterruptedException {
        if (bindingPresent(target)) return;
        try (Tunnel tunnel = new Tunnel(lab, "service/shardshop-rabbitmq", 5672);
             Connection connection = connect(tunnel); Channel channel = connection.createChannel()) {
            channel.queueBind(target.dlq(), "shardshop.dlx", target.dlq());
        } catch (Exception problem) { throw io(problem); }
        require(bindingPresent(target), "DLQ binding was not restored");
    }

    private boolean bindingPresent(Target target) throws IOException, InterruptedException {
        for (JsonNode binding : brokerList("list_bindings", "source_name", "destination_name", "destination_kind", "routing_key", "arguments"))
            if (binding.path("source_name").asText().equals("shardshop.dlx")
                    && binding.path("destination_name").asText().equals(target.dlq())
                    && binding.path("destination_kind").asText().equals("queue")
                    && binding.path("routing_key").asText().equals(target.dlq())) return true;
        return false;
    }

    private long depth(String queue) throws IOException, InterruptedException {
        return queueState(queue).path("messages").asLong(-1);
    }

    private boolean retainedDeadLetters(String queue) throws IOException, InterruptedException {
        JsonNode state = queueState(queue);
        // RabbitMQ's normal ready/unacknowledged counters exclude the quorum queue's internal
        // retained-dead-letter store, while "messages" still counts it against max-length.
        return state.path("messages").asLong(-1) >= LIMIT
                && state.path("messages_ready").asLong(-1) == 0
                && state.path("messages_unacknowledged").asLong(-1) == 0;
    }

    private JsonNode queueState(String queue) throws IOException, InterruptedException {
        for (JsonNode state : brokerList("list_queues", "name", "messages", "messages_ready", "messages_unacknowledged"))
            if (state.path("name").asText().equals(queue)) return state;
        throw new IOException("Broker source or DLQ disappeared: " + queue);
    }

    private JsonNode brokerList(String command, String... fields) throws IOException, InterruptedException {
        String[] args = new String[11 + fields.length];
        String[] fixed = {"exec", BROKER, "-c", "rabbitmq", "--", "rabbitmqctl", "--quiet", "--formatter=json", command};
        System.arraycopy(fixed, 0, args, 0, fixed.length);
        args[9] = "-p";
        args[10] = VHOST;
        System.arraycopy(fields, 0, args, 11, fields.length);
        JsonNode result = KindLab.parse(lab.kubectl(Duration.ofSeconds(20), args));
        require(result.isArray(), "RabbitMQ list command returned no JSON array");
        return result;
    }

    private void brokerCtl(String... command) throws IOException, InterruptedException {
        String[] args = new String[7 + command.length];
        String[] fixed = {"exec", BROKER, "-c", "rabbitmq", "--", "rabbitmqctl", "--quiet"};
        System.arraycopy(fixed, 0, args, 0, fixed.length);
        System.arraycopy(command, 0, args, fixed.length, command.length);
        lab.kubectl(Duration.ofSeconds(25), args);
    }

    private BrokerInstance brokerInstance() throws IOException, InterruptedException {
        JsonNode pod = deployment.resource("pod", BROKER);
        String uid = pod.path("metadata").path("uid").asText();
        String containerId = "";
        int restarts = -1;
        for (JsonNode status : pod.path("status").path("containerStatuses"))
            if (status.path("name").asText().equals("rabbitmq")) {
                containerId = status.path("containerID").asText();
                restarts = status.path("restartCount").asInt(-1);
            }
        require(!uid.isBlank() && !containerId.isBlank() && restarts >= 0,
                "RabbitMQ pod has no current container identity");
        return new BrokerInstance(uid, containerId, restarts);
    }

    private void checkBroker(BrokerInstance expected) throws IOException, InterruptedException {
        require(brokerInstance().equals(expected), "Broker pod or container restarted during DLQ pressure drill");
        lab.kubectl(Duration.ofSeconds(20), "exec", BROKER, "-c", "rabbitmq", "--", "rabbitmq-diagnostics", "-q", "-t", "10", "check_running");
        lab.kubectl(Duration.ofSeconds(20), "exec", BROKER, "-c", "rabbitmq", "--", "rabbitmq-diagnostics", "-q", "-t", "10", "check_local_alarms");
    }

    private Connection connect(Tunnel tunnel) throws Exception {
        String encoded = deployment.resource("secret", "shardshop-rabbitmq").path("data").path("admin-password").asText();
        require(!encoded.isBlank(), "RabbitMQ admin credential is missing");
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("127.0.0.1");
        factory.setPort(tunnel.port);
        factory.setVirtualHost(VHOST);
        factory.setUsername("shardshop-admin");
        factory.setPassword(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8));
        factory.setConnectionTimeout(5_000);
        factory.setRequestedHeartbeat(10);
        factory.setAutomaticRecoveryEnabled(false);
        return factory.newConnection();
    }

    private void removeOwned(String queue) throws IOException, InterruptedException {
        try (Tunnel tunnel = new Tunnel(lab, "service/shardshop-rabbitmq", 5672);
             Connection connection = connect(tunnel); Channel channel = connection.createChannel()) {
            for (int count = 0; count < 256; count++) {
                GetResponse message = channel.basicGet(queue, false);
                if (message == null) return;
                Object owner = message.getProps().getHeaders() == null ? null
                        : message.getProps().getHeaders().get("shardshop-drill-owner");
                boolean ownHeader = owner != null && run.equals(owner.toString());
                boolean ownBody = new String(message.getBody(), StandardCharsets.UTF_8).startsWith(marker + ":");
                if (!ownHeader || !ownBody) {
                    channel.basicNack(message.getEnvelope().getDeliveryTag(), false, true);
                    throw new IOException("Refusing to acknowledge a DLQ message outside this drill's ownership");
                }
                channel.basicAck(message.getEnvelope().getDeliveryTag(), false);
            }
            throw new IOException("Owned DLQ filler exceeded cleanup bound");
        } catch (InterruptedException problem) { throw problem; }
        catch (Exception problem) { throw io(problem); }
    }

    private static IOException io(Exception problem) { return problem instanceof IOException io ? io : new IOException("Broker drill operation failed", problem); }
}
