package dev.nklip.javacraft.shardshop.testing.cucumber.step;

import dev.nklip.javacraft.shardshop.testing.cucumber.config.CatalogDatabase;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.PackagedApplication;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.ShardShopFiles;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.CatalogShards;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.OrderRuntime;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.OrderResponseProxy;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.ProductRuntime;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.StoredCatalog;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.WorkloadRuntime;
import io.cucumber.java.After;
import io.cucumber.java.Scenario;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Step 4.8 through the packaged applications and service-owned PostgreSQL schemas. */
public final class OrderProducerStepDefinitions {
    private static final String USD_RUN = "producer-acceptance-usd";
    private static final String EUR_RUN = "producer-acceptance-eur";
    private static final Duration STARTUP_WAIT = Duration.ofMinutes(6);
    private static final Duration ORDER_WAIT = Duration.ofSeconds(45);

    private final CatalogShards shards;
    private final ProductRuntime product;
    private final OrderRuntime order;
    private final WorkloadRuntime workloads;
    private OrderSnapshot usdFirst;
    private OrderSnapshot eurFirst;
    private OrderResponseProxy proxy;
    private OrderResponseProxy.ReplayCalls beforeReplay;
    private PackagedApplication startupCheck;

    private record Item(String sellerId, String productId, int quantity, String currency) {
    }

    private record OrderSnapshot(String id, String buyerId, String fingerprint, String currency, String status,
                                 List<Item> items) {
    }

    @FunctionalInterface
    private interface Check {
        boolean ready() throws Exception;
    }

    public OrderProducerStepDefinitions(CatalogShards shards, ProductRuntime product, OrderRuntime order,
                                        WorkloadRuntime workloads) {
        this.shards = shards;
        this.product = product;
        this.order = order;
        this.workloads = workloads;
    }

    @Given("the order producer has migrated regional primaries and packaged providers")
    public void setup() throws Exception {
        shards.create("shard-a=US, shard-b=EU, shard-c=ASIA, shard-d=US");
        for (CatalogShards.Shard shard : shards.all()) {
            CatalogDatabase.instance().migrate(shard.database(), ShardShopFiles.resolve("database/shard/ordering"),
                    Map.of("shardRegion", shard.region(), "shardIndex", Integer.toString(shards.all().indexOf(shard)),
                            "shardCount", Integer.toString(shards.all().size())), "ordering");
        }
        product.start(ProductRuntime.Endpoints.PRIMARIES);
        order.start(Set.of());
        proxy = new OrderResponseProxy(order.port());
        workloads.producerOrderUrl(proxy.url());
    }

    @After(value = "@order-producer", order = 11000)
    public void stopProviders(Scenario scenario) throws Exception {
        if (workloads.producerStarted() && workloads.producer().isAlive()) {
            workloads.stopProducer();
        }
        order.stop();
        if (proxy != null) {
            proxy.close();
        }
        if (!scenario.isFailed()) {
            assertEquals(List.of(), workloads.producerProblems(), "Producer warnings and errors");
            assertEquals(List.of(), order.problems(), "Order warnings and errors");
        }
    }


    @When("the USD order producer starts before catalog seeding")
    public void startUsd() throws Exception {
        workloads.startProducer(USD_RUN, 0);
    }

    @Then("it waits for the complete catalog without creating buyers or orders")
    public void waitsForCatalog() throws Exception {
        await(STARTUP_WAIT, "Producer did not enter its catalog gate", () ->
                workloads.producer().lines().stream().anyMatch(line ->
                        line.contains("Order producer waiting for the complete catalog")));
        assertTrue(workloads.producer().isAlive(), workloads.producer().tail());
        assertEquals(0, count("ordering.buyers", null));
        assertEquals(0, count("ordering.order_allocations", null));
    }

    @Then("the producer creates all 3000 buyers and submits a USD order with one item from each region")
    public void firstUsd() throws Exception {
        await(STARTUP_WAIT, "Producer did not create all buyers and submit its first order", () -> {
            usdFirst = snapshot(USD_RUN, 0);
            return usdFirst != null;
        });
        assertBuyerDataset();
        assertOrder(usdFirst, "USD");
        assertEquals(1, count("ordering.order_allocations", USD_RUN));
        proxy.assertCommittedResponseRecovery();
        beforeReplay = proxy.replayCalls(USD_RUN, usdFirst.buyerId(), 0, usdFirst.id());
    }

    @When("the producer stops and restarts with the same run name and percentage")
    public void restartUsd() throws Exception {
        assertStopped();
        workloads.startProducer(USD_RUN, 0);
    }

    @Then("its pending first order and allocation are unchanged and no second ordinal was allocated")
    public void replayUsd() throws Exception {
        await(STARTUP_WAIT, "Restart did not finish resolving catalog and buyers", () ->
                workloads.producer().lines().stream().anyMatch(line ->
                        line.contains("Order producer ready: sellers=150 products=300 buyers=3000")));
        await(ORDER_WAIT, "Restart did not replay the first allocation and PUT", () -> {
            OrderResponseProxy.ReplayCalls now = proxy.replayCalls(USD_RUN, usdFirst.buyerId(), 0, usdFirst.id());
            return now.allocation() > beforeReplay.allocation() && now.put() > beforeReplay.put();
        });
        assertEquals(usdFirst, snapshot(USD_RUN, 0), "Replay changed the allocated ID or immutable order payload");
        assertEquals(1, count("ordering.order_allocations", USD_RUN),
                "Producer advanced while the first order was pending");
        assertBuyerDataset();
    }

    @When("the first USD order becomes confirmed")
    public void confirmUsd() throws Exception {
        complete(usdFirst, "CONFIRMED", null);
    }

    @Then("the producer advances to ordinal 1 only after observing the terminal order")
    public void nextUsd() throws Exception {
        await(ORDER_WAIT, "Producer did not advance after CONFIRMED", () -> snapshot(USD_RUN, 1) != null);
        assertEquals("CONFIRMED", snapshot(USD_RUN, 0).status());
        assertEquals(2, count("ordering.order_allocations", USD_RUN));
        assertOrder(snapshot(USD_RUN, 1), "USD");
    }

    @When("the producer stops and a separate EUR run starts")
    public void startEur() throws Exception {
        assertStopped();
        workloads.startProducer(EUR_RUN, 100);
    }

    @Then("it submits a EUR order with one item from each region and reuses the 3000 buyers")
    public void firstEur() throws Exception {
        await(STARTUP_WAIT, "EUR run did not submit its first order", () -> {
            eurFirst = snapshot(EUR_RUN, 0);
            return eurFirst != null;
        });
        assertBuyerDataset();
        assertOrder(eurFirst, "EUR");
        assertEquals(1, count("ordering.order_allocations", EUR_RUN));
    }

    @When("the first EUR order becomes cancelled")
    public void cancelEur() throws Exception {
        complete(eurFirst, "CANCELLED", "UNSUPPORTED_CURRENCY");
    }

    @Then("the EUR run advances to ordinal 1 only after observing the terminal order")
    public void nextEur() throws Exception {
        await(ORDER_WAIT, "EUR run did not advance after CANCELLED", () -> snapshot(EUR_RUN, 1) != null);
        assertEquals("CANCELLED", snapshot(EUR_RUN, 0).status());
        assertEquals(2, count("ordering.order_allocations", EUR_RUN));
        assertOrder(snapshot(EUR_RUN, 1), "EUR");
        assertStopped();
    }

    @When("the order producer checks its startup with a blank run name")
    public void checkBlankRunName() throws Exception {
        startupCheck = workloads.checkProducer(List.of("-Dshardshop.producer.run-name= "));
    }

    /** A failed configuration must not be created again during shutdown, which would log the error twice. */
    @Then("the startup check exits with code {int} and logs one error {string}")
    public void startupCheckFails(int code, String error) throws Exception {
        assertEquals(code, startupCheck.awaitExit(Duration.ofSeconds(60)), startupCheck.tail());
        List<String> errors = startupCheck.lines().stream().filter(line -> line.contains(" ERROR ")).toList();
        assertEquals(1, errors.size(), String.join("\n", errors));
        assertTrue(errors.getFirst().contains(error), errors.getFirst());
    }

    private void assertBuyerDataset() throws SQLException {
        Map<String, Integer> regions = new HashMap<>();
        Set<String> keys = new HashSet<>();
        for (CatalogShards.Shard shard : shards.all()) {
            try (Connection connection = shards.superuser(shard);
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT region, creation_key FROM ordering.buyers");
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String region = rows.getString(1);
                    String key = rows.getString(2);
                    assertTrue(key.matches("buyers-v1\\." + region + "\\.buyer\\.(?:[1-9][0-9]*)"), key);
                    assertTrue(keys.add(key), "Duplicate buyer key " + key);
                    regions.merge(region, 1, Integer::sum);
                }
            }
        }
        assertEquals(Map.of("US", 1000, "EU", 1000, "ASIA", 1000), regions);
        assertEquals(3000, keys.size());
        for (String region : List.of("US", "EU", "ASIA")) {
            for (int ordinal = 1; ordinal <= 1000; ordinal++) {
                assertTrue(keys.contains("buyers-v1." + region + ".buyer." + ordinal),
                        "Missing dataset buyer " + region + "/" + ordinal);
            }
        }
    }

    private void assertOrder(OrderSnapshot snapshot, String currency) throws SQLException {
        assertNotNull(snapshot);
        assertEquals("PENDING_STOCK", snapshot.status());
        assertEquals(currency, snapshot.currency());
        assertEquals(3, snapshot.items().size());
        Map<String, StoredCatalog.Row> products = new HashMap<>();
        for (StoredCatalog.Row row : StoredCatalog.read(shards).values()) {
            if (row.productId() != null) {
                products.put(row.productId(), row);
            }
        }
        Set<String> regions = new HashSet<>();
        for (Item item : snapshot.items()) {
            StoredCatalog.Row product = products.get(item.productId());
            assertNotNull(product, "Order references an unseeded product " + item.productId());
            assertEquals(product.sellerId(), item.sellerId());
            assertEquals(currency, product.currency());
            assertEquals(currency, item.currency());
            assertTrue(item.quantity() >= 1 && item.quantity() <= 3, "Item quantity " + item.quantity());
            regions.add(product.key().split("\\.")[1]);
        }
        assertEquals(Set.of("US", "EU", "ASIA"), regions);
    }

    private OrderSnapshot snapshot(String run, long ordinal) throws SQLException {
        for (CatalogShards.Shard shard : shards.all()) {
            try (Connection connection = shards.superuser(shard);
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT a.order_id, a.buyer_id, o.request_fingerprint, o.currency, o.status "
                                 + "FROM ordering.order_allocations a JOIN ordering.orders o USING (order_id) "
                                 + "WHERE a.run_name=? AND a.request_ordinal=?")) {
                statement.setString(1, run);
                statement.setLong(2, ordinal);
                try (ResultSet rows = statement.executeQuery()) {
                    if (rows.next()) {
                        String id = rows.getString(1);
                        OrderSnapshot result = new OrderSnapshot(id, rows.getString(2), rows.getString(3),
                                rows.getString(4), rows.getString(5), items(connection, id));
                        assertTrue(!rows.next(), "Duplicate order allocation for " + run + "/" + ordinal);
                        return result;
                    }
                }
            }
        }
        return null;
    }

    private static List<Item> items(Connection connection, String orderId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT seller_id, product_id, quantity, currency FROM ordering.order_items "
                        + "WHERE order_id=? ORDER BY item_position")) {
            statement.setLong(1, Long.parseLong(orderId));
            try (ResultSet rows = statement.executeQuery()) {
                java.util.ArrayList<Item> items = new java.util.ArrayList<>();
                while (rows.next()) {
                    items.add(new Item(rows.getString(1), rows.getString(2), rows.getInt(3), rows.getString(4)));
                }
                return List.copyOf(items);
            }
        }
    }

    private void complete(OrderSnapshot snapshot, String status, String reason) throws SQLException {
        CatalogShards.Shard home = shards.ofSeller(snapshot.buyerId());
        try (Connection connection = shards.superuser(home)) {
            connection.setAutoCommit(false);
            try (PreparedStatement orderStatus = connection.prepareStatement(
                    "UPDATE ordering.orders SET status=?, updated_at=CURRENT_TIMESTAMP WHERE order_id=?");
                 PreparedStatement sagaStatus = connection.prepareStatement(
                         "UPDATE ordering.order_sagas SET status=?, cancellation_reason=?, "
                                 + "updated_at=CURRENT_TIMESTAMP WHERE order_id=?")) {
                orderStatus.setString(1, status);
                orderStatus.setLong(2, Long.parseLong(snapshot.id()));
                assertEquals(1, orderStatus.executeUpdate());
                sagaStatus.setString(1, status);
                sagaStatus.setString(2, reason);
                sagaStatus.setLong(3, Long.parseLong(snapshot.id()));
                assertEquals(1, sagaStatus.executeUpdate());
                connection.commit();
            } catch (SQLException | AssertionError failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private int count(String table, String run) throws SQLException {
        int count = 0;
        for (CatalogShards.Shard shard : shards.all()) {
            try (Connection connection = shards.superuser(shard);
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT count(*) FROM " + table + (run == null ? "" : " WHERE run_name=?"))) {
                if (run != null) {
                    statement.setString(1, run);
                }
                try (ResultSet rows = statement.executeQuery()) {
                    rows.next();
                    count += rows.getInt(1);
                }
            }
        }
        return count;
    }

    private void assertStopped() throws InterruptedException {
        int exit = workloads.stopProducer();
        assertTrue(exit == 0 || exit == 143, "Producer SIGTERM exit code " + exit + ":\n" + workloads.producer().tail());
    }

    private void await(Duration limit, String message, Check check) throws Exception {
        long deadline = System.nanoTime() + limit.toNanos();
        while (System.nanoTime() < deadline) {
            if (check.ready()) {
                return;
            }
            PackagedApplication producer = workloads.producer();
            if (!producer.isAlive()) {
                fail(message + "; producer exited:\n" + producer.tail());
            }
            Thread.sleep(100);
        }
        fail(message + ":\n" + workloads.producer().tail());
    }
}
