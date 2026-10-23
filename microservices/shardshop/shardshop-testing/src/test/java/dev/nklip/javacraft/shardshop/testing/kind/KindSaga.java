package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nklip.javacraft.shardshop.common.CurrencyPolicy;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.Json;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static dev.nklip.javacraft.shardshop.testing.kind.KindDeployment.require;

/**
 * Checks the deployed saga with the gate's own buyer, seller, products and orders, and observes their permanent
 * effects through primary SQL without repairing state. Workload (producer) orders are never examined.
 */
public final class KindSaga {
    /** Creation-key prefix of the gate fixture; later runs send the same keys and payloads and recover the same IDs. */
    static final String GATE = "kind-saga-gate-v2";
    /** Prefix of the step 4.9 gate fixture. The retained lab keeps it with an unresolved country; no run creates it. */
    static final String RETAINED_GATE = "kind-saga-gate";
    private static final int GATE_STOCK = 1_000_000;
    private final KindLab lab;
    private final KindDeployment deployment;

    public KindSaga() { this(new KindLab()); }
    KindSaga(KindLab lab) { this.lab = lab; deployment = new KindDeployment(lab); }

    public void deployed() throws IOException, InterruptedException {
        var generators = new ArrayList<>(deployment.readyProducts());
        for (String application : List.of("order", "order-saga", "ledger", "order-producer")) {
            String name = "shardshop-" + application;
            JsonNode resource = deployment.resource("deployment", name);
            int count = application.equals("order") ? 2 : 1;
            deploymentShape(application, resource);
            require(resource.path("status").path("readyReplicas").asInt() == count
                    && resource.path("status").path("updatedReplicas").asInt() == count
                    && resource.path("status").path("observedGeneration").asLong(-1)
                    >= resource.path("metadata").path("generation").asLong(), name + " needs " + count + " ready current replicas");
            List<JsonNode> pods = readyPods(application);
            require(pods.size() == count, name + " has an unexpected ready pod count");
            for (JsonNode pod : pods) {
                require(pod.path("status").path("phase").asText().equals("Running"), name + " is not running");
                if (application.equals("order-producer")) KindDeployment.httpOnly(pod.path("spec"));
                if (application.equals("order") || application.equals("order-saga")) generators.add(KindDeployment.name(pod));
            }
        }
        JsonNode config = deployment.resource("configmap", "shardshop-snowflake-generators");
        deployment.generators(generators,
                KindDeployment.highWater(config.path("data").path("highWaterMark").asText()));
        lab.await("persisted CDC offsets on every ordering primary", Duration.ofSeconds(120), () -> {
            for (String shard : lab.shards())
                require(number(shard, "SELECT count(*) FROM ordering.debezium_offset_storage WHERE offset_val IS NOT NULL") > 0,
                        "Order CDC has no durable offset on " + shard);
            return true;
        });
    }

    /**
     * Each run adds one USD and one EUR order, and both must be confirmed by the deployed seven-currency ledger
     * policy. Each order uses one unit of the gate's own stock. The gate changes no deployment or workload; the
     * explicit saga drill owns the scoped ledger rejection.
     */
    public void ownOrders() throws IOException, InterruptedException {
        deployed();
        normalPolicy();
        try (Tunnel product = new Tunnel(lab, "service/shardshop-product", 8080);
             Tunnel order = new Tunnel(lab, "service/shardshop-order", 8080)) {
            Fixture fixture = fixture(product, order);
            String run = GATE + "-" + UUID.randomUUID();
            String usd = submit(order, fixture, run, 0, "USD");
            String eur = submit(order, fixture, run, 1, "EUR");
            awaitOrder(order, fixture, usd, "CONFIRMED", "");
            awaitOrder(order, fixture, eur, "CONFIRMED", "");
        }
        invariants();
    }

    /** The deployed ledger allowlist, from its ConfigMap or an environment override, is every supported currency. */
    void normalPolicy() throws IOException, InterruptedException {
        JsonNode resource = deployment.resource("deployment", "shardshop-ledger");
        String configured = deployment.resource("configmap", "shardshop-ledger-config").path("data")
                .path("application.properties").asText().lines()
                .filter(line -> line.startsWith("shardshop.ledger.allowed-currencies="))
                .map(line -> line.substring(line.indexOf('=') + 1)).findFirst().orElse("");
        for (JsonNode variable : resource.at("/spec/template/spec/containers/0/env"))
            if (variable.path("name").asText().equals("SHARDSHOP_LEDGER_ALLOWED_CURRENCIES")) configured = variable.path("value").asText();
        require(Set.copyOf(List.of(configured.split(","))).equals(Set.copyOf(CurrencyPolicy.SUPPORTED_CURRENCIES)),
                "Normal ledger policy must allow every supported currency");
    }

    /** Separate policy-valid US/USD and EU/EUR sellers with an ASIA buyer. */
    record Fixture(String seller, String euroSeller, String usd, String eur, String buyer, String home) { }

    private Fixture fixture(Tunnel product, Tunnel order) throws IOException, InterruptedException {
        String seller = id(created(product, "/api/v1/sellers", GATE + ".seller.US",
                Json.newObject().put("companyName", "Kind saga gate US").put("region", "US").put("countryCode", "US")).path("sellerId").asText());
        String euroSeller = id(created(product, "/api/v1/sellers", GATE + ".seller.EU",
                Json.newObject().put("companyName", "Kind saga gate EU").put("region", "EU").put("countryCode", "DE")).path("sellerId").asText());
        String usd = id(created(product, "/api/v1/sellers/" + seller + "/products", GATE + ".product.USD", gateProduct("USD")).path("productId").asText());
        String eur = id(created(product, "/api/v1/sellers/" + euroSeller + "/products", GATE + ".product.EUR", gateProduct("EUR")).path("productId").asText());
        String buyer = id(created(order, "/api/v1/buyers", GATE + ".buyer", gateBuyer()).path("buyerId").asText());
        for (String shard : lab.shards())
            if (number(shard, "SELECT count(*) FROM ordering.buyers WHERE buyer_id=" + buyer) == 1)
                return new Fixture(seller, euroSeller, usd, eur, buyer, shard);
        throw new IOException("The gate buyer is on no inventory shard");
    }

    static ObjectNode gateProduct(String currency) {
        return Json.newObject().put("name", "Kind saga gate " + currency + " item")
                .put("description", "Gate-owned product for scoped deployment acceptance orders")
                .put("price", "19.95").put("unitCost", "12.50").put("currency", currency).put("initialStock", GATE_STOCK);
    }

    static ObjectNode gateBuyer() {
        ObjectNode buyer = Json.newObject().put("firstName", "Kind").put("surname", "Gate")
                .put("email", "kind-saga-gate@example.com").put("phone", "+12025550123").put("region", "ASIA");
        buyer.putObject("address").put("line1", "1 Example Road").put("city", "Example City")
                .put("postalCode", "00001").put("countryCode", "US");
        return buyer;
    }

    private static JsonNode created(Tunnel api, String path, String key, ObjectNode payload) throws IOException, InterruptedException {
        // 201 creates the entity in the first gate run; later runs send an identical retry and get 200.
        return api.call("POST", path, key, payload, Set.of(201, 200));
    }

    private static String submit(Tunnel order, Fixture fixture, String run, int ordinal, String currency)
            throws IOException, InterruptedException {
        String id = id(order.call("POST", "/api/v1/buyers/" + fixture.buyer() + "/order-allocations", null,
                Json.newObject().put("runName", run).put("requestOrdinal", Integer.toString(ordinal)), Set.of(201))
                .path("orderId").asText());
        ObjectNode request = Json.newObject().put("currency", currency);
        request.putArray("items").addObject().put("sellerId", currency.equals("USD") ? fixture.seller() : fixture.euroSeller())
                .put("productId", currency.equals("USD") ? fixture.usd() : fixture.eur()).put("quantity", 1);
        order.call("PUT", orderPath(fixture, id), null, request, Set.of(202));
        return id;
    }

    private static String orderPath(Fixture fixture, String orderId) {
        return "/api/v1/buyers/" + fixture.buyer() + "/orders/" + orderId;
    }

    private void awaitOrder(Tunnel order, Fixture fixture, String orderId, String status, String reason)
            throws IOException, InterruptedException {
        lab.await("gate order " + orderId + " reaches " + status + " with complete effects", Duration.ofSeconds(120), () -> {
            JsonNode state = KindLab.parse(sql(fixture.home(), "SELECT row_to_json(x)::text FROM (SELECT o.status, "
                    + "coalesce(s.cancellation_reason, '') AS reason FROM ordering.orders o JOIN ordering.order_sagas s "
                    + "USING(order_id) WHERE o.order_id=" + orderId + ") x"));
            require(state.path("status").asText().equals(status), "Gate order has not reached " + status);
            require(state.path("reason").asText().equals(reason), "Gate order has cancellation reason '"
                    + state.path("reason").asText() + "', not '" + reason + "'");
            verifyOrder(fixture.home(), orderId, status);
            require(order.call("GET", orderPath(fixture, orderId), null, null, Set.of(200)).path("status").asText().equals(status),
                    "Order HTTP status differs from permanent SQL status");
            return true;
        });
    }

    static void deploymentShape(String application, JsonNode resource) throws IOException {
        int count = application.equals("order") ? 2 : 1;
        require(resource.path("spec").path("replicas").asInt() == count, application + " needs " + count + " replicas");
        if (application.equals("order") || application.equals("order-saga")) {
            String mode = application.equals("order") ? "http" : "saga";
            String strategy = application.equals("order") ? "RollingUpdate" : "Recreate";
            require(resource.at("/spec/strategy/type").asText().equals(strategy), application + " needs " + strategy);
            String configured = "";
            for (JsonNode variable : resource.at("/spec/template/spec/containers/0/env"))
                if (variable.path("name").asText().equals("SHARDSHOP_ORDER_PROCESS")) configured = variable.path("value").asText();
            require(configured.equals(mode), application + " must select the " + mode + " process");
        }
    }

    List<JsonNode> readyPods(String application) throws IOException, InterruptedException {
        return deployment.pods("app.kubernetes.io/name=shardshop-" + application).stream()
                .filter(KindDeployment::ready).toList();
    }

    JsonNode pod(String application) throws IOException, InterruptedException {
        List<JsonNode> pods = readyPods(application);
        require(pods.size() == 1, "Expected one ready " + application + " pod");
        return pods.getFirst();
    }

    void verifyOrder(String home, String orderId, String status) throws IOException, InterruptedException {
        orderId = id(orderId);
        boolean confirmed = status.equals("CONFIRMED");
        JsonNode order = KindLab.parse(sql(home, "SELECT row_to_json(x)::text FROM (SELECT o.currency, o.total_amount::text, "
                + "s.status, s.cancellation_reason, s.stock_released_at IS NOT NULL AS released "
                + "FROM ordering.orders o JOIN ordering.order_sagas s USING(order_id) WHERE o.order_id=" + orderId + ") x"));
        require(order.path("status").asText().equals(status), "Order and saga terminal states must agree");
        require(confirmed || order.path("released").asBoolean(), "Cancelled order has not released stock");
        JsonNode ledger = KindLab.parse(sql("ledger-db", "SELECT json_build_object('decisions', count(*), 'entries', "
                + "(SELECT count(*) FROM ledger.ledger_entries WHERE order_id=" + orderId + "), 'outcome', min(outcome), "
                + "'currency', min(currency), 'amount', min(amount)::text) FROM ledger.ledger_operations WHERE order_id=" + orderId));
        boolean stockFailure = order.path("cancellation_reason").asText().equals("OUT_OF_STOCK");
        require(ledger.path("decisions").asInt() == (stockFailure ? 0 : 1)
                && ledger.path("entries").asInt() == (confirmed ? 1 : 0), "Terminal order has incorrect permanent ledger cardinality");
        if (!stockFailure) require(ledger.path("outcome").asText().equals(confirmed ? "RECORDED" : "REJECTED")
                && ledger.path("currency").equals(order.path("currency"))
                && decimal(ledger, "amount").compareTo(decimal(order, "total_amount")) == 0,
                "Ledger decision differs from the accepted order");
        JsonNode items = KindLab.parse(sql(home, "SELECT coalesce(json_agg(x),'[]'::json)::text FROM (SELECT product_id::text, "
                + "seller_id::text, quantity, currency, ((unit_price-unit_cost)*quantity)::text AS margin "
                + "FROM ordering.order_items WHERE order_id=" + orderId + ") x"));
        require(!items.isEmpty(), "Terminal order has no immutable items");
        for (JsonNode item : items) {
            int reservations = 0;
            int credits = 0;
            for (String shard : lab.shards()) {
                JsonNode rows = KindLab.parse(sql(shard, "SELECT json_build_object('reservations', "
                        + "(SELECT coalesce(json_agg(x),'[]'::json) FROM (SELECT quantity, released_at IS NOT NULL AS released FROM catalog.stock_reservations "
                        + "WHERE order_id=" + orderId + " AND product_id=" + id(item.path("product_id").asText()) + ") x), "
                        + "'credits', (SELECT coalesce(json_agg(x),'[]'::json) FROM (SELECT seller_id::text, currency, amount::text FROM catalog.profit_credits "
                        + "WHERE order_id=" + orderId + " AND product_id=" + id(item.path("product_id").asText()) + ") x))"));
                for (JsonNode reservation : rows.path("reservations")) {
                    reservations++;
                    require(reservation.path("quantity").equals(item.path("quantity"))
                            && reservation.path("released").asBoolean() != confirmed, "Reservation quantity or release state differs from terminal order");
                }
                for (JsonNode credit : rows.path("credits")) {
                    credits++;
                    require(credit.path("seller_id").equals(item.path("seller_id")) && credit.path("currency").equals(item.path("currency"))
                            && decimal(credit, "amount").compareTo(decimal(item, "margin")) == 0, "Seller credit differs from immutable item margin");
                }
            }
            require((stockFailure || reservations == 1) && reservations <= 1
                    && credits == (confirmed ? 1 : 0), "Terminal order duplicated or omitted a reservation/profit credit");
        }
        require(number(home, "SELECT count(*) FROM ordering.profit_intents WHERE order_id=" + orderId
                + (confirmed ? " AND applied_at IS NOT NULL AND blocked_reason IS NULL" : "")) == (confirmed ? items.size() : 0),
                "Terminal order has incorrect applied profit intents");
    }

    void invariants() throws IOException, InterruptedException {
        for (String shard : lab.shards()) {
            require(number(shard, stockViolations()) == 0, "Stock differs from initial stock minus active reservations on " + shard);
            require(number(shard, profitViolations()) == 0, "Seller balance differs from permanent currency credits on " + shard);
        }
    }

    static String stockViolations() {
        return "SELECT count(*) FROM catalog.products p WHERE p.stock<0 OR p.stock<>p.initial_stock-"
                + "coalesce((SELECT sum(r.quantity) FROM catalog.stock_reservations r WHERE r.product_id=p.product_id AND r.released_at IS NULL),0)";
    }

    static String profitViolations() {
        return "SELECT count(*) FROM catalog.seller_profits p FULL JOIN (SELECT seller_id,currency,sum(amount) AS amount "
                + "FROM catalog.profit_credits GROUP BY seller_id,currency) c USING(seller_id,currency) "
                + "WHERE coalesce(p.amount,0)<>coalesce(c.amount,0)";
    }

    long number(String shard, String query) throws IOException, InterruptedException {
        String value = sql(shard, query).strip();
        require(value.matches("[0-9]+"), "Invalid SQL count observation");
        return Long.parseLong(value);
    }

    String sql(String cluster, String query) throws IOException, InterruptedException {
        String primary = deployment.resource("cluster", cluster).path("status").path("currentPrimary").asText();
        require(!primary.isBlank(), "No current primary for " + cluster);
        return lab.kubectl("exec", primary, "-c", "postgres", "--", "env", "PGOPTIONS=-c statement_timeout=5000 -c lock_timeout=5000",
                "psql", "-X", "-qAt", "-U", "postgres", "-d", cluster.equals("ledger-db") ? "ledger" : "shardshop",
                "-v", "ON_ERROR_STOP=1", "-c", query);
    }

    static String id(String value) throws IOException {
        require(value.matches("[1-9][0-9]{0,18}"), "Noncanonical saga observation ID");
        try { Long.parseLong(value); } catch (NumberFormatException failure) { throw new IOException("Saga ID exceeds BIGINT", failure); }
        return value;
    }

    static String literal(String value) { return "'" + value.replace("'", "''") + "'"; }
    private static BigDecimal decimal(JsonNode node, String field) throws IOException {
        try { return new BigDecimal(node.path(field).asText()); }
        catch (NumberFormatException failure) { throw new IOException("Missing or invalid saga monetary observation", failure); }
    }

    /** Bounded local port forward for the scoped saga HTTP calls of the gate and the drills. */
    static final class Tunnel implements AutoCloseable {
        private final Process process;
        final int port;

        Tunnel(KindLab lab, String resource, int remote) throws IOException, InterruptedException {
            try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { port = socket.getLocalPort(); }
            process = new ProcessBuilder("kubectl", "--context", "kind-" + lab.clusterName(), "-n", "shardshop", "port-forward",
                    "--address=127.0.0.1", resource, port + ":" + remote).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try {
                lab.await("local tunnel for scoped saga HTTP", Duration.ofSeconds(20), () -> {
                    require(process.isAlive(), "Saga tunnel exited");
                    try (Socket socket = new Socket()) { socket.connect(new InetSocketAddress("127.0.0.1", port), 500); return true; }
                });
            } catch (IOException | InterruptedException failure) { close(); throw failure; }
        }

        /** Send the JSON media type only with a body, and an Idempotency-Key only for a creation. */
        JsonNode call(String method, String path, String key, JsonNode body, Set<Integer> expected)
                throws IOException, InterruptedException {
            try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(3)).build()) {
                HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .timeout(Duration.ofSeconds(10)).method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofByteArray(Json.bytes(body)));
                if (body != null) request.header("Content-Type", "application/json");
                if (key != null) request.header("Idempotency-Key", key);
                HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
                require(expected.contains(response.statusCode()), "Scoped saga HTTP " + method + " expected " + expected
                        + "; received " + response.statusCode());
                return KindLab.parse(response.body());
            }
        }

        @Override public void close() {
            process.destroy();
            try { if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly(); }
            catch (InterruptedException failure) { process.destroyForcibly(); Thread.currentThread().interrupt(); }
        }
    }
}
