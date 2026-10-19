package dev.nklip.javacraft.shardshop.testing.cucumber.step;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.CatalogDatabase;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.CatalogRouting;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.*;

/** Scenario 9 through real HTTP, owner migrations and runtime-role PostgreSQL transactions. */
public final class OrderApiStepDefinitions {
    private final CatalogShards shards;
    private final ProductRuntime product;
    private final CatalogApi catalog;
    private final OrderRuntime order;
    private String buyer;
    private String orderId;
    private String path;
    private ObjectNode request;
    private CatalogApi.Product usd;
    private CatalogApi.Product eur;
    private CatalogApi.Product asian;

    public OrderApiStepDefinitions(CatalogShards shards, ProductRuntime product, CatalogApi catalog, OrderRuntime order) {
        this.shards = shards;
        this.product = product;
        this.catalog = catalog;
        this.order = order;
    }

    @Given("order acceptance uses migrated regional primaries and the packaged providers")
    public void start() throws Exception {
        shards.create("shard-a=US, shard-b=EU, shard-c=ASIA, shard-d=US");
        for (var shard : shards.all()) {
            CatalogDatabase.instance().migrate(shard.database(), ShardShopFiles.resolve("database/shard/ordering"),
                    placeholders(shard), "ordering");
        }
        product.start(ProductRuntime.Endpoints.PRIMARIES);
        order.start(Set.of());
        usd = product("usd", "EU", "USD");
        eur = product("eur", "ASIA", "EUR");
        asian = product("asian", "ASIA", "USD");
        buyer = createdBuyer("buyer", "US");
        orderId = allocation("initial", 0, 201);
        path = path(buyer, orderId);
        request = orderRequest("USD", usd, asian);
    }

    private Map<String, String> placeholders(CatalogShards.Shard shard) {
        return Map.of("shardRegion", shard.region(), "shardIndex", Integer.toString(shards.all().indexOf(shard)),
                "shardCount", Integer.toString(shards.all().size()));
    }

    @After("@order-api")
    public void stop() throws Exception {
        order.stop();
        product.stop();
        assertEquals(List.of(), order.problems(), "Order must not log PII or noisy request failures");
        assertEquals(List.of(), product.problems());
    }

    @Then("buyer creation and allocation are durable and regional")
    public void durable() throws Exception {
        List<String> ids = new ArrayList<>();
        for (String region : List.of("US", "EU", "ASIA")) {
            var payload = buyerRequest(region);
            var responses = concurrent(6, () -> exchange("POST", "/api/v1/buyers", "regional", payload));
            for (var response : responses) assertTrue(Set.of(200, 201).contains(response.status()), response::text);
            assertEquals(1, responses.stream().filter(r -> r.status() == 201).count());
            String id = Json.parse(responses.getFirst().body()).get("buyerId").asText();
            ids.add(id);
            for (var response : responses) assertEquals(id, Json.parse(response.body()).get("buyerId").asText());
            var expected = payload.deepCopy().put("buyerId", id);
            assertEquals(expected, body(exchange("GET", "/api/v1/buyers/" + id, null, null), 200));
            int index = CatalogRouting.sellerShard(id, shards.all().size());
            assertEquals(region, shards.all().get(index).region());
            List<Integer> eligible = new ArrayList<>();
            for (int i = 0; i < shards.all().size(); i++) {
                if (region.equals(shards.all().get(i).region())) eligible.add(i);
            }
            int keyHome = eligible.get(CatalogRouting.digest("buyer\0" + region + "\0regional")
                    .mod(java.math.BigInteger.valueOf(eligible.size())).intValue());
            assertEquals(keyHome, index, "Buyer key and issued ID have the same permanent shard home");
            for (var shard : shards.all()) {
                assertEquals(shard == shards.all().get(index) ? 1 : 0,
                        count(shard, "ordering.buyers", "buyer_id=" + id));
            }
            error(exchange("POST", "/api/v1/buyers", "regional", payload.deepCopy().put("surname", "Changed")),
                    409, "BUYER_IDEMPOTENCY_CONFLICT");
        }
        assertEquals(3, Set.copyOf(ids).size());
        ObjectNode allocationRequest = Json.object("{\"runName\":\"race\",\"requestOrdinal\":\"9223372036854775807\"}");
        var responses = concurrent(8, () -> exchange("POST", "/api/v1/buyers/" + buyer + "/order-allocations", null,
                allocationRequest));
        for (var response : responses) assertTrue(Set.of(200, 201).contains(response.status()), response::text);
        assertEquals(1, responses.stream().filter(r -> r.status() == 201).count());
        JsonNode allocated = Json.parse(responses.getFirst().body());
        for (var response : responses) assertEquals(allocated, Json.parse(response.body()));
        order.restart(Set.of());
        assertEquals(allocated, body(exchange("POST", "/api/v1/buyers/" + buyer + "/order-allocations", null,
                allocationRequest), 200));
        for (int i = 0; i < ids.size(); i++) {
            assertEquals(ids.get(i), body(exchange("POST", "/api/v1/buyers", "regional",
                    buyerRequest(List.of("US", "EU", "ASIA").get(i))), 200).get("buyerId").asText());
        }
        assertNoAcceptance();
    }

    @Then("concurrent order acceptance stores one complete pending order without changing stock")
    public void atomicAcceptance() throws Exception {
        for (var response : concurrent(8, () -> exchange("PUT", path, null, request))) {
            assertEquals("PENDING_STOCK", body(response, 202).get("status").asText());
            assertEquals(path, response.header("Location").orElseThrow());
        }
        var home = shards.ofSeller(buyer);
        assertEquals(1, count(home, "ordering.orders", "order_id=" + orderId));
        assertEquals(1, count(home, "ordering.order_sagas", "order_id=" + orderId));
        assertEquals(2, count(home, "ordering.order_items", "order_id=" + orderId));
        assertEquals(0, count(home, "ordering.order_outbox", "true"));
        assertEquals(0, count(home, "ordering.order_result_ids", "true"));
        try (var connection = shards.superuser(home); var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT total_amount, request_fingerprint FROM ordering.orders WHERE order_id=" + orderId)) {
                assertTrue(rows.next());
                assertEquals(new BigDecimal("79.80"), rows.getBigDecimal(1));
                String normalized = "{\"buyerId\":\"" + buyer + "\",\"orderId\":\"" + orderId
                        + "\",\"currency\":\"USD\",\"items\":" + request.get("items") + "}";
                assertEquals(String.format("%064x", CatalogRouting.digest(normalized)), rows.getString(2));
            }
            try (var rows = statement.executeQuery("SELECT unit_price, unit_cost, quantity, product_name FROM ordering.order_items WHERE order_id=" + orderId)) {
                int seen = 0;
                while (rows.next()) {
                    assertEquals(new BigDecimal("19.95"), rows.getBigDecimal(1));
                    assertEquals(new BigDecimal("12.50"), rows.getBigDecimal(2));
                    assertEquals(2, rows.getInt(3));
                    assertEquals("Verification item", rows.getString(4));
                    seen++;
                }
                assertEquals(2, seen);
            }
        }
        for (var item : List.of(usd, asian)) {
            assertEquals(2, body(catalog.get(item.path()), 200).get("stock").asInt());
        }
        for (var shard : shards.all()) {
            if (!shard.equals(home)) assertEquals(0, count(shard, "ordering.orders", "true"));
            assertEquals(0, count(shard, "catalog.stock_reservations", "true"));
        }
        // Exact integral JSON representations preserve the contract's normalized fingerprint.
        var normalizedRetry = request.toString().replace("\"quantity\":2", "\"quantity\":2.0");
        assertEquals(202, raw("PUT", path, null, normalizedRetry).status());
    }

    @Then("pending and terminal retries return the saved status without catalog access")
    public void statusRetries() throws Exception {
        order.restart(Set.of("shard-b", "shard-c"));
        assertEquals("PENDING_STOCK", body(exchange("PUT", path, null, request), 202).get("status").asText());
        assertEquals("PENDING_STOCK", body(exchange("GET", path, null, null), 200).get("status").asText());
        error(exchange("PUT", path, null, orderRequest("EUR", usd)), 409, "ORDER_ID_CONFLICT");
        for (String state : List.of("PENDING_LEDGER", "CONFIRMED", "CANCELLED")) {
            try (var connection = shards.superuser(shards.ofSeller(buyer)); var statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE ordering.orders SET status='" + state + "' WHERE order_id=" + orderId);
                statement.executeUpdate("UPDATE ordering.order_sagas SET status='" + state + "', cancellation_reason="
                        + (state.equals("CANCELLED") ? "'UNSUPPORTED_CURRENCY'" : "NULL") + " WHERE order_id=" + orderId);
            }
            JsonNode response = body(exchange("PUT", path, null, request), state.equals("PENDING_LEDGER") ? 202 : 200);
            assertEquals(state, response.get("status").asText());
            if (state.equals("CANCELLED")) assertEquals("UNSUPPORTED_CURRENCY", response.get("cancellationReason").asText());
            assertEquals(response, body(exchange("GET", path, null, null), 200));
        }
        assertEquals(1, count(shards.ofSeller(buyer), "ordering.order_sagas", "true"));
    }

    @Then("order validation errors leave no orders or sagas")
    public void errors() throws Exception {
        error(raw("PUT", path, null, "{\"currency\":\"USD\",\"items\":[]}"), 400, "INVALID_REQUEST");
        error(raw("PUT", path, null, request.toString().replace("\"quantity\":2", "\"quantity\":2.000000000000000000001")), 400, "INVALID_REQUEST");
        error(exchange("PUT", path(buyer, "1"), null, request), 400, "INVALID_REQUEST");
        error(exchange("PUT", path("1", orderId), null, request), 400, "INVALID_REQUEST");
        error(exchange("POST", "/api/v1/buyers/1/order-allocations", null,
                Json.object("{\"runName\":\"missing\",\"requestOrdinal\":\"0\"}")), 422, "BUYER_NOT_FOUND");
        error(exchange("GET", path, null, null), 404, "ORDER_NOT_FOUND");
        error(exchange("PUT", path, null, orderRequest("EUR", usd)), 422, "CURRENCY_MISMATCH");
        for (var mixed : List.of(orderRequest("USD", usd, eur), orderRequest("USD", eur, usd))) {
            error(exchange("PUT", path, null, mixed), 422, "MIXED_CURRENCIES");
        }
        var missing = orderRequest("USD", usd);
        ((ObjectNode) missing.withArray("items").get(0)).put("productId", "1");
        error(exchange("PUT", path, null, missing), 422, "PRODUCT_NOT_FOUND");
        assertNoAcceptance();
        assertEquals(orderId, allocation("initial", 0, 200));
    }

    @Then("catalog unavailability outranks missing products and existing retries bypass it")
    public void outagePrecedence() throws Exception {
        order.restart(Set.of("shard-b"));
        var missingAndUnavailable = orderRequest("USD", usd, asian);
        ((ObjectNode) missingAndUnavailable.withArray("items").get(1)).put("productId", "1");
        error(exchange("PUT", path, null, missingAndUnavailable), 503, "CATALOG_UNAVAILABLE");
        var reversed = orderRequest("USD", asian, usd);
        ((ObjectNode) reversed.withArray("items").get(0)).put("productId", "1");
        error(exchange("PUT", path, null, reversed), 503, "CATALOG_UNAVAILABLE");
        assertNoAcceptance();
        order.restart(Set.of());
        body(exchange("PUT", path, null, request), 202);
        order.restart(Set.of("shard-b", "shard-c"));
        body(exchange("PUT", path, null, request), 202);
        order.restart(Set.of(shards.ofSeller(buyer).name()));
        error(exchange("PUT", path, null, request), 503, "ORDER_STORE_UNAVAILABLE");
        error(raw("PUT", path, null, "{}"), 400, "INVALID_REQUEST");
    }

    @Then("a database failure during acceptance leaves only its durable allocation")
    public void rollback() throws Exception {
        var home = shards.ofSeller(buyer);
        try (var connection = shards.superuser(home); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE ordering.order_sagas ADD CONSTRAINT injected_failure CHECK (command_id < 0) NOT VALID");
        }
        error(exchange("PUT", path, null, request), 503, "ORDER_STORE_UNAVAILABLE");
        assertNoAcceptance();
        try (var connection = shards.superuser(home); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE ordering.order_sagas DROP CONSTRAINT injected_failure");
        }
        body(exchange("PUT", path, null, request), 202);
        assertEquals(1, count(home, "ordering.order_sagas", "true"));
    }

    @Then("concurrent acceptances and status reads on healthy primaries never return 503")
    public void capacity() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int ordinal = 0; ordinal < 8; ordinal++) ids.add(allocation("capacity", ordinal, 201));
        // Each acceptance needs two order phases and two catalog lookups. The one-connection pools of the four
        // shards serve them one at a time; a request waits for a connection instead of failing.
        for (var response : concurrent(ids.size(), client -> () -> exchange("PUT", path(buyer, ids.get(client)), null,
                request))) {
            assertEquals("PENDING_STOCK", body(response, 202).get("status").asText());
        }
        for (var response : concurrent(2 * ids.size(), client -> () -> exchange("GET",
                path(buyer, ids.get(client % ids.size())), null, null))) {
            assertEquals("PENDING_STOCK", body(response, 200).get("status").asText());
        }
        assertEquals(ids.size(), count(shards.ofSeller(buyer), "ordering.order_sagas", "true"));
    }

    @Then("buyer creation, allocation and order acceptance each wait for their advisory lock")
    public void advisoryLocks() throws Exception {
        var europe = shards.all().stream().filter(shard -> shard.region().equals("EU")).findFirst().orElseThrow();
        var created = whileLocked(europe, lockKey("buyer", "EU", "locked"),
                () -> exchange("POST", "/api/v1/buyers", "locked", buyerRequest("EU")));
        assertEquals("EU", body(created, 201).get("region").asText());
        var home = shards.ofSeller(buyer);
        var allocated = whileLocked(home, lockKey("allocation", buyer, "locked-run\0" + 0),
                () -> exchange("POST", "/api/v1/buyers/" + buyer + "/order-allocations", null,
                        Json.object("{\"runName\":\"locked-run\",\"requestOrdinal\":\"0\"}")));
        String id = body(allocated, 201).get("orderId").asText();
        var accepted = whileLocked(home, lockKey("order", "", id), () -> exchange("PUT", path(buyer, id), null, request));
        assertEquals("PENDING_STOCK", body(accepted, 202).get("status").asText());
    }

    @Then("an order lock held past the phase budget gives 503 and writes nothing")
    public void heldLock() throws Exception {
        var home = shards.ofSeller(buyer);
        String id = allocation("held-lock", 0, 201);
        try (Connection lock = shards.superuser(home)) {
            lock.setAutoCommit(false);
            hold(lock, lockKey("order", "", id));
            var response = exchange("PUT", path(buyer, id), null, request);
            error(response, 503, "ORDER_STORE_UNAVAILABLE");
            assertTrue(response.elapsed().compareTo(Duration.ofSeconds(4)) < 0, "Bounded lock acquisition");
            lock.rollback();
        }
        assertEquals(0, count(home, "ordering.orders", "order_id=" + id));
        assertEquals(0, count(home, "ordering.order_sagas", "order_id=" + id));
        assertEquals("PENDING_STOCK", body(exchange("PUT", path(buyer, id), null, request), 202).get("status").asText());
    }

    /** Holds a transaction advisory lock until the request waits for it, then releases the lock. */
    private RawHttp.Response whileLocked(CatalogShards.Shard shard, long key, Callable<RawHttp.Response> request)
            throws Exception {
        try (Connection lock = shards.superuser(shard); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            lock.setAutoCommit(false);
            hold(lock, key);
            var pending = executor.submit(request);
            awaitAdvisoryWait(shard);
            assertFalse(pending.isDone(), "The request must wait for the held lock");
            lock.rollback();
            return pending.get(5, TimeUnit.SECONDS);
        }
    }

    private static void hold(Connection connection, long key) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
            statement.setLong(1, key);
            statement.execute();
        }
    }

    private void awaitAdvisoryWait(CatalogShards.Shard shard) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            try (var connection = shards.superuser(shard); var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() "
                         + "AND usename='order_app' AND wait_event_type='Lock' AND wait_event='advisory'")) {
                assertTrue(rows.next());
                if (rows.getInt(1) > 0) return;
            }
            Thread.sleep(20);
        }
        fail("The request did not wait for its advisory lock");
    }

    /** Computed independently: the signed low 64 bits of SHA-256 over collection, NUL, scope, NUL and key. */
    private static long lockKey(String collection, String scope, String key) {
        return CatalogRouting.digest(collection + "\0" + scope + "\0" + key).longValue();
    }

    @Then("a concurrent {string} wins over a blocked catalog lookup")
    public void finalRecheck(String change) throws Exception {
        var home = shards.ofSeller(buyer);
        try (Connection lock = shards.superuser(shards.ofSeller(usd.seller().id()));
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            lock.setAutoCommit(false);
            try (var statement = lock.createStatement()) {
                statement.execute("LOCK TABLE catalog.products IN ACCESS EXCLUSIVE MODE");
            }
            var blocked = executor.submit(() -> exchange("PUT", path, null, orderRequest("USD", usd)));
            awaitCatalogLock(shards.ofSeller(usd.seller().id()));
            try (var connection = shards.superuser(home); var statement = connection.createStatement()) {
                try (var rows = statement.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND usename='order_app' AND xact_start IS NOT NULL")) {
                    assertTrue(rows.next());
                    assertEquals(0, rows.getInt(1), "No order transaction spans catalog IO");
                }
                try (var rows = statement.executeQuery("SELECT count(*) FROM pg_locks l JOIN pg_stat_activity a ON a.pid=l.pid WHERE a.datname=current_database() AND a.usename='order_app' AND l.locktype='advisory'")) {
                    assertTrue(rows.next());
                    assertEquals(0, rows.getInt(1), "No order advisory lock spans catalog IO");
                }
            }
            // With the order pool limited to one, this proves the order connection was also returned.
            allocation("during-lookup", 0, 201);
            if (change.equals("creation")) {
                body(exchange("PUT", path, null, orderRequest("USD", asian)), 202);
            } else {
                try (var connection = shards.superuser(home); var statement = connection.createStatement()) {
                    statement.executeUpdate("INSERT INTO ordering.orphan_results (message_id,payload_fingerprint,command_id,saga_id,order_id,buyer_id,request_fingerprint,outcome,envelope,reason) VALUES (1,'"
                            + "a".repeat(64) + "',2,3," + orderId + "," + buyer + ",'lost','RECORDED','{}','test quarantine')");
                }
            }
            // Let the lookup time out: final durable state must outrank CATALOG_UNAVAILABLE.
            error(blocked.get(8, TimeUnit.SECONDS), 409,
                    change.equals("creation") ? "ORDER_ID_CONFLICT" : "ORDER_RECONCILIATION_REQUIRED");
            lock.rollback();
        }
        if (change.equals("quarantine")) assertNoAcceptance();
    }

    private void awaitCatalogLock(CatalogShards.Shard shard) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            try (var connection = shards.superuser(shard); var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND usename='order_app' AND wait_event_type='Lock'")) {
                assertTrue(rows.next());
                if (rows.getInt(1) > 0) return;
            }
            Thread.sleep(20);
        }
        fail("The catalog lookup did not reach the held database lock");
    }

    @Then("the ordering migration preserves legacy data and enforces complete new profiles")
    public void migration() throws Exception {
        var database = CatalogDatabase.instance();
        String name = database.createDatabase();
        try {
            Path stream = Files.createTempDirectory(ShardShopFiles.newWorkDirectory("ordering-upgrade-"), "v1-");
            Path full = ShardShopFiles.resolve("database/shard/ordering");
            Files.copy(full.resolve("V1__ordering.sql"), stream.resolve("V1__ordering.sql"));
            Map<String, String> values = Map.of("shardRegion", "US", "shardIndex", "0", "shardCount", "1");
            database.migrate(name, stream, values, "ordering");
            try (var connection = database.superuser(name); var statement = connection.createStatement()) {
                statement.execute("INSERT INTO ordering.buyers (buyer_id,name) VALUES (1,'Historical buyer')");
                statement.execute("INSERT INTO ordering.order_allocations (order_id,buyer_id,run_name,request_ordinal) VALUES (2,1,'historical',0)");
                statement.execute("INSERT INTO ordering.orders (order_id,buyer_id,request_fingerprint,currency,total_amount) VALUES (2,1,'historical','USD',19.95)");
                statement.execute("INSERT INTO ordering.order_items (order_id,item_position,seller_id,product_id,quantity,product_name,unit_price,currency) VALUES (2,0,3,4,1,'Historical product',19.95,'USD')");
                statement.execute("INSERT INTO ordering.order_sagas (saga_id,order_id,command_id) VALUES (5,2,6)");
                statement.execute("INSERT INTO ordering.order_result_ids (result_message_id,message_id,saga_id) VALUES (7,8,5)");
            }
            database.migrate(name, full, values, "ordering");
            assertEquals(0, database.migrate(name, full, values, "ordering").migrationsExecuted);
            try (var connection = database.superuser(name); var statement = connection.createStatement()) {
                try (var rows = statement.executeQuery("SELECT name, creation_key, first_name FROM ordering.buyers WHERE buyer_id=1")) {
                    assertTrue(rows.next());
                    assertEquals("Historical buyer", rows.getString(1));
                    assertNull(rows.getString(2));
                    assertNull(rows.getString(3));
                }
                try (var rows = statement.executeQuery("SELECT unit_price,unit_cost FROM ordering.order_items WHERE order_id=2")) {
                    assertTrue(rows.next());
                    assertEquals(new BigDecimal("19.95"), rows.getBigDecimal(1));
                    assertNull(rows.getBigDecimal(2), "Unknown historical cost must not be invented");
                }
                try (var rows = statement.executeQuery("SELECT status,ledger_result FROM ordering.order_sagas WHERE saga_id=5")) {
                    assertTrue(rows.next());
                    assertEquals("PENDING_STOCK", rows.getString(1));
                    assertNull(rows.getString(2), "Migration must not invent a ledger decision");
                }
                try (var rows = statement.executeQuery("SELECT received_envelope FROM ordering.order_result_ids WHERE result_message_id=7")) {
                    assertTrue(rows.next());
                    assertNull(rows.getString(1), "Migration must not claim a historical result was received");
                }
                try (var rows = statement.executeQuery("SELECT count(*) FROM ordering.profit_intents")) {
                    assertTrue(rows.next());
                    assertEquals(0, rows.getInt(1), "Migration must not credit a historical unknown cost");
                }
                assertThrows(SQLException.class, () -> statement.execute("INSERT INTO ordering.buyers (buyer_id,name,creation_key) VALUES (5,'Incomplete','new-key')"));
            }
        } finally {
            database.dropDatabase(name);
        }
    }

    private CatalogApi.Product product(String alias, String region, String currency) throws Exception {
        var sellerRequest = CatalogApi.sellerRequest(alias + " company", region);
        var sellerResponse = catalog.createSeller(alias, sellerRequest);
        body(sellerResponse, 201);
        var seller = catalog.remember(alias, alias, sellerRequest, sellerResponse);
        var item = CatalogApi.itemRequest().put("currency", currency);
        var response = catalog.post(seller.path() + "/products", alias, Json.bytes(item));
        body(response, 201);
        return catalog.remember(alias, seller, alias, item, response);
    }

    private String createdBuyer(String key, String region) throws Exception {
        return body(exchange("POST", "/api/v1/buyers", key, buyerRequest(region)), 201).get("buyerId").asText();
    }

    private static ObjectNode buyerRequest(String region) {
        return Json.object("{\"firstName\":\"Alex\",\"surname\":\"Example\",\"email\":\"alex@example.com\",\"phone\":\"+12025550123\",\"address\":{\"line1\":\"1 Example Road\",\"city\":\"Example City\",\"postalCode\":\"00001\",\"countryCode\":\"US\"}}")
                .put("region", region);
    }

    private String allocation(String run, long ordinal, int status) throws Exception {
        var payload = Json.newObject().put("runName", run).put("requestOrdinal", Long.toString(ordinal));
        return body(exchange("POST", "/api/v1/buyers/" + buyer + "/order-allocations", null, payload), status)
                .get("orderId").asText();
    }

    private static ObjectNode orderRequest(String currency, CatalogApi.Product... products) {
        var payload = Json.newObject().put("currency", currency);
        var items = payload.putArray("items");
        for (var product : products) {
            items.addObject().put("sellerId", product.seller().id()).put("productId", product.id()).put("quantity", 2);
        }
        return payload;
    }

    private static String path(String buyer, String order) {
        return "/api/v1/buyers/" + buyer + "/orders/" + order;
    }

    private RawHttp.Response exchange(String method, String target, String key, JsonNode payload) throws Exception {
        return raw(method, target, key, payload == null ? null : payload.toString());
    }

    private RawHttp.Response raw(String method, String target, String key, String payload) throws Exception {
        List<RawHttp.Header> headers = new ArrayList<>();
        if (key != null) headers.add(new RawHttp.Header("Idempotency-Key", key));
        if (payload != null) headers.add(new RawHttp.Header("Content-Type", "application/json"));
        var response = RawHttp.exchange(order.port(), method, target, headers,
                payload == null ? new byte[0] : payload.getBytes(StandardCharsets.UTF_8),
                payload == null ? RawHttp.Framing.NONE : RawHttp.Framing.CONTENT_LENGTH);
        var schema = Contracts.ORDER_API.responseSchema(method, target, response.status());
        schema.ifPresent(pointer -> assertEquals(List.of(), Contracts.ORDER_API.errors(pointer, Json.parse(response.body())),
                () -> "Undocumented order response: " + response.status() + " " + response.text()));
        assertTrue(response.elapsed().compareTo(Duration.ofSeconds(8)) < 0, "Bounded HTTP response");
        return response;
    }

    private static JsonNode body(RawHttp.Response response, int status) {
        assertEquals(status, response.status(), response::text);
        return Json.parse(response.body());
    }

    private static void error(RawHttp.Response response, int status, String code) {
        CatalogApi.assertError(response, status, code);
    }

    private long count(CatalogShards.Shard shard, String table, String predicate) throws SQLException {
        try (var connection = shards.superuser(shard); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT count(*) FROM " + table + " WHERE " + predicate)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private void assertNoAcceptance() throws SQLException {
        for (var shard : shards.all()) {
            for (String table : List.of("orders", "order_items", "order_sagas", "order_outbox", "order_result_ids")) {
                assertEquals(0, count(shard, "ordering." + table, "true"), table);
            }
        }
    }

    private static List<RawHttp.Response> concurrent(int clients, Callable<RawHttp.Response> request) throws Exception {
        return concurrent(clients, client -> request);
    }

    /** Releases all clients at once and never retries: healthy primaries must answer without 503. */
    private static List<RawHttp.Response> concurrent(int clients, IntFunction<Callable<RawHttp.Response>> request)
            throws Exception {
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var tasks = new ArrayList<java.util.concurrent.Future<RawHttp.Response>>();
        var start = new CountDownLatch(1);
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        try {
            for (int i = 0; i < clients; i++) {
                Callable<RawHttp.Response> call = request.apply(i);
                tasks.add(executor.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            var responses = new ArrayList<RawHttp.Response>();
            for (var task : tasks) responses.add(task.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
            return responses;
        } finally {
            tasks.forEach(task -> task.cancel(true));
            executor.shutdownNow();
        }
    }
}
