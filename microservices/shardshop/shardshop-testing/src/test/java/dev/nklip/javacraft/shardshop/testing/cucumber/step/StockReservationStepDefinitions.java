package dev.nklip.javacraft.shardshop.testing.cucumber.step;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.CatalogDatabase;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.Json;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.RawHttp;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.ShardShopFiles;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.CatalogApi;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.CatalogShards;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.Contracts;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.OrderRuntime;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.ProductRuntime;
import io.cucumber.java.After;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Scenario 11 through packaged Java transactions using the real PostgreSQL order_app login. */
public final class StockReservationStepDefinitions {
    private final CatalogShards shards;
    private final ProductRuntime product;
    private final CatalogApi catalog;
    private final OrderRuntime order;
    private OrderRuntime competitor;
    private CatalogApi.Product abundant;
    private CatalogApi.Product european;
    private CatalogApi.Product scarce;
    private String buyer;
    private int ordinal;

    public StockReservationStepDefinitions(CatalogShards shards, ProductRuntime product, CatalogApi catalog,
                                          OrderRuntime order) {
        this.shards = shards;
        this.product = product;
        this.catalog = catalog;
        this.order = order;
    }

    @Given("stock reservation uses migrated regional primaries and packaged providers")
    public void start() throws Exception {
        shards.create("shard-a=US, shard-b=EU, shard-c=ASIA, shard-d=US");
        for (var shard : shards.all()) {
            CatalogDatabase.instance().migrate(shard.database(), ShardShopFiles.resolve("database/shard/ordering"),
                    Map.of("shardRegion", shard.region(), "shardIndex", Integer.toString(shards.all().indexOf(shard)),
                            "shardCount", Integer.toString(shards.all().size())), "ordering");
        }
        product.start(ProductRuntime.Endpoints.PRIMARIES);
        order.start(Set.of());
        abundant = product("abundant", "US", 40, "shard-a");
        european = product("european", "EU", 10, "shard-b");
        scarce = product("scarce", "ASIA", 4, "shard-c");
        var request = Json.object("{\"firstName\":\"Alex\",\"surname\":\"Example\",\"email\":\"alex@example.com\","
                + "\"phone\":\"+12025550123\",\"region\":\"EU\",\"address\":{\"line1\":\"1 Example Road\","
                + "\"city\":\"Example City\",\"postalCode\":\"00001\",\"countryCode\":\"US\"}}");
        buyer = body(http("POST", "/api/v1/buyers", "stock-buyer", request), 201).get("buyerId").asText();
        invariant();
    }

    @After("@stock-reservation")
    public void stop() throws Exception {
        if (competitor != null) competitor.stop();
        order.stop();
        product.stop();
        assertEquals(List.of(), product.problems());
    }

    @Then("cross-region stock reservation creates one immutable RecordOrder")
    public void successful() throws Exception {
        var request = request(2, abundant, european, scarce);
        String id = accept(request);
        order.enableStock();
        order.restart(Set.of());
        await("PENDING_LEDGER", id);
        JsonNode envelope = envelope(id);
        assertEquals("119.70", envelope.at("/snapshot/amount").asText());
        assertEquals("USD", envelope.at("/snapshot/currency").asText());
        assertEquals(3, envelope.at("/snapshot/items").size());
        for (int position = 0; position < 3; position++) {
            var item = envelope.at("/snapshot/items").get(position);
            var fixture = List.of(abundant, european, scarce).get(position);
            assertEquals(position, item.get("itemPosition").asInt());
            assertEquals(fixture.seller().id(), item.get("sellerId").asText());
            assertEquals(fixture.id(), item.get("productId").asText());
            assertEquals(2, item.get("quantity").asInt());
            assertEquals("Verification item", item.get("productName").asText());
            assertEquals("19.95", item.get("unitPrice").asText());
            reservation(fixture, id, 2, false);
        }
        assertStocks(38, 8, 2);
        for (int retry = 0; retry < 4; retry++) {
            assertEquals("PENDING_LEDGER", body(http("PUT", path(id), null, request), 202).get("status").asText());
        }
        order.restart(Set.of());
        await("PENDING_LEDGER", id);
        assertEquals(envelope, envelope(id), "Restart and HTTP retries retain transport and logical identities");
        assertStocks(38, 8, 2);
        invariant();
    }

    @Then("concurrent scarce-stock orders never oversell with competing coordinators")
    public void concurrentOrders() throws Exception {
        int clients = 16;
        var ids = new ArrayList<String>();
        for (int client = 0; client < clients; client++) ids.add(allocate());
        var request = request(1, abundant, scarce);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            var tasks = new ArrayList<java.util.concurrent.Future<RawHttp.Response>>();
            for (String id : ids) tasks.add(executor.submit(() -> {
                start.await();
                return http("PUT", path(id), null, request);
            }));
            start.countDown();
            for (var task : tasks) body(task.get(20, TimeUnit.SECONDS), 202);
        }
        var firstCatalog = shards.ofSeller(abundant.seller().id());
        // Hold the first product until both JVM workers have entered independent reservation transactions.
        // Starting the second JVM without this barrier could let the first finish the entire small dataset.
        try (var lock = shards.superuser(firstCatalog); var statement = lock.createStatement()) {
            lock.setAutoCommit(false);
            try (var rows = statement.executeQuery("SELECT product_id FROM catalog.products WHERE product_id="
                    + abundant.id() + " FOR UPDATE")) {
                assertTrue(rows.next());
            }
            order.enableStock();
            order.restart(Set.of());
            competitor = new OrderRuntime(shards);
            competitor.enableStock();
            competitor.start(Set.of());
            eventually("Both coordinator JVMs compete for catalog stock", () -> scalar(firstCatalog,
                    "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() "
                            + "AND usename='order_app' AND wait_event_type='Lock'") >= 2);
            lock.commit();
        }
        eventually("Every order reserves or cancels and completes compensation", () -> {
            invariant();
            return scalar(home(), "SELECT count(*) FROM ordering.order_sagas WHERE status='PENDING_LEDGER' "
                    + "OR (status='CANCELLED' AND stock_released_at IS NOT NULL)") == clients;
        });
        assertEquals(4, count(home(), "ordering.orders", "status='PENDING_LEDGER'"));
        assertEquals(12, count(home(), "ordering.order_sagas", "status='CANCELLED' AND cancellation_reason='OUT_OF_STOCK'"));
        assertEquals(4, count(home(), "ordering.order_outbox", "true"));
        assertEquals(4, count(home(), "ordering.order_result_ids", "true"));
        assertStocks(36, 10, 0);
        for (String id : ids) {
            String status = status(id).get("status").asText();
            if (status.equals("CANCELLED")) {
                assertEquals("OUT_OF_STOCK", status(id).get("cancellationReason").asText());
                assertEquals(0, count(home(), "ordering.order_outbox", "saga_id IN (SELECT saga_id FROM ordering.order_sagas WHERE order_id=" + id + ")"));
                assertEquals(0, count(shards.ofSeller(abundant.seller().id()), "catalog.stock_reservations",
                        "order_id=" + id + " AND released_at IS NULL"));
            } else {
                reservation(abundant, id, 1, false);
                reservation(scarce, id, 1, false);
                envelope(id);
            }
            body(http("PUT", path(id), null, request), status.equals("CANCELLED") ? 200 : 202);
        }
        assertTrue(count(shards.ofSeller(abundant.seller().id()), "catalog.stock_reservations", "released_at IS NOT NULL") > 0,
                "Rejected orders release reservations committed on earlier catalog shards");
        invariant();
    }

    @Then("a pending stock saga resumes after a catalog outage without reserving twice")
    public void catalogOutage() throws Exception {
        var request = request(1, abundant, scarce);
        String id = accept(request);
        order.enableStock();
        order.restart(Set.of("shard-c"));
        eventually("Earlier catalog shard reservation commits before the unavailable shard", () ->
                count(shards.ofSeller(abundant.seller().id()), "catalog.stock_reservations", "order_id=" + id) == 1
                        && failures(id) > 0);
        assertEquals("PENDING_STOCK", status(id).get("status").asText());
        assertEquals(0, count(home(), "ordering.order_outbox", "true"));
        reservation(abundant, id, 1, false);
        assertStocks(39, 10, 4);
        body(http("PUT", path(id), null, request), 202);
        invariant();
        order.restart(Set.of());
        await("PENDING_LEDGER", id);
        reservation(abundant, id, 1, false);
        reservation(scarce, id, 1, false);
        assertStocks(39, 10, 3);
        envelope(id);
        invariant();
    }

    @Then("a failed Java stock decrement rolls back its reservation insert")
    public void reserveRollback() throws Exception {
        var shard = shards.ofSeller(abundant.seller().id());
        sql(shard, "ALTER TABLE catalog.products ADD CONSTRAINT injected_stock_failure CHECK (product_id<>"
                + abundant.id() + " OR stock=initial_stock) NOT VALID");
        String id = accept(request(1, abundant));
        order.enableStock();
        order.restart(Set.of());
        eventually("The Java stock update reaches the injected CHECK failure", () -> failures(id) > 0);
        assertEquals("PENDING_STOCK", status(id).get("status").asText());
        assertEquals(0, count(shard, "catalog.stock_reservations", "order_id=" + id),
                "Failure after INSERT rolls back the inserted reservation");
        assertStocks(40, 10, 4);
        assertEquals(0, count(home(), "ordering.order_outbox", "true"));
        invariant();
        sql(shard, "ALTER TABLE catalog.products DROP CONSTRAINT injected_stock_failure");
        await("PENDING_LEDGER", id);
        reservation(abundant, id, 1, false);
        assertStocks(39, 10, 4);
        invariant();
    }

    @Then("a restarted coordinator reclaims an expired stock lease exactly once")
    public void leaseTakeover() throws Exception {
        String id = accept(request(1, abundant));
        assertEquals("shard-b", home().name());
        sql(home(), "UPDATE ordering.order_sagas SET stock_lease_version=7, "
                + "stock_lease_until=clock_timestamp()+INTERVAL '1 hour' WHERE order_id=" + id);
        var laterShard = shards.all().get(2);
        assertEquals("shard-c", laterShard.name());
        // The worker polls inventory order. Blocking its claim on c proves it already tried b,
        // where the active durable lease must prevent a claim, without depending on elapsed time.
        try (var barrier = shards.superuser(laterShard); var statement = barrier.createStatement()) {
            barrier.setAutoCommit(false);
            statement.execute("LOCK TABLE ordering.order_sagas IN ACCESS EXCLUSIVE MODE");
            order.enableStock();
            order.restart(Set.of());
            eventually("Restarted worker passes the actively leased saga and reaches the next shard", () ->
                    scalar(laterShard, "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() "
                            + "AND usename='order_app' AND wait_event_type='Lock' AND query LIKE 'WITH candidate AS%'") > 0);
            assertEquals(7, scalar(home(), "SELECT stock_lease_version FROM ordering.order_sagas WHERE order_id=" + id));
            assertEquals("PENDING_STOCK", status(id).get("status").asText());
            assertEquals(0, count(shards.ofSeller(abundant.seller().id()), "catalog.stock_reservations", "order_id=" + id));
            assertEquals(0, count(home(), "ordering.order_outbox", "true"));
            sql(home(), "UPDATE ordering.order_sagas SET stock_lease_until=clock_timestamp()-INTERVAL '1 second' "
                    + "WHERE order_id=" + id);
            barrier.commit();
        }
        await("PENDING_LEDGER", id);
        assertEquals(8, scalar(home(), "SELECT stock_lease_version FROM ordering.order_sagas WHERE order_id=" + id));
        reservation(abundant, id, 1, false);
        envelope(id);
        assertStocks(39, 10, 4);
        invariant();
    }

    @Then("a failed Java stock restoration resumes compensation after restart")
    public void releaseRollback() throws Exception {
        var shard = shards.ofSeller(abundant.seller().id());
        // NOT VALID allows the original row; the decrement passes, but the later restoration fails.
        sql(shard, "ALTER TABLE catalog.products ADD CONSTRAINT injected_stock_failure CHECK (product_id<>"
                + abundant.id() + " OR stock<initial_stock) NOT VALID");
        var request = request(1, abundant, scarce);
        ((ObjectNode) request.withArray("items").get(1)).put("quantity", 5);
        String id = accept(request);
        order.enableStock();
        order.restart(Set.of());
        await("CANCELLED", id);
        eventually("Compensation reaches the injected stock restoration failure", () -> failures(id) > 0);
        assertEquals("OUT_OF_STOCK", status(id).get("cancellationReason").asText());
        reservation(abundant, id, 1, false);
        assertEquals(0, count(shards.ofSeller(scarce.seller().id()), "catalog.stock_reservations", "order_id=" + id));
        assertEquals(0, count(home(), "ordering.order_sagas", "order_id=" + id + " AND stock_released_at IS NOT NULL"));
        assertEquals(0, count(home(), "ordering.order_outbox", "true"));
        assertStocks(39, 10, 4);
        invariant();
        order.stop();
        sql(shard, "ALTER TABLE catalog.products DROP CONSTRAINT injected_stock_failure");
        order.start(Set.of());
        eventually("Restart completes the durable cancellation compensation", () ->
                count(home(), "ordering.order_sagas", "order_id=" + id + " AND stock_released_at IS NOT NULL") == 1);
        reservation(abundant, id, 1, true);
        assertClosedGuards(id, abundant, scarce);
        assertStocks(40, 10, 4);
        body(http("PUT", path(id), null, request), 200);
        order.restart(Set.of());
        body(http("PUT", path(id), null, request), 200);
        reservation(abundant, id, 1, true);
        assertClosedGuards(id, abundant, scarce);
        assertStocks(40, 10, 4);
        invariant();
    }

    @Then("the order runtime login cannot truncate reservation history")
    public void truncateRefused() throws Exception {
        for (var shard : shards.all()) {
            try (var connection = CatalogDatabase.instance().order(shard.database());
                 var statement = connection.createStatement()) {
                var failure = assertThrows(SQLException.class, () -> statement.execute("TRUNCATE catalog.stock_reservations"));
                assertEquals("42501", failure.getSQLState());
            }
        }
        invariant();
    }

    private CatalogApi.Product product(String alias, String region, int stock, String shardName) throws Exception {
        String key = alias;
        for (int candidate = 0; !shards.ofKey(region, key).name().equals(shardName); candidate++) key = alias + candidate;
        var sellerRequest = CatalogApi.sellerRequest(alias + " company", region);
        var sellerResponse = catalog.createSeller(key, sellerRequest);
        body(sellerResponse, 201);
        var seller = catalog.remember(alias, key, sellerRequest, sellerResponse);
        assertEquals(shardName, shards.ofSeller(seller.id()).name());
        var payload = CatalogApi.itemRequest().put("initialStock", stock);
        var response = catalog.post(seller.path() + "/products", alias, Json.bytes(payload));
        body(response, 201);
        return catalog.remember(alias, seller, alias, payload, response);
    }

    private String allocate() throws Exception {
        return body(http("POST", "/api/v1/buyers/" + buyer + "/order-allocations", null,
                Json.newObject().put("runName", "stock-reservation").put("requestOrdinal", Integer.toString(ordinal++))), 201)
                .get("orderId").asText();
    }

    private String accept(ObjectNode request) throws Exception {
        String id = allocate();
        assertEquals("PENDING_STOCK", body(http("PUT", path(id), null, request), 202).get("status").asText());
        return id;
    }

    private static ObjectNode request(int quantity, CatalogApi.Product... fixtures) {
        var request = Json.newObject().put("currency", "USD");
        var items = request.putArray("items");
        for (var fixture : fixtures) items.addObject().put("sellerId", fixture.seller().id())
                .put("productId", fixture.id()).put("quantity", quantity);
        return request;
    }

    private JsonNode status(String id) throws Exception {
        return body(http("GET", path(id), null, null), 200);
    }

    private void await(String state, String id) throws Exception {
        eventually("Order " + id + " reaches " + state, () -> state.equals(status(id).get("status").asText()));
    }

    private JsonNode envelope(String id) throws Exception {
        try (var connection = shards.superuser(home()); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT o.envelope, o.message_id, o.result_message_id, s.saga_id, s.command_id, "
                     + "r.request_fingerprint FROM ordering.order_outbox o JOIN ordering.order_sagas s USING (saga_id) "
                     + "JOIN ordering.orders r USING (order_id) WHERE r.order_id=" + id)) {
            assertTrue(rows.next(), "Exactly one outbox envelope exists for a reserved order");
            var envelope = Json.parse(rows.getString(1).getBytes(StandardCharsets.UTF_8));
            assertEquals(List.of(), Contracts.RECORD_ORDER.errors("#", envelope));
            assertEquals(rows.getString(2), envelope.get("messageId").asText());
            assertEquals(rows.getString(3), envelope.get("resultMessageId").asText());
            assertEquals(rows.getString(4), envelope.get("sagaId").asText());
            assertEquals(rows.getString(5), envelope.get("commandId").asText());
            assertEquals(rows.getString(6), envelope.get("requestFingerprint").asText());
            assertEquals(id, envelope.get("orderId").asText());
            assertEquals(buyer, envelope.get("buyerId").asText());
            assertFalse(rows.next());
            assertEquals(1, count(home(), "ordering.order_result_ids", "saga_id=" + envelope.get("sagaId").asText()));
            return envelope;
        }
    }

    private void reservation(CatalogApi.Product fixture, String id, int quantity, boolean released) throws Exception {
        try (var connection = shards.superuser(shards.ofSeller(fixture.seller().id())); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT quantity, released_at IS NOT NULL FROM catalog.stock_reservations "
                     + "WHERE order_id=" + id + " AND product_id=" + fixture.id())) {
            assertTrue(rows.next());
            assertEquals(quantity, rows.getInt(1));
            assertEquals(released, rows.getBoolean(2));
            assertFalse(rows.next(), "Reservation identity is unique");
        }
    }

    private void assertClosedGuards(String id, CatalogApi.Product... fixtures) throws SQLException {
        for (var fixture : fixtures) {
            assertEquals(1, count(shards.ofSeller(fixture.seller().id()), "catalog.stock_reservation_guards",
                    "order_id=" + id + " AND closed"), "Compensation permanently closes even an unreserved shard");
        }
    }

    private void assertStocks(int first, int second, int third) throws Exception {
        int index = 0;
        int[] expected = {first, second, third};
        for (var fixture : List.of(abundant, european, scarce)) {
            assertEquals(expected[index++], body(catalog.get(fixture.path()), 200).get("stock").asInt());
        }
    }

    /** One SQL snapshot compares both sides, including while other coordinators commit transactions. */
    private void invariant() throws Exception {
        for (var shard : shards.all()) {
            assertEquals(0, scalar(shard, "SELECT count(*) FROM catalog.products p WHERE stock<0 OR stock <> initial_stock - "
                    + "COALESCE((SELECT sum(quantity) FROM catalog.stock_reservations r WHERE r.product_id=p.product_id "
                    + "AND released_at IS NULL),0)"), "Stock equals initial stock minus active reservations on " + shard.name());
        }
    }

    private long failures(String id) throws SQLException {
        return scalar(home(), "SELECT stock_failures FROM ordering.order_sagas WHERE order_id=" + id);
    }

    private CatalogShards.Shard home() {
        return shards.ofSeller(buyer);
    }

    private long count(CatalogShards.Shard shard, String table, String predicate) throws SQLException {
        return scalar(shard, "SELECT count(*) FROM " + table + " WHERE " + predicate);
    }

    private long scalar(CatalogShards.Shard shard, String query) throws SQLException {
        try (var connection = shards.superuser(shard); var statement = connection.createStatement();
             var rows = statement.executeQuery(query)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private void sql(CatalogShards.Shard shard, String sql) throws SQLException {
        try (var connection = shards.superuser(shard); var statement = connection.createStatement()) {
            statement.setQueryTimeout(10);
            statement.execute(sql);
        }
    }

    private String path(String id) {
        return "/api/v1/buyers/" + buyer + "/orders/" + id;
    }

    private RawHttp.Response http(String method, String target, String key, JsonNode payload) throws Exception {
        var headers = new ArrayList<RawHttp.Header>();
        if (key != null) headers.add(new RawHttp.Header("Idempotency-Key", key));
        if (payload != null) headers.add(new RawHttp.Header("Content-Type", "application/json"));
        var response = RawHttp.exchange(order.port(), method, target, headers,
                payload == null ? new byte[0] : Json.bytes(payload),
                payload == null ? RawHttp.Framing.NONE : RawHttp.Framing.CONTENT_LENGTH);
        Contracts.ORDER_API.responseSchema(method, target, response.status()).ifPresent(pointer ->
                assertEquals(List.of(), Contracts.ORDER_API.errors(pointer, Json.parse(response.body()))));
        assertTrue(response.elapsed().compareTo(Duration.ofSeconds(8)) < 0);
        return response;
    }

    private static JsonNode body(RawHttp.Response response, int status) {
        assertEquals(status, response.status(), response::text);
        return Json.parse(response.body());
    }

    @FunctionalInterface
    private interface Condition {
        boolean met() throws Exception;
    }

    private static void eventually(String requirement, Condition condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        do {
            if (condition.met()) return;
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        fail(requirement + " within 45 seconds");
    }
}
