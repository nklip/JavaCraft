package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nklip.javacraft.shardshop.common.CurrencyPolicy;
import dev.nklip.javacraft.shardshop.common.amqp.BrokerSettings;
import dev.nklip.javacraft.shardshop.common.amqp.ConfirmedPublisher;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.Json;
import dev.nklip.javacraft.shardshop.testing.kind.KindSaga.Tunnel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static dev.nklip.javacraft.shardshop.testing.kind.KindDeployment.require;

/** Explicit fault injection only: normal deployment gates never call this class. */
public final class KindSagaDrill {
    private final KindLab lab;
    private final KindSaga saga;
    private final KindDeployment deployment;
    private final String run = "kind-drill-" + UUID.randomUUID();
    private int ordinal;
    private String buyer;
    private String home;

    public KindSagaDrill() { this(new KindLab()); }
    KindSagaDrill(KindLab lab) { this.lab = lab; saga = new KindSaga(lab); deployment = new KindDeployment(lab); }

    public void replayAndRace() throws Exception {
        try (KindDrillGuard guard = new KindDrillGuard(lab).acquire()) {
            guard.run(() -> {
                try {
                    saga.deployed();
                    saga.normalPolicy();
                    pauseProducer(guard);
                    restrictPolicy(guard);
                    buyer();
                    try (Tunnel http = new Tunnel(lab, "service/shardshop-order", 8080);
                         Tunnel broker = new Tunnel(lab, "service/shardshop-rabbitmq", 5672)) {
                        JsonNode usd = product("USD");
                        JsonNode eur = product("EUR");
                        String confirmed = accept(http, request("USD", usd, 1));
                        awaitOrder(confirmed, "CONFIRMED");
                        String rejected = accept(http, request("EUR", eur, 1));
                        awaitOrder(rejected, "CANCELLED");
                        require(reason(rejected).equals("UNSUPPORTED_CURRENCY"), "EUR must be cancelled by ledger policy");
                        replay(broker, confirmed, false);
                        replay(broker, rejected, false);
                        conflictingCommand(broker, rejected);
                        expandPolicy(guard);
                        replay(broker, rejected, true);
                        race(http);
                        atomicStockFailure(http);
                        saga.invariants();
                    }
                } catch (IOException | InterruptedException failure) { throw failure; }
                catch (Exception failure) { throw new IOException("Saga replay/race drill failed", failure); }
            });
        }
        saga.normalPolicy();
    }

    public void relocateCdc() throws IOException, InterruptedException {
        try (KindDrillGuard guard = new KindDrillGuard(lab).acquire()) {
            guard.run(() -> {
                saga.deployed();
                buyer();
                JsonNode original = saga.pod("order-saga");
                String oldNode = original.path("spec").path("nodeName").asText();
                JsonNode sagaDeployment = deployment.resource("deployment", "shardshop-order-saga");
                JsonNode originalSelector = sagaDeployment.at("/spec/template/spec/nodeSelector");
                List<JsonNode> workers = KindLab.items(KindLab.parse(lab.kubectl("get", "nodes", "-o", "json"))).stream()
                        .filter(node -> !node.path("metadata").path("labels").has("node-role.kubernetes.io/control-plane"))
                        .filter(node -> !KindDeployment.name(node).equals(oldNode)).toList();
                require(!workers.isEmpty(), "CDC relocation needs a different worker");
                String target = KindDeployment.name(workers.getFirst());
                guard.onClose("restore saga placement and one ready CDC owner", () -> {
                    lab.kubectl("scale", "deployment/shardshop-order-saga", "--replicas=0");
                    awaitNoSaga();
                    selector(originalSelector);
                    lab.kubectl("scale", "deployment/shardshop-order-saga", "--replicas=1");
                    awaitDeployment("order-saga");
                    return true;
                });
                lab.kubectl("scale", "deployment/shardshop-order-saga", "--replicas=0");
                awaitNoSaga();
                java.util.Map<String, String> history = new java.util.LinkedHashMap<>();
                java.util.Map<String, Long> offsets = new java.util.LinkedHashMap<>();
                for (String shard : lab.shards()) {
                    history.put(shard, saga.sql(shard, "SELECT json_agg(s ORDER BY reader_name)::text FROM ordering.cdc_reader_state s").strip());
                    require(!history.get(shard).equals("null") && !history.get(shard).isBlank(), "No permanent CDC history before relocation");
                    offsets.put(shard, checkpoint(shard));
                    require(offsets.get(shard) > 0, "No persisted CDC resume position before relocation");
                }
                List<String> pending = new ArrayList<>();
                try (Tunnel tunnel = new Tunnel(lab, "service/shardshop-order", 8080)) {
                    for (int write = 0; write < 3; write++) pending.add(accept(tunnel, request("USD", product("USD"), 1)));
                }
                require(saga.number(home, "SELECT count(*) FROM ordering.order_sagas WHERE order_id IN ("
                        + String.join(",", pending) + ") AND status='PENDING_STOCK'") == pending.size(),
                        "HTTP must commit new orders while the saga process is stopped");
                ObjectNode selected = originalSelector.isObject() ? originalSelector.deepCopy() : Json.newObject();
                selected.put("kubernetes.io/hostname", target);
                selector(selected);
                lab.kubectl("scale", "deployment/shardshop-order-saga", "--replicas=1");
                awaitDeployment("order-saga");
                replacement(original, saga.pod("order-saga"), target);
                for (String id : pending) awaitOrder(id, "CONFIRMED");
                for (String shard : lab.shards()) {
                    require(history.get(shard).equals(saga.sql(shard, "SELECT json_agg(s ORDER BY reader_name)::text FROM ordering.cdc_reader_state s").strip()),
                            "Replacement recreated CDC initialization history");
                    require(checkpoint(shard) >= offsets.get(shard), "Replacement rewound a durable CDC position");
                }
                try (Tunnel tunnel = new Tunnel(lab, "service/shardshop-order", 8080)) {
                    String id = accept(tunnel, request("USD", product("USD"), 1));
                    awaitOrder(id, "CONFIRMED");
                    lab.await("replacement CDC checkpoints its newly published command", Duration.ofSeconds(60), () ->
                            checkpoint(home) > offsets.get(home)
                                    && saga.number(home, "SELECT count(*) FROM ordering.order_outbox o JOIN ordering.order_sagas s USING(saga_id) "
                                    + "WHERE s.order_id=" + id + " AND o.published_at IS NOT NULL") == 1);
                }
                saga.invariants();
            });
        }
    }

    private void awaitNoSaga() throws IOException, InterruptedException {
        lab.await("saga owner terminates before changing CDC placement", Duration.ofSeconds(90), () ->
                deployment.pods("app.kubernetes.io/name=shardshop-order-saga").isEmpty());
    }

    private void selector(JsonNode selector) throws IOException, InterruptedException {
        ObjectNode operation = Json.newObject();
        if (selector.isMissingNode()) {
            JsonNode current = deployment.resource("deployment", "shardshop-order-saga").at("/spec/template/spec/nodeSelector");
            if (current.isMissingNode()) return;
            operation.put("op", "remove");
        } else {
            operation.put("op", "add").set("value", selector);
        }
        operation.put("path", "/spec/template/spec/nodeSelector");
        lab.kubectl("patch", "deployment/shardshop-order-saga", "--type=json", "-p", Json.newObject().arrayNode().add(operation).toString());
        require(deployment.resource("deployment", "shardshop-order-saga").at("/spec/template/spec/nodeSelector").equals(selector),
                "Saga placement patch did not preserve the requested complete selector");
    }

    static void replacement(JsonNode before, JsonNode after, String target) throws IOException {
        require(!before.path("metadata").path("uid").equals(after.path("metadata").path("uid"))
                        && !before.path("spec").path("nodeName").equals(after.path("spec").path("nodeName"))
                        && after.path("spec").path("nodeName").asText().equals(target),
                "Replacement saga JVM must run on the explicitly selected different worker");
    }

    /** HTTP replacement must leave the live saga JVM and each active logical reader unchanged. */
    public void rolloutHttp() throws IOException, InterruptedException {
        try (KindDrillGuard guard = new KindDrillGuard(lab).acquire()) {
            guard.run(() -> {
                saga.deployed();
                buyer();
                JsonNode owner = saga.pod("order-saga");
                java.util.Map<String, Long> slots = new java.util.LinkedHashMap<>();
                for (String shard : lab.shards()) slots.put(shard, activeReader(shard));
                JsonNode before = deployment.resource("deployment", "shardshop-order");
                Set<String> originalHttp = httpPodUids();
                require(originalHttp.size() == 2, "HTTP rollout requires two initial pod identities");
                JsonNode annotations = before.at("/spec/template/metadata/annotations").deepCopy();
                guard.onClose("restore HTTP rollout annotation and two ready HTTP replicas", () -> {
                    patchHttpAnnotations(annotations);
                    awaitHttp();
                    return true;
                });
                KindHttpWriter writer = new KindHttpWriter(lab, buyer, run + "-rollout", request("USD", rolloutProduct(), 1));
                try (writer) {
                    lab.await("scoped HTTP writer is committing before rollout", Duration.ofSeconds(30), () -> writer.count() >= 2);
                    int[] replacementStartWrites = {-1};
                    boolean[] wroteDuringReplacement = {false};
                    ObjectNode changed = annotations.isObject() ? annotations.deepCopy() : Json.newObject();
                    changed.put("shardshop.javacraft/drill", run);
                    patchHttpAnnotations(changed);
                    lab.await("HTTP rolls while writes continue and the saga keeps its live CDC slots", Duration.ofSeconds(180), () -> {
                        require(owner.path("metadata").path("uid").equals(saga.pod("order-saga").path("metadata").path("uid")),
                                "HTTP rollout replaced the saga owner");
                        for (String shard : lab.shards())
                            require(activeReader(shard) == slots.get(shard), "HTTP rollout reconnected the CDC reader on " + shard);
                        int committed = writer.count();
                        boolean current = httpCurrent();
                        if (!current && httpPodUids().stream().anyMatch(uid -> !originalHttp.contains(uid))) {
                            if (replacementStartWrites[0] < 0) replacementStartWrites[0] = writer.count();
                            else if (committed > replacementStartWrites[0]) wroteDuringReplacement[0] = true;
                        }
                        return current;
                    });
                    require(wroteDuringReplacement[0], "No scoped HTTP order committed while replacement was in progress");
                    lab.await("both original HTTP pods terminate after replacement", Duration.ofSeconds(60), () ->
                            httpCurrent() && httpPodUids().stream().noneMatch(originalHttp::contains));
                    int afterRollout = writer.count();
                    lab.await("HTTP writer keeps committing after rollout", Duration.ofSeconds(20), () -> writer.count() > afterRollout);
                }
                for (String id : writer.accepted()) awaitOrder(id, "CONFIRMED");
                saga.invariants();
            });
        }
    }

    private long activeReader(String shard) throws IOException, InterruptedException {
        String slot = "shardshop_order_" + shard.replace('-', '_');
        long pid = saga.number(shard, "SELECT coalesce(active_pid,0) FROM pg_replication_slots WHERE slot_name=" + KindSaga.literal(slot));
        require(pid > 0, "No active saga CDC slot on " + shard);
        return pid;
    }

    private void patchHttpAnnotations(JsonNode annotations) throws IOException, InterruptedException {
        ObjectNode operation = Json.newObject().put("op", annotations.isMissingNode() ? "remove" : "add")
                .put("path", "/spec/template/metadata/annotations");
        if (!annotations.isMissingNode()) operation.set("value", annotations);
        lab.kubectl("patch", "deployment/shardshop-order", "--type=json", "-p", Json.newObject().arrayNode().add(operation).toString());
    }

    private boolean httpCurrent() throws IOException, InterruptedException {
        JsonNode resource = deployment.resource("deployment", "shardshop-order");
        JsonNode status = resource.path("status");
        return status.path("readyReplicas").asInt() == 2 && status.path("updatedReplicas").asInt() == 2
                && status.path("replicas").asInt() == 2 && status.path("observedGeneration").asLong(-1)
                >= resource.path("metadata").path("generation").asLong();
    }

    private Set<String> httpPodUids() throws IOException, InterruptedException {
        return deployment.pods("app.kubernetes.io/name=shardshop-order").stream()
                .map(pod -> pod.path("metadata").path("uid").asText())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private void awaitHttp() throws IOException, InterruptedException {
        lab.await("two current ready HTTP replicas with old pods terminated", Duration.ofSeconds(180), () -> {
            List<JsonNode> pods = deployment.pods("app.kubernetes.io/name=shardshop-order");
            return httpCurrent() && pods.size() == 2 && pods.stream().allMatch(KindDeployment::ready);
        });
    }

    private long checkpoint(String shard) throws IOException, InterruptedException {
        return saga.number(shard, "SELECT coalesce(max(coalesce((offset_val::jsonb->>'lsn_commit')::bigint,"
                + "(offset_val::jsonb->>'lsn')::bigint)),0) FROM ordering.debezium_offset_storage");
    }

    private void pauseProducer(KindDrillGuard guard) throws IOException, InterruptedException {
        int replicas = deployment.resource("deployment", "shardshop-order-producer").path("spec").path("replicas").asInt(-1);
        require(replicas == 1, "Saga drill requires the normal single producer replica");
        guard.onClose("restore order producer replica count after the normal ledger policy is ready", () -> {
            saga.normalPolicy();
            awaitDeployment("ledger");
            lab.kubectl("scale", "deployment/shardshop-order-producer", "--replicas=" + replicas);
            awaitDeployment("order-producer");
            return true;
        });
        lab.kubectl("scale", "deployment/shardshop-order-producer", "--replicas=0");
        lab.await("producer stopped and existing sagas drained", Duration.ofSeconds(180), () -> {
            if (!deployment.pods("app.kubernetes.io/name=shardshop-order-producer").isEmpty()) return false;
            for (String shard : lab.shards())
                if (saga.number(shard, "SELECT count(*) FROM ordering.order_sagas WHERE status IN ('PENDING_STOCK','PENDING_LEDGER') "
                        + "OR (status='CANCELLED' AND stock_released_at IS NULL)") != 0) return false;
            return true;
        });
    }

    private void buyer() throws IOException, InterruptedException {
        for (String shard : lab.shards()) {
            String candidate = saga.sql(shard, "SELECT buyer_id::text FROM ordering.buyers ORDER BY buyer_id LIMIT 1").strip();
            if (!candidate.isEmpty()) { buyer = KindSaga.id(candidate); home = shard; return; }
        }
        throw new IOException("Producer has not created any buyers");
    }

    // Seeded catalog-v2 products only: the drill must not use the stock of the saga gate's own products.
    private JsonNode product(String currency) throws IOException, InterruptedException {
        for (String shard : lab.shards()) {
            String product = saga.sql(shard, "SELECT row_to_json(x)::text FROM (SELECT seller_id::text, product_id::text, stock, initial_stock "
                    + "FROM catalog.products WHERE currency=" + KindSaga.literal(currency) + " AND stock>4 "
                    + "AND starts_with(creation_key, 'catalog-v2.') ORDER BY product_id LIMIT 1) x").strip();
            if (!product.isEmpty()) return KindLab.parse(product);
        }
        throw new IOException("No seeded product with remaining " + currency + " stock");
    }

    private JsonNode rolloutProduct() throws IOException, InterruptedException {
        for (String shard : lab.shards()) {
            String row = saga.sql(shard, "SELECT row_to_json(x)::text FROM (SELECT seller_id::text, product_id::text "
                    + "FROM catalog.products WHERE currency='USD' AND stock>=600 AND starts_with(creation_key,'catalog-v2.') "
                    + "ORDER BY stock DESC, product_id LIMIT 1) x").strip();
            if (!row.isEmpty()) return KindLab.parse(row);
        }
        throw new IOException("HTTP rollout needs a seeded USD product with stock for its bounded writer");
    }

    static ObjectNode request(String currency, JsonNode product, int quantity) throws IOException {
        ObjectNode request = Json.newObject().put("currency", currency);
        request.putArray("items").addObject().put("sellerId", KindSaga.id(product.path("seller_id").asText()))
                .put("productId", KindSaga.id(product.path("product_id").asText())).put("quantity", quantity);
        return request;
    }

    private String allocate(Tunnel tunnel) throws IOException, InterruptedException {
        JsonNode body = http(tunnel, "POST", "/api/v1/buyers/" + buyer + "/order-allocations",
                Json.newObject().put("runName", run).put("requestOrdinal", Integer.toString(ordinal++)), 201);
        return KindSaga.id(body.path("orderId").asText());
    }

    private String accept(Tunnel tunnel, ObjectNode request) throws IOException, InterruptedException {
        String id = allocate(tunnel);
        http(tunnel, "PUT", path(id), request, 202);
        return id;
    }

    private String path(String id) { return "/api/v1/buyers/" + buyer + "/orders/" + id; }

    private void awaitOrder(String id, String status) throws IOException, InterruptedException {
        lab.await("scoped order reaches " + status + " with complete effects", Duration.ofSeconds(120), () -> {
            require(saga.sql(home, "SELECT status FROM ordering.orders WHERE order_id=" + KindSaga.id(id)).strip().equals(status),
                    "Scoped order has not reached " + status);
            saga.verifyOrder(home, id, status);
            return true;
        });
    }

    private String reason(String id) throws IOException, InterruptedException {
        return saga.sql(home, "SELECT cancellation_reason FROM ordering.order_sagas WHERE order_id=" + KindSaga.id(id)).strip();
    }

    private void replay(Tunnel tunnel, String id, boolean cleanup) throws Exception {
        ObjectNode command = (ObjectNode) KindLab.parse(saga.sql(home, "SELECT o.envelope::text FROM ordering.order_outbox o "
                + "JOIN ordering.order_sagas s USING(saga_id) WHERE s.order_id=" + KindSaga.id(id)));
        JsonNode result = KindLab.parse(saga.sql("ledger-db", "SELECT envelope::text FROM ledger.ledger_result_ids WHERE order_id=" + id));
        String messageId = KindSaga.id(command.path("messageId").asText());
        String resultId = KindSaga.id(result.path("messageId").asText());
        long attempt = saga.number("ledger-db", "SELECT publication_attempt FROM ledger.ledger_result_ids WHERE message_id=" + messageId);
        String originalDecision = saga.sql("ledger-db", "SELECT row_to_json(o)::text FROM ledger.ledger_operations o WHERE order_id=" + id).strip();
        if (cleanup) {
            require(saga.number(home, "SELECT count(*) FROM ordering.order_outbox WHERE message_id=" + messageId + " AND published_at IS NOT NULL") == 1,
                    "Transport cleanup requires confirmed source publication");
            long fence = saga.number(home, "SELECT pg_wal_lsn_diff(pg_current_wal_lsn(),'0/0')::bigint");
            lab.await("CDC checkpoint passes scoped source cleanup fence", Duration.ofSeconds(60), () -> checkpoint(home) >= fence);
            // The original command/result were saved above; delete only this test order's disposable transport state.
            saga.sql("ledger-db", "BEGIN; DELETE FROM ledger.ledger_inbox WHERE message_id=" + messageId
                    + "; DELETE FROM ledger.ledger_outbox WHERE message_id=" + resultId + "; COMMIT");
            saga.sql(home, "BEGIN; DELETE FROM ordering.order_inbox WHERE message_id=" + resultId
                    + "; DELETE FROM ordering.order_outbox WHERE message_id=" + messageId + "; COMMIT");
        }
        var parkingBefore = parking();
        publish(tunnel, "order", "ledger.commands", "ledger.record-order", command);
        lab.await("duplicate command consumed with another confirmed result publication", Duration.ofSeconds(90), () ->
                saga.number("ledger-db", "SELECT count(*) FROM ledger.ledger_outbox WHERE message_id=" + resultId
                        + " AND publication_attempt>" + attempt + " AND published_at IS NOT NULL") == 1
                        && saga.number(home, "SELECT count(*) FROM ordering.order_inbox WHERE message_id=" + resultId) == 1);
        long conflictsBefore = saga.number(home, "SELECT count(*) FROM ordering.conflicting_results WHERE order_id=" + id);
        publish(tunnel, "ledger", "order.saga-results", "order.ledger-results", result);
        publish(tunnel, "ledger", "order.saga-results", "order.ledger-results", result);
        queuesDrained();
        require(parkingBefore.equals(parking()), "Identical replay parked command or result deliveries");
        require(saga.number(home, "SELECT count(*) FROM ordering.conflicting_results WHERE order_id=" + id) == conflictsBefore,
                "Identical duplicate results were quarantined as conflicts");
        require(originalDecision.equals(saga.sql("ledger-db", "SELECT row_to_json(o)::text FROM ledger.ledger_operations o WHERE order_id=" + id).strip()),
                "Replay reevaluated or changed the permanent ledger decision");
        require(result.equals(KindLab.parse(saga.sql("ledger-db", "SELECT envelope::text FROM ledger.ledger_result_ids WHERE order_id=" + id))),
                "Replay changed the exact retained result envelope");
        String status = result.path("outcome").asText().equals("RECORDED") ? "CONFIRMED" : "CANCELLED";
        awaitOrder(id, status);
    }

    private void conflictingCommand(Tunnel tunnel, String id) throws Exception {
        ObjectNode command = (ObjectNode) KindLab.parse(saga.sql(home, "SELECT o.envelope::text FROM ordering.order_outbox o "
                + "JOIN ordering.order_sagas s USING(saga_id) WHERE s.order_id=" + KindSaga.id(id)));
        ((ObjectNode) command.at("/snapshot/items/0")).put("productName", "Fault injection changed product name");
        long before = saga.number("ledger-db", "SELECT coalesce(sum(delivery_count),0)::bigint FROM ledger.conflicting_commands WHERE order_id=" + id);
        publish(tunnel, "order", "ledger.commands", "ledger.record-order", command);
        lab.await("conflicting command quarantined independently of identical replay", Duration.ofSeconds(60), () ->
                saga.number("ledger-db", "SELECT coalesce(sum(delivery_count),0)::bigint FROM ledger.conflicting_commands WHERE order_id=" + id) == before + 1);
        awaitOrder(id, "CANCELLED");
    }

    /** The drill's own rejection check: every supported currency except EUR. */
    private void restrictPolicy(KindDrillGuard guard) throws IOException, InterruptedException {
        policy(guard, String.join(",", CurrencyPolicy.SUPPORTED_CURRENCIES.stream().filter(currency -> !currency.equals("EUR")).toList()));
    }

    private void expandPolicy(KindDrillGuard guard) throws IOException, InterruptedException {
        policy(guard, String.join(",", CurrencyPolicy.SUPPORTED_CURRENCIES));
    }

    private void policy(KindDrillGuard guard, String currencies) throws IOException, InterruptedException {
        JsonNode resource = deployment.resource("deployment", "shardshop-ledger");
        JsonNode previous = resource.at("/spec/template/spec/containers/0/env").deepCopy();
        guard.onClose("restore ledger allowlist environment and ready deployment", () -> {
            replaceEnv("ledger", previous);
            awaitDeployment("ledger");
            return true;
        });
        var selected = Json.newObject().arrayNode();
        require(previous.isArray(), "Ledger environment must be an explicit array");
        for (JsonNode variable : previous)
            if (!variable.path("name").asText().equals("SHARDSHOP_LEDGER_ALLOWED_CURRENCIES")) selected.add(variable.deepCopy());
        selected.addObject().put("name", "SHARDSHOP_LEDGER_ALLOWED_CURRENCIES").put("value", currencies);
        replaceEnv("ledger", selected);
        awaitDeployment("ledger");
        require(saga.pod("ledger").at("/spec/containers/0/env").equals(selected),
                "Ready ledger pod has not adopted the scoped allowlist");
    }

    private void replaceEnv(String app, JsonNode env) throws IOException, InterruptedException {
        ObjectNode operation = Json.newObject().put("op", "replace").put("path", "/spec/template/spec/containers/0/env");
        operation.set("value", env);
        lab.kubectl("patch", "deployment/shardshop-" + app, "--type=json", "-p", Json.newObject().arrayNode().add(operation).toString());
    }

    private void race(Tunnel tunnel) throws Exception {
        // Re-read after the two initial saga checks; producer is stopped, so only these orders consume this stock.
        JsonNode current = product("USD");
        int quantity = current.path("stock").asInt() / 2 + 1;
        ObjectNode request = request("USD", current, quantity);
        List<String> ids = List.of(allocate(tunnel), allocate(tunnel));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<JsonNode>> responses = new ArrayList<>();
            for (String id : ids) responses.add(executor.submit(() -> http(tunnel, "PUT", path(id), request, 202)));
            for (var response : responses) response.get(15, TimeUnit.SECONDS);
        }
        lab.await("concurrent orders produce one confirmation and one out-of-stock cancellation", Duration.ofSeconds(120), () ->
                saga.number(home, "SELECT count(*) FROM ordering.orders WHERE order_id IN (" + String.join(",", ids) + ") AND status='CONFIRMED'") == 1
                        && saga.number(home, "SELECT count(*) FROM ordering.order_sagas WHERE order_id IN (" + String.join(",", ids)
                        + ") AND status='CANCELLED' AND cancellation_reason='OUT_OF_STOCK' AND stock_released_at IS NOT NULL") == 1);
        for (String id : ids) {
            String status = saga.sql(home, "SELECT status FROM ordering.orders WHERE order_id=" + id).strip();
            awaitOrder(id, status);
            require(http(tunnel, "PUT", path(id), request, 200).path("status").asText().equals(status), "Identical HTTP retry changed terminal state");
            ObjectNode conflict = request.deepCopy();
            ((ObjectNode) conflict.at("/items/0")).put("quantity", quantity + 1);
            require(http(tunnel, "PUT", path(id), conflict, 409).path("code").asText().equals("ORDER_ID_CONFLICT"),
                    "Conflicting HTTP retry must preserve the original order");
        }
        saga.invariants();
    }

    private void atomicStockFailure(Tunnel tunnel) throws Exception {
        JsonNode first = product("USD");
        JsonNode second = null;
        for (String shard : lab.shards()) {
            String row = saga.sql(shard, "SELECT row_to_json(x)::text FROM (SELECT seller_id::text,product_id::text,stock,initial_stock "
                    + "FROM catalog.products WHERE currency='USD' AND product_id>" + KindSaga.id(first.path("product_id").asText())
                    + " AND starts_with(creation_key, 'catalog-v2.') ORDER BY product_id LIMIT 1) x").strip();
            if (!row.isEmpty()) { second = KindLab.parse(row); break; }
        }
        require(second != null && second.path("initial_stock").asInt() < Integer.MAX_VALUE, "Atomic stock failure needs a second bounded USD product");
        ObjectNode request = request("USD", first, 1);
        ((com.fasterxml.jackson.databind.node.ArrayNode) request.path("items")).add(request("USD", second, second.path("initial_stock").asInt() + 1).path("items").get(0));
        String id = accept(tunnel, request);
        awaitOrder(id, "CANCELLED");
        require(reason(id).equals("OUT_OF_STOCK"), "Oversized order must fail stock before ledger");
        // Each supported currency occupies one catalog shard in this retained topology. A shard group
        // is atomic, so failure on the second item rolls back the first item's reservation and stock write.
        for (String shard : lab.shards()) {
            require(saga.number(shard, "SELECT count(*) FROM catalog.stock_reservations WHERE order_id=" + id) == 0,
                    "A failed atomic catalog group must leave no reservation rows");
            require(saga.number(shard, "SELECT count(*) FROM catalog.profit_credits WHERE order_id=" + id) == 0,
                    "A stock failure must never credit seller profit");
        }
        for (JsonNode item : List.of(first, second)) {
            long unchanged = 0;
            for (String shard : lab.shards()) unchanged += saga.number(shard,
                    "SELECT count(*) FROM catalog.products WHERE product_id=" + KindSaga.id(item.path("product_id").asText())
                            + " AND stock=" + item.path("stock").asInt());
            require(unchanged == 1, "A failed atomic catalog group must preserve every item's prior stock");
        }
        http(tunnel, "PUT", path(id), request, 200);
        saga.invariants();
    }

    private void awaitDeployment(String app) throws IOException, InterruptedException {
        lab.await("one current ready " + app + " replica", Duration.ofSeconds(150), () -> {
            JsonNode state = deployment.resource("deployment", "shardshop-" + app);
            return state.path("status").path("readyReplicas").asInt() == 1
                    && state.path("status").path("updatedReplicas").asInt() == 1
                    && state.path("status").path("observedGeneration").asLong(-1) >= state.path("metadata").path("generation").asLong()
                    && KindDeployment.ready(saga.pod(app));
        });
    }

    private void publish(Tunnel tunnel, String role, String exchange, String routingKey, JsonNode envelope) throws Exception {
        String encoded = deployment.resource("secret", "shardshop-rabbitmq").path("data").path(role + "-password").asText();
        BrokerSettings settings = new BrokerSettings("127.0.0.1", tunnel.port, "shardshop", "shardshop-" + role,
                new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8), Duration.ofSeconds(5));
        try (ConfirmedPublisher publisher = new ConfirmedPublisher(settings,
                new ConfirmedPublisher.Destination(exchange, routingKey, "kind-drill", "kind-drill-publisher"))) {
            publisher.publish(envelope.path("messageId").asText(), Json.bytes(envelope));
        }
    }

    private java.util.Map<String, Long> parking() throws IOException, InterruptedException {
        java.util.Map<String, Long> counts = new java.util.TreeMap<>();
        for (JsonNode queue : queueState()) {
            String name = queue.path("name").asText();
            if (!name.endsWith(".dlq")) continue;
            counts.put(name, queue.path("messages_ready").asLong(-1) + queue.path("messages_unacknowledged").asLong(-1));
        }
        require(counts.size() == 2 && counts.values().stream().allMatch(count -> count >= 0), "Missing saga parking queues");
        return java.util.Map.copyOf(counts);
    }

    private JsonNode queueState() throws IOException, InterruptedException {
        return KindLab.parse(lab.kubectl("exec", "shardshop-rabbitmq-0", "-c", "rabbitmq", "--",
                "rabbitmqctl", "--quiet", "--timeout", "10", "--formatter=json", "list_queues", "-p", "shardshop",
                "name", "messages_ready", "messages_unacknowledged"));
    }

    private void queuesDrained() throws IOException, InterruptedException {
        lab.await("injected commands/results acknowledged with empty ready/unacknowledged queues", Duration.ofSeconds(60), () -> {
            JsonNode queues = queueState();
            int found = 0;
            for (JsonNode queue : queues) {
                if (!List.of("ledger.record-order", "order.ledger-results").contains(queue.path("name").asText())) continue;
                found++;
                if (queue.path("messages_ready").asLong(-1) != 0 || queue.path("messages_unacknowledged").asLong(-1) != 0) return false;
            }
            return found == 2;
        });
    }

    private JsonNode http(Tunnel tunnel, String method, String path, JsonNode body, int expected) throws IOException, InterruptedException {
        return tunnel.call(method, path, null, body, Set.of(expected));
    }
}
