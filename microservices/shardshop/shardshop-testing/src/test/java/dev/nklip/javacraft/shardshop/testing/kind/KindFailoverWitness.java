package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.Json;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static dev.nklip.javacraft.shardshop.testing.kind.KindDeployment.require;

/** Real HTTP orders and permanent SQL evidence; the caller owns all CNPG and workload mutations. */
final class KindFailoverWitness {
    private static final Duration RECOVERY = Duration.ofSeconds(180);
    private final KindLab lab;
    private final KindTopology topology;
    private final KindSaga saga;
    private final String shard;
    private final String slot;
    private final String run = "kind-failover-" + UUID.randomUUID();
    private String buyer;
    private JsonNode product;
    private Accepted confirmed;
    private int ordinal;

    KindFailoverWitness(KindLab lab, KindTopology topology, String shard) {
        this.lab = lab;
        this.topology = topology;
        this.saga = new KindSaga(lab);
        this.shard = shard;
        this.slot = "shardshop_order_" + shard.replace('-', '_');
    }

    record Accepted(String order, String saga, String command, String message, String result,
                    String immutableHash, String envelopeHash, long sourceUpperBound, long resumeBefore) { }
    record Cdc(String history, String key, long position) { }
    record Snapshot(String primary, Accepted confirmed, Accepted pending, Cdc cdc) { }

    void initialize() throws IOException, InterruptedException {
        require(lab.shards().contains(shard), "Failover witness needs an inventory shard");
        saga.normalPolicy();
        // Derive the HTTP creation key's target from the deployed immutable routing contract.
        JsonNode routing = KindLab.parse(lab.kubectl("get", "configmap", "shardshop-routing", "-o", "json"));
        KindSafety.validateTopology(lab.inventory(), routing, "failover routing");
        String properties = routing.path("data").path("application.properties").asText();
        List<String> names = Arrays.asList(property(properties, "shardshop.routing.shards").split(","));
        List<String> regions = Arrays.asList(property(properties, "shardshop.routing.regions").split(","));
        require(names.equals(lab.shards()) && regions.size() == names.size(), "Failover routing differs from inventory");
        String region = regions.get(names.indexOf(shard));
        List<String> eligible = names.stream().filter(name -> regions.get(names.indexOf(name)).equals(region)).toList();
        String key = buyerKey(run, region, eligible, shard);
        try (KindSaga.Tunnel order = new KindSaga.Tunnel(lab, "service/shardshop-order", 8080)) {
            ObjectNode payload = KindSaga.gateBuyer().put("region", region);
            buyer = KindSaga.id(order.call("POST", "/api/v1/buyers", key, payload, Set.of(200, 201)).path("buyerId").asText());
        }
        require(saga.number(shard, "SELECT count(*) FROM ordering.buyers WHERE buyer_id=" + buyer) == 1,
                "HTTP fixture buyer did not commit on selected failover shard");
        for (String peer : lab.shards()) {
            if (peer.equals(shard)) continue;
            String row = saga.sql(peer, "SELECT row_to_json(x)::text FROM (SELECT seller_id::text, product_id::text, currency "
                    + "FROM catalog.products WHERE stock>=32 AND starts_with(creation_key,'catalog-v2.') "
                    + "ORDER BY stock DESC,product_id LIMIT 1) x").strip();
            if (!row.isEmpty()) { product = KindLab.parse(row); break; }
        }
        require(product != null, "Failover witness requires a seeded peer-shard product with at least 32 units");
        cdc();
        confirmed = accepted();
        awaitConfirmed(confirmed);
    }

    Accepted accepted() throws IOException, InterruptedException {
        require(buyer != null && product != null, "Initialize failover witness before accepting orders");
        long before = cdc().position();
        String id;
        ObjectNode allocation = Json.newObject().put("runName", run).put("requestOrdinal", Integer.toString(ordinal++));
        ObjectNode payload = KindSagaDrill.request(product.path("currency").asText(), product, 1);
        try (KindSaga.Tunnel order = new KindSaga.Tunnel(lab, "service/shardshop-order", 8080);
             HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                     .connectTimeout(Duration.ofSeconds(3)).build()) {
            HttpRequest allocate = writeRequest(order.port, "POST", "/api/v1/buyers/" + buyer + "/order-allocations", allocation);
            id = KindSaga.id(awaitWrite(allocate, 201, request -> sendWrite(client, request)).path("orderId").asText());
            HttpRequest put = writeRequest(order.port, "PUT", path(id), payload);
            awaitWrite(put, 202, request -> sendWrite(client, request));
        }
        lab.await("acknowledged order has its original committed CDC command", Duration.ofSeconds(60), () ->
                saga.number(shard, "SELECT count(*) FROM ordering.order_outbox o JOIN ordering.order_sagas s USING(saga_id) "
                        + "WHERE s.order_id=" + id) == 1);
        return observe(id, before);
    }

    void awaitConfirmed(Accepted accepted) throws IOException, InterruptedException {
        lab.await("failover order confirms with its original identities and single business effects", RECOVERY, () -> {
            sameIdentity(accepted, observe(accepted.order(), accepted.resumeBefore()));
            require(saga.sql(shard, "SELECT status FROM ordering.orders WHERE order_id=" + accepted.order()).strip().equals("CONFIRMED"),
                    "Acknowledged failover order has not confirmed");
            saga.verifyOrder(shard, accepted.order(), "CONFIRMED");
            JsonNode delivered = delivery(accepted);
            verifyDelivery(accepted, delivered);
            require(delivered.path("buyer").asText().equals(buyer), "Permanent ledger decision changed the acknowledged buyer");
            require(saga.number(shard, "SELECT count(*) FROM ordering.order_outbox o JOIN ordering.order_result_ids r "
                    + "USING(message_id,result_message_id,saga_id) WHERE o.message_id=" + accepted.message()
                    + " AND o.result_message_id=" + accepted.result() + " AND o.published_at IS NOT NULL "
                    + "AND r.received_envelope IS NOT NULL") == 1,
                    "Original CDC outbox was not published and its reserved result applied");
            return true;
        });
        try (KindSaga.Tunnel order = new KindSaga.Tunnel(lab, "service/shardshop-order", 8080);
             HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                     .connectTimeout(Duration.ofSeconds(3)).build()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + order.port + path(accepted.order())))
                    .timeout(Duration.ofSeconds(10)).GET().build();
            awaitHttpConfirmed(lab, () -> {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                return new OrderRead(response.statusCode(), response.statusCode() == 200 ? KindLab.parse(response.body()) : Json.newObject());
            });
        }
    }

    record OrderRead(int status, JsonNode body) { }
    @FunctionalInterface interface ReadOrder { OrderRead get() throws IOException, InterruptedException; }
    @FunctionalInterface interface WriteOrder { OrderRead send(HttpRequest request) throws IOException, InterruptedException; }

    interface WriteTime {
        long now();
        void sleep(long nanos) throws InterruptedException;
    }

    private static JsonNode awaitWrite(HttpRequest request, int created, WriteOrder send) throws IOException, InterruptedException {
        return awaitWrite(request, created, send, new WriteTime() {
            public long now() { return System.nanoTime(); }
            public void sleep(long nanos) throws InterruptedException { TimeUnit.NANOSECONDS.sleep(nanos); }
        });
    }

    /** Retry only the same frozen allocation coordinates or PUT ID/body, synchronously on the guard thread. */
    static JsonNode awaitWrite(HttpRequest request, int created, WriteOrder send, WriteTime time) throws IOException, InterruptedException {
        long deadline = time.now() + RECOVERY.toNanos();
        boolean uncertain = false;
        IOException last = null;
        // Reserve the full ten-second request deadline before starting another mutation.
        while (time.now() + Duration.ofSeconds(10).toNanos() <= deadline) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Failover HTTP write interrupted");
            try {
                OrderRead response = send.send(request);
                if (response.status() == created || uncertain && response.status() == 200) return response.body();
                if (response.status() != 503) throw new TerminalWriteFailure("Failover HTTP write expected " + created
                        + (uncertain ? " or replay 200" : "") + "; received " + response.status());
                uncertain = true; // 503 can mean an unknown commit; retry the exact durable identity.
                last = new IOException("Failover HTTP write returned transient 503");
            } catch (TerminalWriteFailure terminal) {
                throw terminal;
            } catch (IOException transport) {
                if (!transientWriteFailure(transport)) throw transport;
                uncertain = true;
                last = transport;
            }
            long remaining = deadline - time.now();
            if (remaining <= 0) break;
            long delay = Duration.ofMillis(400 + java.util.concurrent.ThreadLocalRandom.current().nextLong(200)).toNanos();
            time.sleep(Math.min(remaining, delay));
        }
        throw new IOException("Failover HTTP write exceeded its 180-second recovery deadline", last);
    }

    private static boolean transientWriteFailure(IOException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof java.net.ConnectException || cause instanceof java.net.SocketException
                    || cause instanceof java.net.http.HttpTimeoutException || cause instanceof java.io.EOFException) return true;
        return false;
    }

    private static final class TerminalWriteFailure extends IOException {
        private TerminalWriteFailure(String message) { super(message); }
    }

    private static HttpRequest writeRequest(int port, String method, String path, JsonNode body) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofByteArray(Json.bytes(body))).build();
    }

    private static OrderRead sendWrite(HttpClient client, HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        return new OrderRead(status, status == 200 || status == 201 || status == 202 ? KindLab.parse(response.body()) : Json.newObject());
    }

    /** Pool reconnection can return 503 after promotion; all other HTTP errors remain immediate failures. */
    static void awaitHttpConfirmed(KindLab lab, ReadOrder read) throws IOException, InterruptedException {
        AtomicReference<OrderRead> observed = new AtomicReference<>();
        lab.await("promoted order HTTP read returns its permanent confirmed outcome", RECOVERY, () -> {
            OrderRead response = read.get();
            observed.set(response);
            if (response.status() == 503) return false;
            // End the wait on an unexpected status so its strict assertion fails outside the retry loop.
            return response.status() != 200 || response.body().path("status").asText().equals("CONFIRMED");
        });
        OrderRead response = observed.get();
        require(response != null && response.status() == 200, "Promoted order HTTP GET expected 200; received "
                + (response == null ? "no response" : response.status()));
        require(response.body().path("status").asText().equals("CONFIRMED"),
                "Promoted order HTTP read disagrees with the permanent confirmed outcome");
    }

    Snapshot beforeTransition(Accepted pending) throws IOException, InterruptedException {
        require(confirmed != null, "Initialize the confirmed failover anchor first");
        awaitConfirmed(confirmed);
        pending(pending);
        Cdc cdc = cdc();
        lab.await("primary failover slot covers the saved durable checkpoint", Duration.ofSeconds(30), () -> {
            validateSlot(slot(topology.primary(shard)), slot, false, cdc.position());
            return true;
        });
        return new Snapshot(topology.primary(shard), confirmed, pending, cdc);
    }

    void syncedSlot(String standby, Snapshot snapshot) throws IOException, InterruptedException {
        require(!standby.equals(snapshot.primary()) && standby.matches(java.util.regex.Pattern.quote(shard) + "-[0-9]+"),
                "Slot witness needs a selected-shard standby");
        lab.await("promotable standby has the durable synchronized logical slot", RECOVERY, () -> {
            require(topology.sql(standby, "SELECT pg_is_in_recovery()").equals("t"), "Slot target is not a standby");
            validateSlot(slot(standby), slot, true, snapshot.cdc().position());
            return true;
        });
    }

    void afterTransition(Snapshot snapshot) throws IOException, InterruptedException {
        lab.await("promoted primary retains durable CDC identity and original acknowledged orders", RECOVERY, () -> {
            String primary = topology.primary(shard);
            require(!primary.equals(snapshot.primary()), "Failover did not change the selected primary");
            retained(snapshot.cdc(), cdc(), false);
            validateSlot(slot(primary), slot, false, snapshot.cdc().position());
            sameIdentity(snapshot.confirmed(), observe(snapshot.confirmed().order(), snapshot.confirmed().resumeBefore()));
            sameIdentity(snapshot.pending(), observe(snapshot.pending().order(), snapshot.pending().resumeBefore()));
            return true;
        });
        awaitConfirmed(snapshot.confirmed());
        resumedCdc(snapshot.pending());
        Accepted after = accepted();
        awaitConfirmed(after);
        lab.await("promoted CDC advances its original durable streaming checkpoint", RECOVERY, () -> {
            retained(snapshot.cdc(), cdc(), true);
            return true;
        });
    }

    /** A real new outbox insert must stay undelivered while either configured physical standby is absent. */
    Accepted pausedCdc(String missingStandby) throws IOException, InterruptedException {
        require(missingStandby.matches(java.util.regex.Pattern.quote(shard) + "-[0-9]+")
                && !missingStandby.equals(topology.primary(shard)), "Pause proof needs a selected-shard standby");
        String primary = topology.primary(shard);
        String configuration = topology.sql(primary, "SHOW synchronized_standby_slots");
        require(configuration.split(",").length == 2 && !configuration.isBlank(), "CDC pause proof needs both synchronized physical slots");
        // The normal quorum must still be configured and a peer must still acknowledge synchronous HTTP commits.
        require(topology.sql(primary, "SHOW synchronous_commit; SHOW synchronous_standby_names").startsWith("on\nANY 1 "),
                "CDC pause proof requires synchronous quorum writes");
        require(saga.number(shard, "SELECT count(*) FROM pg_stat_replication WHERE application_name="
                + KindSaga.literal(missingStandby)) == 0, "Selected standby still streams");
        Accepted accepted = accepted();
        long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        boolean blocked = false;
        do {
            pending(accepted);
            Cdc cdc = cdc();
            require(cdc.position() < accepted.sourceUpperBound(), "CDC checkpoint passed the blocked source WAL boundary");
            blocked |= saga.number(shard, "SELECT count(*) FROM pg_stat_activity a JOIN pg_replication_slots s ON s.active_pid=a.pid "
                    + "WHERE s.slot_name=" + KindSaga.literal(slot) + " AND a.wait_event='WaitForStandbyConfirmation'") == 1;
            if (System.nanoTime() < end) TimeUnit.MILLISECONDS.sleep(500);
        } while (System.nanoTime() < end);
        require(blocked, "No synchronized-standby WAL wait was observed for the live CDC reader");
        return accepted;
    }

    void resumedCdc(Accepted accepted) throws IOException, InterruptedException {
        awaitConfirmed(accepted);
        lab.await("original command drains and durable CDC checkpoint progresses", RECOVERY, () -> {
            require(cdc().position() > accepted.resumeBefore(), "CDC did not advance its saved resume position");
            return true;
        });
    }

    private void pending(Accepted accepted) throws IOException, InterruptedException {
        sameIdentity(accepted, observe(accepted.order(), accepted.resumeBefore()));
        JsonNode state = KindLab.parse(saga.sql(shard, "SELECT json_build_object('status',s.status,'published',o.published_at IS NOT NULL,"
                + "'received',r.received_envelope IS NOT NULL) FROM ordering.order_sagas s JOIN ordering.order_outbox o USING(saga_id) "
                + "JOIN ordering.order_result_ids r USING(message_id,result_message_id,saga_id) WHERE o.message_id=" + accepted.message()));
        require(state.path("status").asText().equals("PENDING_LEDGER") && state.path("published").isBoolean()
                && !state.path("published").booleanValue() && state.path("received").isBoolean() && !state.path("received").booleanValue(),
                "Blocked CDC command was delivered or is not a real pending ledger outbox");
        require(saga.number("ledger-db", "SELECT count(*) FROM ledger.ledger_operations WHERE order_id=" + accepted.order()) == 0
                && saga.number("ledger-db", "SELECT count(*) FROM ledger.ledger_inbox WHERE message_id=" + accepted.message()) == 0,
                "Blocked CDC command already reached the ledger");
    }

    private Accepted observe(String id, long resume) throws IOException, InterruptedException {
        JsonNode value = KindLab.parse(saga.sql(shard, "SELECT row_to_json(x)::text FROM (SELECT s.order_id::text AS order_id, "
                + "s.saga_id::text,s.command_id::text,o.message_id::text,o.result_message_id::text, "
                + "encode(sha256(convert_to(jsonb_build_object('order',to_jsonb(ord)-'status'-'updated_at', 'items',"
                + "(SELECT jsonb_agg(i ORDER BY item_position) FROM ordering.order_items i WHERE i.order_id=s.order_id))::text,'UTF8')),'hex') AS immutable_hash, "
                + "encode(sha256(convert_to(o.envelope::text,'UTF8')),'hex') AS envelope_hash, "
                + "pg_wal_lsn_diff(pg_current_wal_insert_lsn(),'0/0')::bigint AS source_upper "
                + "FROM ordering.order_sagas s JOIN ordering.orders ord USING(order_id) JOIN ordering.order_outbox o USING(saga_id) "
                + "WHERE s.order_id=" + KindSaga.id(id) + " ORDER BY o.created_at,o.message_id LIMIT 1) x"));
        return new Accepted(KindSaga.id(value.path("order_id").asText()), KindSaga.id(value.path("saga_id").asText()),
                KindSaga.id(value.path("command_id").asText()), KindSaga.id(value.path("message_id").asText()),
                KindSaga.id(value.path("result_message_id").asText()), value.path("immutable_hash").asText(),
                value.path("envelope_hash").asText(), value.path("source_upper").asLong(), resume);
    }

    private JsonNode delivery(Accepted accepted) throws IOException, InterruptedException {
        return KindLab.parse(saga.sql("ledger-db", "SELECT json_build_object('decisions',count(*),'entries',"
                + "(SELECT count(*) FROM ledger.ledger_entries WHERE order_id=" + accepted.order() + "),'saga',min(saga_id)::text,"
                + "'command',min(command_id)::text,'buyer',min(buyer_id)::text,'bindings',"
                + "(SELECT count(*) FROM ledger.ledger_result_ids WHERE order_id=" + accepted.order() + " AND message_id="
                + accepted.message() + " AND result_message_id=" + accepted.result() + ")) FROM ledger.ledger_operations WHERE order_id=" + accepted.order()));
    }

    private Cdc cdc() throws IOException, InterruptedException {
        String history = saga.sql(shard, "SELECT coalesce(json_agg(s ORDER BY reader_name),'[]'::json)::text FROM ordering.cdc_reader_state s").strip();
        JsonNode readers = KindLab.parse(history);
        require(readers.isArray() && readers.size() == 1 && readers.path(0).path("reader_name").asText().equals(slot)
                && !readers.path(0).path("initialized_at").asText().isBlank(), "Missing permanent CDC initialization history");
        JsonNode offsets = KindLab.parse(saga.sql(shard, "SELECT coalesce(json_agg(s),'[]'::json)::text FROM "
                + "(SELECT offset_key,offset_val FROM ordering.debezium_offset_storage) s"));
        return checkpoint(history, offsets, slot);
    }

    private JsonNode slot(String pod) throws IOException, InterruptedException {
        return KindLab.parse(topology.sql(pod, "SELECT row_to_json(s)::text FROM (SELECT slot_name,slot_type,plugin,failover,synced,temporary,active,"
                + "database=current_database() AS same_database,wal_status,invalidation_reason,"
                + "pg_wal_lsn_diff(confirmed_flush_lsn,'0/0')::bigint AS confirmed FROM pg_replication_slots WHERE slot_name="
                + KindSaga.literal(slot) + ") s"));
    }

    static Cdc checkpoint(String history, JsonNode offsets, String identity) throws IOException {
        require(offsets.isArray() && offsets.size() == 1, "CDC must retain exactly one durable connector offset");
        String key = offsets.path(0).path("offset_key").asText();
        JsonNode partition = KindLab.parse(key);
        JsonNode value = KindLab.parse(offsets.path(0).path("offset_val").asText());
        require(partition.isArray() && partition.size() == 2 && partition.path(0).asText().equals(identity)
                && partition.path(1).isObject() && partition.path(1).size() == 1
                && partition.path(1).path("server").asText().equals(identity), "CDC durable offset changed connector identity");
        JsonNode snapshot = value.path("snapshot");
        require(snapshot.isMissingNode() || snapshot.isBoolean() && !snapshot.booleanValue()
                || snapshot.isTextual() && snapshot.textValue().equals("false"), "CDC returned to a bootstrap snapshot");
        require(value.path("snapshot_completed").isMissingNode() || value.path("snapshot_completed").isBoolean()
                        && value.path("snapshot_completed").booleanValue(),
                "CDC snapshot is incomplete");
        JsonNode lsn = value.path("lsn");
        require(lsn.isIntegralNumber() && lsn.canConvertToLong() && lsn.longValue() > 0, "CDC offset lacks a source LSN");
        JsonNode commit = value.path("lsn_commit");
        require(commit.isMissingNode() || commit.isNull() || commit.isIntegralNumber() && commit.canConvertToLong()
                && commit.longValue() > 0, "CDC offset has an invalid commit LSN");
        return new Cdc(history, key, commit.isIntegralNumber() ? commit.longValue() : lsn.longValue());
    }

    static void retained(Cdc before, Cdc after, boolean progressed) throws IOException {
        require(before.history().equals(after.history()), "Promotion recreated permanent CDC initialization history");
        require(before.key().equals(after.key()), "Promotion replaced the durable CDC partition");
        require(progressed ? after.position() > before.position() : after.position() >= before.position(),
                "Promotion rewound or failed to advance the durable CDC offset");
    }

    static void validateSlot(JsonNode value, String identity, boolean standby, long resume) throws IOException {
        // PostgreSQL 18 retains synced=true as provenance after promotion; only standby slots require it.
        // https://www.postgresql.org/docs/18/view-pg-replication-slots.html
        require(value.path("slot_name").asText().equals(identity) && value.path("slot_type").asText().equals("logical")
                && value.path("plugin").asText().equals("pgoutput") && value.path("same_database").asBoolean()
                && value.path("failover").asBoolean() && value.path("temporary").isBoolean() && !value.path("temporary").booleanValue()
                && value.path("synced").isBoolean() && (!standby || value.path("synced").booleanValue())
                && value.path("invalidation_reason").isNull() && Set.of("reserved", "extended").contains(value.path("wal_status").asText())
                && value.path("confirmed").isIntegralNumber() && value.path("confirmed").asLong() >= resume,
                "Logical slot is absent, unsynchronized, temporary, invalidated or behind the durable resume position");
        if (standby) require(!value.path("active").asBoolean(), "Synchronized standby slot must not have a consumer");
    }

    static void sameIdentity(Accepted before, Accepted after) throws IOException {
        require(before.order().equals(after.order()) && before.saga().equals(after.saga()) && before.command().equals(after.command())
                && before.message().equals(after.message()) && before.result().equals(after.result())
                && before.immutableHash().matches("[0-9a-f]{64}") && before.immutableHash().equals(after.immutableHash())
                && before.envelopeHash().matches("[0-9a-f]{64}") && before.envelopeHash().equals(after.envelopeHash()),
                "Promotion changed acknowledged order, saga or original CDC command identity");
    }

    static void verifyDelivery(Accepted accepted, JsonNode delivery) throws IOException {
        require(delivery.path("decisions").asInt() == 1 && delivery.path("entries").asInt() == 1
                && delivery.path("bindings").asInt() == 1 && delivery.path("saga").asText().equals(accepted.saga())
                && delivery.path("command").asText().equals(accepted.command()),
                "Original failover command lacks exactly one permanent ledger effect and transport binding");
    }

    static String buyerKey(String prefix, String region, List<String> eligible, String target) throws IOException {
        require(eligible.contains(target), "Buyer target is outside its region");
        try {
            for (int attempt = 0; attempt < 1024; attempt++) {
                String key = prefix + ".buyer." + attempt;
                BigInteger hash = new BigInteger(1, MessageDigest.getInstance("SHA-256")
                        .digest(("buyer\0" + region + "\0" + key).getBytes(StandardCharsets.UTF_8)));
                if (eligible.get(hash.mod(BigInteger.valueOf(eligible.size())).intValue()).equals(target)) return key;
            }
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        throw new IOException("Could not derive a bounded selected-shard HTTP buyer creation key");
    }

    private static String property(String text, String name) throws IOException {
        List<String> values = text.lines().filter(line -> line.startsWith(name + "=")).map(line -> line.substring(name.length() + 1)).toList();
        require(values.size() == 1 && !values.getFirst().isBlank(), "Missing deployed routing property " + name);
        return values.getFirst();
    }

    private String path(String order) { return "/api/v1/buyers/" + buyer + "/orders/" + order; }
}
