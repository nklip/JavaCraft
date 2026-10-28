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

import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in comparison of the packaged production stock worker with disposable real PostgreSQL. */
public final class StockThroughputStepDefinitions {
    private static final int WARMUP_PER_SHARD = 8;
    private static final int TIMED_PER_SHARD = 96;
    private static final int TOTAL_PER_SHARD = WARMUP_PER_SHARD + TIMED_PER_SHARD;
    private static final Duration DEADLINE = Duration.ofSeconds(120);

    @Then("serial and parallel stock workers drain identical warmed PostgreSQL fixtures")
    public void compare() throws Exception {
        var directory = ShardShopFiles.newWorkDirectory("stock-throughput-");
        var report = Json.newObject();
        report.put("methodology", "Same packaged candidate; fresh three-shard databases per arm; local three-item "
                + "orders; 24 warmup orders; 288 timed preaccepted orders; eligibility gate release included in "
                + "drain duration; startup and HTTP acceptance excluded; single PostgreSQL container; balanced arm order 1,3,3,1; no CDC or ledger");
        report.put("recordedAtUtc", java.time.Instant.now().toString());
        report.put("javaVersion", System.getProperty("java.version"));
        report.put("postgresImage", System.getProperty("tc.image.postgresql"));
        report.put("poolSizePerShard", 4);
        report.put("batchSize", 8);
        report.put("pollIntervalMillis", 250);
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
            // Exception text can contain JDBC credentials or request bodies; record only the type.
            report.put("failureType", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            var artifact = directory.resolve("measurement.json");
            Files.write(artifact, Json.bytes(report));
            System.out.println("Stock-stage comparison artifact: " + artifact);
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
            order.configureStockMeasurement(concurrency, 4);
            product.start(ProductRuntime.Endpoints.PRIMARIES);
            order.start(Set.of());
            for (var shard : shards.all()) {
                List<JsonNode> products = products(catalog, shard.region());
                String buyer = buyer(order, shard.region());
                assertEquals(shard, shards.ofSeller(buyer), "Buyer fixture routes to its regional home");
                String currency = products.getFirst().get("currency").asText();
                for (int ordinal = 0; ordinal < TOTAL_PER_SHARD; ordinal++) {
                    String id = accept(order, buyer, ordinal, currency, products);
                    if (ordinal >= WARMUP_PER_SHARD) {
                        sql(shards, shard, "UPDATE ordering.order_sagas SET next_stock_attempt_at="
                                + "clock_timestamp()+INTERVAL '1 hour' WHERE order_id=" + id);
                    }
                }
            }
            order.enableStock();
            order.restart(Set.of());
            awaitCompleted(shards, WARMUP_PER_SHARD);
            for (var shard : shards.all()) {
                assertEquals(TIMED_PER_SHARD, scalar(shards, shard,
                        "SELECT count(*) FROM ordering.order_sagas WHERE status='PENDING_STOCK'"));
            }
            var samples = result.putArray("backlogSamples");
            var baseline = samples.addObject().put("elapsedSeconds", 0.0).putObject("pendingPerShard");
            for (var shard : shards.all()) baseline.put(shard.name(), TIMED_PER_SHARD);
            long started = System.nanoTime();
            for (var shard : shards.all()) {
                sql(shards, shard, "UPDATE ordering.order_sagas SET next_stock_attempt_at=clock_timestamp() "
                        + "WHERE status='PENDING_STOCK'");
            }
            long pending;
            do {
                var sample = samples.addObject();
                var counts = sample.putObject("pendingPerShard");
                pending = 0;
                for (var shard : shards.all()) {
                    long count = scalar(shards, shard,
                            "SELECT count(*) FROM ordering.order_sagas WHERE status='PENDING_STOCK'");
                    counts.put(shard.name(), count);
                    pending += count;
                }
                sample.put("elapsedSeconds", (System.nanoTime() - started) / 1_000_000_000.0);
                assertTrue(System.nanoTime() - started < DEADLINE.toNanos(), "Stock-stage drain exceeds 120s");
                if (pending > 0) Thread.sleep(100); // Bounded condition polling, not a readiness delay.
            } while (pending > 0);
            double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
            result.put("elapsedSeconds", seconds);
            result.put("timedOrders", 3 * TIMED_PER_SHARD);
            result.put("ordersPerSecond", 3 * TIMED_PER_SHARD / seconds);
            order.stop();
            var checked = result.putArray("verifiedShards");
            for (var shard : shards.all()) {
                assertEquals(TOTAL_PER_SHARD, scalar(shards, shard,
                        "SELECT count(*) FROM ordering.order_sagas WHERE status='PENDING_LEDGER'"));
                assertEquals(0, scalar(shards, shard,
                        "SELECT count(*) FROM ordering.order_sagas WHERE stock_failures<>0 OR status<>'PENDING_LEDGER'"));
                assertEquals(TOTAL_PER_SHARD, scalar(shards, shard, "SELECT count(*) FROM ordering.order_outbox"));
                assertEquals(TOTAL_PER_SHARD, scalar(shards, shard, "SELECT count(DISTINCT saga_id) FROM ordering.order_outbox"));
                assertEquals(TOTAL_PER_SHARD, scalar(shards, shard, "SELECT count(*) FROM ordering.order_result_ids"));
                assertEquals(3 * TOTAL_PER_SHARD, scalar(shards, shard, "SELECT count(*) FROM catalog.stock_reservations"));
                assertEquals(0, scalar(shards, shard, "SELECT count(*) FROM catalog.stock_reservations WHERE released_at IS NOT NULL"));
                assertEquals(0, scalar(shards, shard, "SELECT count(*) FROM catalog.products p WHERE stock<0 OR "
                        + "stock<>initial_stock-COALESCE((SELECT sum(quantity) FROM catalog.stock_reservations r "
                        + "WHERE r.product_id=p.product_id AND released_at IS NULL),0)"));
                assertEquals(3, scalar(shards, shard, "SELECT count(*) FROM catalog.products WHERE stock=16"));
                checked.addObject().put("shard", shard.name()).put("completedOrders", TOTAL_PER_SHARD)
                        .put("outboxRows", TOTAL_PER_SHARD).put("reservationRows", 3 * TOTAL_PER_SHARD)
                        .put("stockFailures", 0).put("remainingStockPerProduct", 16);
            }
            product.stop();
            assertEquals(List.of(), order.problems(), "No packaged order JVM warnings or errors");
            assertEquals(List.of(), product.problems(), "No packaged product JVM warnings or errors");
            result.put("outcome", "passed");
            System.out.printf(java.util.Locale.ROOT, "Stock-stage concurrency=%d: %d orders in %.3fs (%.2f/s)%n",
                    concurrency, 3 * TIMED_PER_SHARD, seconds, 3 * TIMED_PER_SHARD / seconds);
        } finally {
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

    private List<JsonNode> products(CatalogApi catalog, String region) throws Exception {
        String key = "stock-throughput-" + region;
        JsonNode seller = response(catalog.createSeller(key, CatalogApi.sellerRequest(key, region)), 201);
        var products = new ArrayList<JsonNode>();
        for (int item = 0; item < 3; item++) {
            products.add(response(catalog.post("/api/v1/sellers/" + seller.get("sellerId").asText() + "/products",
                    "item-" + item, Json.bytes(CatalogApi.itemRequest(region).put("initialStock", 120))), 201));
        }
        return products;
    }

    private String buyer(OrderRuntime order, String region) throws Exception {
        var request = Json.object("{\"firstName\":\"Stock\",\"surname\":\"Fixture\",\"email\":\"stock@example.com\","
                + "\"phone\":\"+12025550123\",\"address\":{\"line1\":\"1 Example Road\",\"city\":\"Example City\","
                + "\"postalCode\":\"00001\",\"countryCode\":\"US\"}}").put("region", region);
        return response(http(order, "POST", "/api/v1/buyers", "buyer-" + region, request), 201).get("buyerId").asText();
    }

    private String accept(OrderRuntime order, String buyer, int ordinal, String currency, List<JsonNode> products)
            throws Exception {
        String base = "/api/v1/buyers/" + buyer;
        String id = response(http(order, "POST", base + "/order-allocations", null,
                Json.newObject().put("runName", "stock-throughput").put("requestOrdinal", Integer.toString(ordinal))),
                201).get("orderId").asText();
        var request = Json.newObject().put("currency", currency);
        var items = request.putArray("items");
        for (var product : products) items.addObject().put("sellerId", product.get("sellerId").asText())
                .put("productId", product.get("productId").asText()).put("quantity", 1);
        assertEquals("PENDING_STOCK", response(http(order, "PUT", base + "/orders/" + id, null, request), 202)
                .get("status").asText());
        return id;
    }

    private void awaitCompleted(CatalogShards shards, int count) throws Exception {
        long started = System.nanoTime();
        while (true) {
            boolean complete = true;
            for (var shard : shards.all()) {
                complete &= scalar(shards, shard, "SELECT count(*) FROM ordering.order_sagas WHERE status='PENDING_LEDGER'") == count;
            }
            if (complete) return;
            assertTrue(System.nanoTime() - started < DEADLINE.toNanos(), "Stock warmup exceeds 120s");
            Thread.sleep(100);
        }
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
