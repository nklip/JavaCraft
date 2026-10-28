package dev.nklip.javacraft.shardshop.testing.cucumber.step;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.CatalogDatabase;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.Json;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.RawHttp;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.ShardShopFiles;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.CatalogApi;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.CatalogShards;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.OrderRuntime;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.ProductRuntime;
import io.cucumber.java.en.Then;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opt-in isolated profit posting with a packaged production saga and disposable real PostgreSQL. */
public final class ProfitThroughputStepDefinitions {
    private static final int WARMUP_ORDERS_PER_SHARD = 8;
    private static final int TIMED_ORDERS_PER_SHARD = 352;
    private static final int TOTAL_ORDERS_PER_SHARD = WARMUP_ORDERS_PER_SHARD + TIMED_ORDERS_PER_SHARD;
    private static final int ITEMS_PER_ORDER = 3;
    private static final int TIMED_INTENTS_PER_SHARD = TIMED_ORDERS_PER_SHARD * ITEMS_PER_ORDER;
    private static final Duration DEADLINE = Duration.ofSeconds(60);
    // Independently fixed product inputs: (19.95 - 12.50) * quantities 1, 2, 3.
    private static final BigDecimal MARGIN_PER_ORDER = new BigDecimal("44.70");

    @Then("serial and parallel profit workers drain identical warmed PostgreSQL fixtures")
    public void compare() throws Exception {
        var directory = ShardShopFiles.newWorkDirectory("profit-throughput-");
        var report = Json.newObject();
        report.put("methodology", "Same packaged candidate; fresh three-shard databases per arm; balanced 1,3,3,1; "
                + "one real API-accepted three-item snapshot per home cloned by explicit fixture SQL into "
                + "confirmed orders and durable profit intents; no actual stock reservation or ledger confirmation; "
                + "24 warmup orders / 72 intents, 1056 timed orders / 3168 intents; gate release included in timing; "
                + "startup, acceptance, SQL preparation, stock, CDC, results and ledger excluded; "
                + "one PostgreSQL container; independent local sellers; replay checks outside timer");
        report.put("recordedAtUtc", java.time.Instant.now().toString());
        report.put("javaVersion", System.getProperty("java.version"));
        report.put("postgresImage", System.getProperty("tc.image.postgresql"));
        report.put("poolSizePerShard", 4);
        report.put("batchSize", 32);
        report.put("pollIntervalMillis", 100);
        report.put("profile", "lab profit override; runtime defaults remain 250ms/batch8");
        report.put("observationIntervalMillis", 100);
        report.put("drainDeadlineSeconds", DEADLINE.toSeconds());
        var arms = report.putArray("arms");
        try {
            int runIndex = 0;
            for (int concurrency : List.of(1, 3, 3, 1)) {
                var result = arms.addObject().put("runIndex", ++runIndex).put("shardConcurrency", concurrency);
                measure(concurrency, result);
            }
            report.put("outcome", "passed");
        } catch (Exception | AssertionError failure) {
            report.put("outcome", "failed");
            // Exception messages may contain SQL connection details or fixture bodies.
            report.put("failureType", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            var artifact = directory.resolve("measurement.json");
            Files.write(artifact, Json.bytes(report));
            System.out.println("Profit-stage comparison artifact: " + artifact);
        }
    }

    private void measure(int concurrency, ObjectNode result) throws Exception {
        var shards = new CatalogShards();
        ProductRuntime product = null;
        OrderRuntime order = null;
        try {
            shards.create("shard-a=US, shard-b=EU, shard-c=ASIA");
            for (var shard : shards.all()) {
                CatalogDatabase.instance().migrate(shard.database(), ShardShopFiles.resolve("database/shard/ordering"),
                        Map.of("shardRegion", shard.region(), "shardIndex", Integer.toString(shards.all().indexOf(shard)),
                                "shardCount", Integer.toString(shards.all().size())), "ordering");
            }
            product = new ProductRuntime(shards);
            var catalog = new CatalogApi(product);
            order = new OrderRuntime(shards);
            order.configureProfitMeasurement(concurrency, 4);
            product.start(ProductRuntime.Endpoints.PRIMARIES);
            order.start(Set.of());
            for (var shard : shards.all()) {
                var products = products(catalog, shard.region());
                String buyer = buyer(order, shard.region());
                assertEquals(shard, shards.ofSeller(buyer), "Buyer fixture routes to its regional home");
                assertEquals(shard, shards.ofSeller(products.getFirst().get("sellerId").asText()),
                        "Each home has an independent local seller");
                String accepted = accept(order, buyer, products);
                prepareConfirmed(shards, shard, accepted);
            }
            product.stop();
            assertEquals(List.of(), product.problems(), "No packaged product JVM warnings or errors");
            order.enableProfit();
            order.restart(Set.of());
            awaitPending(shards, TIMED_INTENTS_PER_SHARD);
            var samples = result.putArray("backlogSamples");
            var baseline = samples.addObject().put("elapsedSeconds", 0.0).putObject("pendingPerShard");
            for (var shard : shards.all()) {
                baseline.put(shard.name(), TIMED_INTENTS_PER_SHARD);
                assertEquals(WARMUP_ORDERS_PER_SHARD * ITEMS_PER_ORDER, scalar(shards, shard,
                        "SELECT count(*) FROM catalog.profit_credits"), "Warmup seller credits are durable");
            }
            long started = System.nanoTime();
            for (var shard : shards.all()) {
                sql(shards, shard, "UPDATE ordering.profit_intents SET next_attempt_at=clock_timestamp() "
                        + "WHERE applied_at IS NULL");
            }
            long pending;
            do {
                var sample = samples.addObject();
                var counts = sample.putObject("pendingPerShard");
                pending = 0;
                for (var shard : shards.all()) {
                    long count = pending(shards, shard);
                    counts.put(shard.name(), count);
                    pending += count;
                }
                sample.put("elapsedSeconds", (System.nanoTime() - started) / 1_000_000_000.0);
                assertTrue(System.nanoTime() - started < DEADLINE.toNanos(), "Profit-stage drain exceeds 60s");
                if (pending > 0) Thread.sleep(100); // Bounded condition polling.
            } while (pending > 0);
            double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
            result.put("elapsedSeconds", seconds);
            result.put("timedIntents", 3 * TIMED_INTENTS_PER_SHARD);
            result.put("timedOrders", 3 * TIMED_ORDERS_PER_SHARD);
            result.put("intentsPerSecond", 3 * TIMED_INTENTS_PER_SHARD / seconds);
            order.stopSaga();
            verify(shards);
            // Model an unknown seller commit: retain the seller credits, retry only one completed order per home.
            for (var shard : shards.all()) {
                sql(shards, shard, "UPDATE ordering.profit_intents SET applied_at=NULL, next_attempt_at=clock_timestamp() "
                        + "WHERE order_id=(SELECT min(order_id) FROM ordering.orders)");
            }
            order.restartSaga();
            awaitPending(shards, 0);
            order.stop();
            verify(shards);
            assertEquals(List.of(), order.problems(), "No packaged order JVM warnings or errors");
            var checked = result.putArray("verifiedShards");
            for (var shard : shards.all()) {
                checked.addObject().put("shard", shard.name()).put("confirmedOrders", TOTAL_ORDERS_PER_SHARD)
                        .put("appliedIntents", TOTAL_ORDERS_PER_SHARD * ITEMS_PER_ORDER)
                        .put("uniqueSellerCredits", TOTAL_ORDERS_PER_SHARD * ITEMS_PER_ORDER)
                        .put("sellerBalance", MARGIN_PER_ORDER.multiply(BigDecimal.valueOf(TOTAL_ORDERS_PER_SHARD)).toPlainString())
                        .put("failures", 0).put("blockedIntents", 0).put("replayedIntents", ITEMS_PER_ORDER);
            }
            result.put("outcome", "passed");
            System.out.printf(java.util.Locale.ROOT, "Profit-stage concurrency=%d: %d intents in %.3fs (%.2f/s)%n",
                    concurrency, 3 * TIMED_INTENTS_PER_SHARD, seconds, 3 * TIMED_INTENTS_PER_SHARD / seconds);
        } catch (Exception | AssertionError failure) {
            result.put("outcome", "failed").put("failureType", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            if (order != null) {
                var logs = result.putArray("orderApplicationLogs");
                order.applicationLogs().forEach(path -> logs.add(path.toString()));
            }
            try {
                if (order != null) order.stop();
            } finally {
                try {
                    if (product != null) product.stop();
                } finally {
                    shards.dispose();
                }
            }
        }
    }

    /**
     * Fixture-only boundary: clone a real accepted immutable snapshot, then synthesize confirmation and its intents.
     * This bypasses the stock/ledger/result pipeline deliberately; it never purports to validate that pipeline.
     * Synthetic positive IDs are disjoint across homes and far below the API-issued Snowflake template IDs.
     */
    private void prepareConfirmed(CatalogShards shards, CatalogShards.Shard shard, String acceptedOrder) throws Exception {
        long template = Long.parseLong(acceptedOrder);
        long base = 1_000_000L + shards.all().indexOf(shard) * 10_000L;
        assertTrue(template > base + 3_000_000, "API identity is disjoint from all synthetic fixture identities");
        String generated = " FROM generate_series(1," + (TOTAL_ORDERS_PER_SHARD - 1) + ") AS g(n) ";
        try (var connection = shards.superuser(shard); var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.executeUpdate("INSERT INTO ordering.order_allocations(order_id,buyer_id,run_name,request_ordinal) "
                    + "SELECT " + base + "+n,buyer_id,'profit-throughput-synthetic',n" + generated
                    + "CROSS JOIN ordering.orders t WHERE t.order_id=" + template);
            statement.executeUpdate("INSERT INTO ordering.orders(order_id,buyer_id,request_fingerprint,currency,total_amount,status) "
                    + "SELECT " + base + "+n,buyer_id,request_fingerprint,currency,total_amount,'CONFIRMED'" + generated
                    + "CROSS JOIN ordering.orders t WHERE t.order_id=" + template);
            statement.executeUpdate("INSERT INTO ordering.order_items(order_id,item_position,seller_id,product_id,quantity,"
                    + "product_name,unit_price,unit_cost,currency) SELECT " + base + "+n,item_position,seller_id,product_id,"
                    + "quantity,product_name,unit_price,unit_cost,currency" + generated
                    + "CROSS JOIN ordering.order_items t WHERE t.order_id=" + template);
            statement.executeUpdate("INSERT INTO ordering.order_sagas(saga_id,order_id,command_id,status) SELECT "
                    + (base + 1_000_000) + "+n," + base + "+n," + (base + 2_000_000) + "+n,'CONFIRMED'" + generated);
            statement.executeUpdate("UPDATE ordering.orders SET status='CONFIRMED' WHERE order_id=" + template);
            statement.executeUpdate("UPDATE ordering.order_sagas SET status='CONFIRMED' WHERE order_id=" + template);
            statement.executeUpdate("INSERT INTO ordering.profit_intents(order_id,item_position,seller_id,product_id,currency,"
                    + "amount,next_attempt_at) SELECT order_id,item_position,seller_id,product_id,currency,"
                    + "quantity*(unit_price-unit_cost),CASE WHEN order_id BETWEEN " + (base + WARMUP_ORDERS_PER_SHARD)
                    + " AND " + (base + TOTAL_ORDERS_PER_SHARD - 1) + " THEN clock_timestamp()+INTERVAL '1 hour' "
                    + "ELSE clock_timestamp() END FROM ordering.order_items");
            connection.commit();
        }
        assertEquals(TOTAL_ORDERS_PER_SHARD * ITEMS_PER_ORDER, pending(shards, shard));
        assertEquals(TIMED_INTENTS_PER_SHARD, scalar(shards, shard,
                "SELECT count(*) FROM ordering.profit_intents WHERE next_attempt_at>clock_timestamp()+INTERVAL '30 minutes'"));
    }

    private void verify(CatalogShards shards) throws Exception {
        for (var shard : shards.all()) {
            assertEquals(TOTAL_ORDERS_PER_SHARD, scalar(shards, shard, "SELECT count(*) FROM ordering.orders WHERE status='CONFIRMED'"));
            assertEquals(TOTAL_ORDERS_PER_SHARD, scalar(shards, shard, "SELECT count(*) FROM ordering.order_sagas WHERE status='CONFIRMED'"));
            assertEquals(TOTAL_ORDERS_PER_SHARD * ITEMS_PER_ORDER, scalar(shards, shard,
                    "SELECT count(*) FROM ordering.profit_intents WHERE applied_at IS NOT NULL AND failures=0 AND blocked_reason IS NULL"));
            assertEquals(0, scalar(shards, shard, "SELECT count(*) FROM ordering.order_items WHERE unit_price<>19.95 "
                    + "OR unit_cost<>12.50 OR unit_cost IS NULL OR quantity<>item_position+1"));
            assertEquals(TOTAL_ORDERS_PER_SHARD * ITEMS_PER_ORDER, scalar(shards, shard,
                    "SELECT count(*) FROM catalog.profit_credits"));
            assertEquals(TOTAL_ORDERS_PER_SHARD * ITEMS_PER_ORDER, scalar(shards, shard,
                    "SELECT count(*) FROM ordering.profit_intents i JOIN catalog.profit_credits c "
                    + "ON c.order_id=i.order_id AND c.product_id=i.product_id AND c.seller_id=i.seller_id "
                    + "AND c.currency=i.currency WHERE c.amount=7.45*(i.item_position+1) AND i.amount=c.amount"));
            assertEquals(1, scalar(shards, shard, "SELECT count(*) FROM catalog.seller_profits WHERE amount="
                    + MARGIN_PER_ORDER.multiply(BigDecimal.valueOf(TOTAL_ORDERS_PER_SHARD)).toPlainString()));
            assertEquals(0, scalar(shards, shard, "SELECT count(*) FROM catalog.seller_profits WHERE amount<>0 AND amount<>"
                    + MARGIN_PER_ORDER.multiply(BigDecimal.valueOf(TOTAL_ORDERS_PER_SHARD)).toPlainString()));
            for (String table : List.of("ordering.order_outbox", "ordering.order_inbox", "ordering.order_result_ids",
                    "catalog.stock_reservations")) {
                assertEquals(0, scalar(shards, shard, "SELECT count(*) FROM " + table), "No excluded pipeline work: " + table);
            }
            assertEquals(0, scalar(shards, shard, "SELECT count(*) FROM catalog.products WHERE stock<>initial_stock"));
        }
    }

    private List<JsonNode> products(CatalogApi catalog, String region) throws Exception {
        String key = "profit-throughput-" + region;
        JsonNode seller = response(catalog.createSeller(key, CatalogApi.sellerRequest(key, region)), 201);
        var products = new ArrayList<JsonNode>();
        for (int item = 0; item < ITEMS_PER_ORDER; item++) {
            products.add(response(catalog.post("/api/v1/sellers/" + seller.get("sellerId").asText() + "/products",
                    "item-" + item, Json.bytes(CatalogApi.itemRequest(region).put("initialStock", 2000))), 201));
        }
        return products;
    }

    private String buyer(OrderRuntime order, String region) throws Exception {
        var request = Json.object("{\"firstName\":\"Profit\",\"surname\":\"Fixture\",\"email\":\"profit@example.com\","
                + "\"phone\":\"+12025550123\",\"address\":{\"line1\":\"1 Example Road\",\"city\":\"Example City\","
                + "\"postalCode\":\"00001\",\"countryCode\":\"US\"}}").put("region", region);
        return response(http(order, "POST", "/api/v1/buyers", "buyer-" + region, request), 201).get("buyerId").asText();
    }

    private String accept(OrderRuntime order, String buyer, List<JsonNode> products) throws Exception {
        String base = "/api/v1/buyers/" + buyer;
        String id = response(http(order, "POST", base + "/order-allocations", null,
                Json.newObject().put("runName", "profit-throughput").put("requestOrdinal", "0")), 201)
                .get("orderId").asText();
        var request = Json.newObject().put("currency", products.getFirst().get("currency").asText());
        var items = request.putArray("items");
        for (int position = 0; position < products.size(); position++) {
            var product = products.get(position);
            items.addObject().put("sellerId", product.get("sellerId").asText())
                    .put("productId", product.get("productId").asText()).put("quantity", position + 1);
        }
        assertEquals("PENDING_STOCK", response(http(order, "PUT", base + "/orders/" + id, null, request), 202)
                .get("status").asText());
        return id;
    }

    private void awaitPending(CatalogShards shards, int expected) throws Exception {
        long started = System.nanoTime();
        while (true) {
            boolean complete = true;
            for (var shard : shards.all()) complete &= pending(shards, shard) == expected;
            if (complete) return;
            assertTrue(System.nanoTime() - started < DEADLINE.toNanos(), "Profit warmup/replay exceeds 60s");
            Thread.sleep(100);
        }
    }

    private long pending(CatalogShards shards, CatalogShards.Shard shard) throws Exception {
        return scalar(shards, shard, "SELECT count(*) FROM ordering.profit_intents WHERE applied_at IS NULL");
    }

    private RawHttp.Response http(OrderRuntime order, String method, String path, String key, ObjectNode payload)
            throws Exception {
        var headers = new ArrayList<RawHttp.Header>();
        headers.add(new RawHttp.Header("Content-Type", "application/json"));
        if (key != null) headers.add(new RawHttp.Header("Idempotency-Key", key));
        return RawHttp.exchange(order.port(), method, path, headers, Json.bytes(payload), RawHttp.Framing.CONTENT_LENGTH);
    }

    private JsonNode response(RawHttp.Response response, int status) {
        assertEquals(status, response.status(), "Fixture HTTP response status");
        return Json.parse(response.body());
    }

    private void sql(CatalogShards shards, CatalogShards.Shard shard, String sql) throws Exception {
        try (var connection = shards.superuser(shard); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private long scalar(CatalogShards shards, CatalogShards.Shard shard, String sql) throws Exception {
        try (var connection = shards.superuser(shard); var statement = connection.createStatement();
             var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }
}
