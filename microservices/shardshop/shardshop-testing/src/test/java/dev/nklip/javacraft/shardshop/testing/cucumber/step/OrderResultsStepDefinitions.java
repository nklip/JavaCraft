package dev.nklip.javacraft.shardshop.testing.cucumber.step;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.CatalogDatabase;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.CatalogRouting;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.Json;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.RabbitMqBroker;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.RawHttp;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.ShardShopFiles;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.CatalogApi;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.CatalogShards;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.Contracts;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.CdcRelay;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.LedgerRuntime;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.OrderRuntime;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.ProductBrowser;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.ProductRuntime;
import io.cucumber.java.After;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** The packaged result consumer and profit worker with real broker and regional PostgreSQL primaries. */
public final class OrderResultsStepDefinitions {
    private static final String RESULT_QUEUE = "order.ledger-results";
    private static final String PARKING_QUEUE = "order.ledger-results.dlq";
    private final CatalogDatabase postgres = CatalogDatabase.instance();
    private final CatalogShards shards;
    private final ProductRuntime product;
    private final CatalogApi catalog;
    private final ProductBrowser browser;
    private final OrderRuntime order;
    private final LedgerRuntime ledger;
    private final RabbitMqBroker broker;
    private CatalogApi.Product us;
    private CatalogApi.Product asia;
    private String buyer;
    private String ledgerDatabase;
    private int ordinal;
    private OrderRuntime competitor;
    private OrderRuntime secondCompetitor;

    public OrderResultsStepDefinitions(CatalogShards shards, ProductRuntime product, CatalogApi catalog,
                                       OrderRuntime order, LedgerRuntime ledger, RabbitMqBroker broker,
                                       ProductBrowser browser) {
        this.shards = shards;
        this.product = product;
        this.catalog = catalog;
        this.order = order;
        this.ledger = ledger;
        this.broker = broker;
        this.browser = browser;
    }

    @Given("order results use migrated regional primaries and a broker")
    public void setup() throws Exception {
        shards.create("shard-a=US, shard-b=EU, shard-c=ASIA, shard-d=US");
        for (var shard : shards.all()) {
            postgres.migrate(shard.database(), ShardShopFiles.resolve("database/shard/ordering"),
                    Map.of("shardRegion", shard.region(), "shardIndex", Integer.toString(shards.all().indexOf(shard)),
                            "shardCount", Integer.toString(shards.all().size())), "ordering");
        }
        broker.start();
        product.start(ProductRuntime.Endpoints.PRIMARIES);
        order.start(Set.of());
        us = createProduct("us", "US", "shard-a", "19.95", "12.50");
        asia = createProduct("asia", "US", "shard-d", "4.00", "6.00");
        ObjectNode profile = Json.object("{\"firstName\":\"Alex\",\"surname\":\"Example\","
                + "\"email\":\"alex@example.com\",\"phone\":\"+12025550123\",\"region\":\"EU\","
                + "\"address\":{\"line1\":\"1 Example Road\",\"city\":\"Example City\","
                + "\"postalCode\":\"00001\",\"countryCode\":\"US\"}}");
        buyer = body(http("POST", "/api/v1/buyers", "order-results-buyer", profile), 201)
                .path("buyerId").asText();
        assertEquals("shard-b", home().name());
    }

    @After("@order-results")
    public void cleanup() throws Exception {
        if (secondCompetitor != null) secondCompetitor.stop();
        if (competitor != null) competitor.stop();
        ledger.stop();
        order.stop();
        product.stop();
        if (ledgerDatabase != null) postgres.dropDatabase(ledgerDatabase);
    }

    @Then("HTTP accepts concurrent retries while the saga is stopped and its restart completes each order once")
    public void separateSagaRestart() throws Exception {
        for (var shard : shards.all()) postgres.createOrderPublication(shard.database());
        order.enableCdc(broker);
        startWorkers(true);
        startLedger("USD");
        order.stopSaga();
        var request = request(1, us, asia);
        List<String> ids = new ArrayList<>();
        for (int index = 0; index < 8; index++) ids.add(allocate());
        try (var executor = Executors.newFixedThreadPool(8)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<RawHttp.Response>> writes = new ArrayList<>();
            for (String id : ids) {
                for (int retry = 0; retry < 2; retry++) writes.add(executor.submit(() -> {
                    start.await();
                    return http("PUT", path(id), null, request);
                }));
            }
            start.countDown();
            for (var write : writes) assertEquals("PENDING_STOCK",
                    body(write.get(20, TimeUnit.SECONDS), 202).path("status").asText());
        }
        assertEquals(8, scalar(home(), "SELECT count(*) FROM ordering.orders"));
        assertEquals(8, scalar(home(), "SELECT count(*) FROM ordering.order_sagas"));
        assertEquals(0, scalar(ledgerDatabase, "SELECT count(*) FROM ledger.ledger_operations"),
                "The HTTP process must not run any saga worker");
        order.restartSaga();
        for (String id : ids) {
            awaitStatus(id, "CONFIRMED");
            await("one profit credit for each item", () -> credits(us, id) == 1 && credits(asia, id) == 1);
            assertEquals(1, scalar(ledgerDatabase,
                    "SELECT count(*) FROM ledger.ledger_operations WHERE order_id=" + id));
            assertEquals(1, scalar(ledgerDatabase,
                    "SELECT count(*) FROM ledger.ledger_entries WHERE order_id=" + id));
            assertEquals("CONFIRMED", body(http("PUT", path(id), null, request), 200).path("status").asText());
        }
        for (var fixture : List.of(us, asia)) {
            assertEquals(2, scalar(shards.ofSeller(fixture.seller().id()),
                    "SELECT stock FROM catalog.products WHERE product_id=" + fixture.id()));
        }
        assertEquals(8, scalar(ledgerDatabase, "SELECT count(*) FROM ledger.ledger_operations"));
    }

    @Then("all seven currencies confirm cross-region purchases with exact profits and immutable legacy retries")
    public void regionalCurrencies() throws Exception {
        // Buyer location is deliberately unrelated to seller currency, including US buying Japanese JPY.
        ObjectNode profile = Json.object("{\"firstName\":\"Casey\",\"surname\":\"Currency\",\"email\":\"currency@example.com\","
                + "\"phone\":\"+12025550123\",\"region\":\"US\",\"address\":{\"line1\":\"1 Example Road\","
                + "\"city\":\"Example City\",\"postalCode\":\"00001\",\"countryCode\":\"US\"}}");
        String usBuyer = body(http("POST", "/api/v1/buyers", "currency-us-buyer", profile), 201).path("buyerId").asText();
        List<CatalogApi.Product> fixtures = new java.util.ArrayList<>();
        for (String mapping : List.of("US:US:USD", "EU:DE:EUR", "EU:FR:GBP", "ASIA:CN:CNY", "ASIA:VN:VND", "ASIA:KR:KRW", "ASIA:JP:JPY")) {
            String[] values = mapping.split(":");
            String currency = values[2];
            var sellerRequest = CatalogApi.sellerRequest("Arbitrary currency " + currency, values[0]).put("countryCode", values[1]);
            var sellerResponse = catalog.createSeller("currency-" + currency, sellerRequest);
            body(sellerResponse, 201);
            var seller = catalog.remember(currency, "currency-" + currency, sellerRequest, sellerResponse);
            var payload = CatalogApi.itemRequest().put("currency", currency).put("price", "19.00").put("unitCost", "12.00").put("initialStock", 10);
            var created = catalog.post(seller.path() + "/products", "currency-item", Json.bytes(payload));
            body(created, 201);
            var fixture = catalog.remember(currency, seller, "currency-item", payload, created);
            fixtures.add(fixture);
            if (List.of("JPY", "KRW", "VND").contains(currency)) {
                for (String field : List.of("price", "unitCost")) {
                    var invalid = payload.deepCopy().put(field, "19.01");
                    CatalogApi.assertError(catalog.post(seller.path() + "/products", "invalid-" + field, Json.bytes(invalid)), 400, "INVALID_REQUEST");
                }
            }
            String forbidden = currency.equals("USD") ? "EUR" : "USD";
            CatalogApi.assertError(catalog.post(seller.path() + "/products", "forbidden", Json.bytes(payload.deepCopy().put("currency", forbidden))), 400, "INVALID_REQUEST");
        }
        ObjectNode missingCountry = CatalogApi.sellerRequest("Missing country", "US");
        missingCountry.remove("countryCode");
        for (ObjectNode invalidSeller : List.of(missingCountry,
                CatalogApi.sellerRequest("Wrong country", "US").put("countryCode", "JP"),
                CatalogApi.sellerRequest("Unmapped country", "ASIA").put("countryCode", "SG")))
            CatalogApi.assertError(catalog.createSeller("invalid-country", invalidSeller), 400, "INVALID_REQUEST");
        startWorkers(true);
        startLedger("USD,EUR,GBP,CNY,VND,KRW,JPY");
        for (var fixture : fixtures) {
            buyer = fixture.request().path("currency").asText().equals("USD") ? buyer : usBuyer;
            String id = accept(2, fixture);
            awaitStatus(id, "PENDING_LEDGER");
            ObjectNode saved = command(id);
            assertEquals(2, saved.path("schemaVersion").asInt());
            assertEquals("38.00", saved.at("/snapshot/amount").asText());
            publishCommand(saved);
            awaitStatus(id, "CONFIRMED");
            await("currency profit " + fixture.id(), () -> credits(fixture, id) == 1);
            assertEquals(0, new BigDecimal("14.00").compareTo(credit(fixture, id)));
            assertEquals(0, new BigDecimal("14.00").compareTo(balance(fixture)));
            browser.assertProduct(fixture, 8, Map.of(fixture.request().path("currency").asText(), "14.00"));
            publishCommand(saved);
            assertEquals(200, http("PUT", path(id), null, request(2, fixture)).status());
        }
        // An identical accepted request recovers under saved snapshots after its seller loses country provenance.
        var legacy = fixtures.getLast();
        String id = allocate();
        ObjectNode original = request(1, legacy);
        body(http("PUT", path(id), null, original), 202);
        awaitStatus(id, "PENDING_LEDGER");
        ObjectNode saved = command(id);
        try (Connection connection = shards.superuser(shards.ofSeller(legacy.seller().id())); var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE catalog.sellers SET country_code=NULL WHERE seller_id=" + legacy.seller().id());
        }
        body(http("PUT", path(id), null, original), 202);
        assertEquals(saved, command(id));
        String fresh = allocate();
        CatalogApi.assertError(http("PUT", path(fresh), null, original), 422, "PRODUCT_CURRENCY_POLICY");
        publishCommand(saved);
        awaitStatus(id, "CONFIRMED");
        String mixed = allocate();
        CatalogApi.assertError(http("PUT", path(mixed), null, request(1, fixtures.get(0), fixtures.get(1))), 422, "MIXED_CURRENCIES");
    }

    @Then("a retained version-one JPY snapshot survives country cutover, restart and identical retries")
    public void retainedCurrencyRecovery() throws Exception {
        var sellerRequest = CatalogApi.sellerRequest("Retained Japanese seller", "ASIA").put("countryCode", "JP");
        var sellerResponse = catalog.createSeller("retained-jpy", sellerRequest);
        body(sellerResponse, 201);
        var seller = catalog.remember("retained-jpy", "retained-jpy", sellerRequest, sellerResponse);
        var payload = CatalogApi.itemRequest().put("currency", "JPY").put("price", "19.00").put("unitCost", "12.00").put("initialStock", 10);
        var created = catalog.post(seller.path() + "/products", "retained-item", Json.bytes(payload));
        body(created, 201);
        var fixture = catalog.remember("retained-jpy", seller, "retained-item", payload, created);
        String id = accept(2, fixture);
        ObjectNode original = request(2, fixture);
        // Reproduce the retained database state from the permissive v1 contract before starting any saga worker.
        // The request fingerprint contains IDs/quantity/currency, so saved prices remain independent of retry input.
        try (Connection connection = shards.superuser(home()); var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE ordering.orders SET currency_policy_version=1,total_amount=39.90 WHERE order_id=" + id);
            statement.executeUpdate("UPDATE ordering.order_items SET unit_price=19.95,unit_cost=12.50 WHERE order_id=" + id);
        }
        try (Connection connection = shards.superuser(shards.ofSeller(seller.id())); var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE catalog.sellers SET country_code=NULL WHERE seller_id=" + seller.id());
            statement.executeUpdate("UPDATE catalog.products SET price=19.95,unit_cost=12.50 WHERE product_id=" + fixture.id());
        }
        body(http("PUT", path(id), null, original), 202);
        assertEquals(new BigDecimal("39.90"), decimal(home().database(), "SELECT total_amount FROM ordering.orders WHERE order_id=" + id));
        String fresh = allocate();
        CatalogApi.assertError(http("PUT", path(fresh), null, original), 422, "PRODUCT_CURRENCY_POLICY");
        startWorkers(true);
        awaitStatus(id, "PENDING_LEDGER");
        ObjectNode saved = command(id);
        assertEquals(1, saved.path("schemaVersion").asInt());
        assertEquals("39.90", saved.at("/snapshot/amount").asText());
        assertEquals("JPY", saved.at("/snapshot/currency").asText());
        assertEquals("19.95", saved.at("/snapshot/items/0/unitPrice").asText());
        order.restart(Set.of());
        body(http("PUT", path(id), null, original), 202);
        assertEquals(saved, command(id));
        startLedger("USD,EUR,GBP,CNY,VND,KRW,JPY");
        publishCommand(saved);
        awaitStatus(id, "CONFIRMED");
        await("retained fractional JPY profit", () -> credits(fixture, id) == 1);
        assertEquals(new BigDecimal("14.90"), credit(fixture, id));
        assertEquals(new BigDecimal("14.90"), balance(fixture));
        JsonNode row = browser.page("region=ASIA&limit=1").path("items").get(0);
        assertEquals("19.95", row.path("price").asText());
        assertFalse(row.path("newOrderEligible").asBoolean());
        assertEquals("UNRESOLVED_SELLER_COUNTRY", row.path("legacyReason").asText());
        assertEquals("14.90", row.at("/profitsEarned/JPY").asText());
        publishCommand(saved);
        await("retained ledger duplicate replay", () -> scalar(ledgerDatabase,
                "SELECT count(*) FROM ledger.ledger_outbox WHERE message_id=" + saved.path("resultMessageId").asText()
                        + " AND publication_attempt>=2 AND published_at IS NOT NULL") == 1);
        assertEquals(1, credits(fixture, id));
        assertEquals(new BigDecimal("14.90"), balance(fixture));
        body(http("PUT", path(id), null, original), 200);
        assertEquals(saved, command(id));
    }

    @Then("a packaged ledger recording confirms a cross-shard order and credits its items once")
    public void recordedProfit() throws Exception {
        profitGrants();
        browser.assertProduct(us, 10, Map.of("USD", "0.00"));
        browser.assertProduct(asia, 10, Map.of("USD", "0.00"));
        String id = accept(2, us, asia);
        startWorkers(true);
        awaitStatus(id, "PENDING_LEDGER");
        browser.assertProduct(us, 8, Map.of("USD", "0.00"));
        browser.assertProduct(asia, 8, Map.of("USD", "0.00"));
        ObjectNode command = command(id);
        startLedger("USD");
        publishCommand(command);
        awaitStatus(id, "CONFIRMED");
        await("both catalog credits", () -> credits(us, id) == 1 && credits(asia, id) == 1);
        browser.assertProduct(us, 8, Map.of("USD", "14.90"));
        browser.assertProduct(asia, 8, Map.of("USD", "-4.00"));
        assertEquals(new BigDecimal("14.90"), credit(us, id));
        assertEquals(new BigDecimal("-4.00"), credit(asia, id));
        assertEquals(new BigDecimal("14.90"), balance(us));
        assertEquals(new BigDecimal("-4.00"), balance(asia));
        assertEquals(2, scalar(home(), "SELECT count(*) FROM ordering.profit_intents WHERE order_id=" + id));
        assertEquals(0, scalar(home(), "SELECT count(*) FROM ordering.orphan_results"));
        assertEquals(0, scalar(home(), "SELECT count(*) FROM ordering.conflicting_results"));
        publishCommand(command);
        await("ledger duplicate result settled", () -> scalar(ledgerDatabase,
                "SELECT count(*) FROM ledger.ledger_outbox WHERE message_id="
                        + command.path("resultMessageId").asText()
                        + " AND publication_attempt>=2 AND published_at IS NOT NULL") == 1
                && broker.queue(RESULT_QUEUE).path("messages").asInt() == 0);
        assertEquals(1, credits(us, id));
        assertEquals(1, credits(asia, id));
        assertEquals(new BigDecimal("14.90"), balance(us));
        assertEquals(new BigDecimal("-4.00"), balance(asia));
        assertEquals("CONFIRMED", status(id));
        browser.assertProduct(us, 8, Map.of("USD", "14.90"));
        browser.assertProduct(asia, 8, Map.of("USD", "-4.00"));
    }

    @Then("a packaged ledger rejection cancels the order and releases reservations without profit")
    public void rejectedRelease() throws Exception {
        String id = accept(1, us, asia);
        startWorkers(true);
        awaitStatus(id, "PENDING_LEDGER");
        browser.assertProduct(us, 9, Map.of("USD", "0.00"));
        browser.assertProduct(asia, 9, Map.of("USD", "0.00"));
        startLedger("EUR");
        publishCommand(command(id));
        awaitStatus(id, "CANCELLED");
        await("stock release", () -> scalar(home(), "SELECT count(*) FROM ordering.order_sagas WHERE order_id=" + id
                + " AND stock_released_at IS NOT NULL") == 1);
        for (var fixture : List.of(us, asia)) {
            browser.assertProduct(fixture, 10, Map.of("USD", "0.00"));
            assertEquals(0, credits(fixture, id));
            assertEquals(BigDecimal.ZERO.setScale(2), balance(fixture));
            assertEquals(1, scalar(shards.ofSeller(fixture.seller().id()).database(),
                    "SELECT count(*) FROM catalog.stock_reservations WHERE order_id=" + id
                            + " AND product_id=" + fixture.id() + " AND released_at IS NOT NULL"));
        }
        assertEquals(0, scalar(home(), "SELECT count(*) FROM ordering.profit_intents WHERE order_id=" + id));
    }

    @Then("packaged order cleanup keeps commands without a checkpoint and unpublished commands")
    public void orderCleanupRequiresCheckpoint() throws Exception {
        String published = accept(1, us);
        String unpublished = accept(1, asia);
        order.enableStock();
        order.enableResults(broker);
        order.enableCleanup();
        order.restart(Set.of());
        awaitStatus(published, "PENDING_LEDGER");
        awaitStatus(unpublished, "PENDING_LEDGER");
        ObjectNode saved = command(published);
        ObjectNode unpublishedEnvelope = command(unpublished);
        ageUnpublishedOrderOutbox(unpublished);
        long staleCommit = walLsn();
        markPublished(published);
        ObjectNode recorded = result(saved, "RECORDED");
        publishResult(recorded);
        awaitStatus(published, "CONFIRMED");
        ageOrderOutbox(published);
        ageOrderInbox(recorded.path("messageId").asLong());
        await("old result inbox is removed by the packaged order worker", () -> orderInbox(recorded) == 0);
        assertEquals(1, outboxes(published), "Publication without a durable CDC offset cannot be cleaned");
        assertEquals(1, outboxes(unpublished), "An unpublished command is never eligible");
        assertEquals(unpublishedEnvelope, command(unpublished));

        // The offset is at the WAL position before its own fixture transaction. Its
        // apparent event LSN is deliberately ahead; lsn_commit must take precedence.
        installStaleCheckpoint(staleCommit);
        publishResult(recorded);
        await("duplicate restores its transport inbox", () -> orderInbox(recorded) == 1);
        ageOrderInbox(recorded.path("messageId").asLong());
        await("another cleanup cycle removes the expired duplicate inbox", () -> orderInbox(recorded) == 0);
        assertEquals(1, outboxes(published), "A stale commit offset cannot permit command deletion");
        assertEquals(1, outboxes(unpublished));
        assertEquals(0, attempts(published), "Transport cleanup cannot spend the saga retry budget");
        assertEquals(0, attempts(unpublished));
        assertEquals(1, bindings(published));
        assertEquals(1, bindings(unpublished));
        assertEquals(1, scalar(home(), "SELECT count(*) FROM ordering.orders WHERE order_id=" + published));
        assertEquals(1, scalar(home(), "SELECT count(*) FROM ordering.order_sagas WHERE order_id=" + published));
        assertCleanupIndex(home().database(), "order_inbox_retention_idx");
        assertCleanupIndex(home().database(), "order_outbox_retention_idx");
    }

    @Then("packaged order and ledger cleanup preserve replay and business effects")
    public void transportCleanupAndReplay() throws Exception {
        for (var shard : shards.all()) postgres.createOrderPublication(shard.database());
        order.enableStock();
        order.enableCdc(broker);
        order.enableResults(broker);
        order.enableProfit();
        order.enableCleanup();
        order.restart(Set.of());
        ledgerDatabase = postgres.createLedgerDatabase();
        postgres.migrateLedger(ledgerDatabase, ShardShopFiles.resolve("database/ledger/ledger"));
        ledger.enableCleanup();
        ledger.startWithPublisher(ledgerDatabase, broker, "USD");

        String old = accept(1, us);
        awaitStatus(old, "CONFIRMED");
        await("old order profit is credited", () -> credits(us, old) == 1);
        ObjectNode command = command(old);
        ObjectNode originalResult = ledgerResult(command.path("resultMessageId").asLong());
        awaitCheckpointedPublication(old);
        await("old ledger result publication is confirmed", () -> scalar(ledgerDatabase,
                "SELECT count(*) FROM ledger.ledger_outbox WHERE message_id="
                        + command.path("resultMessageId").asText() + " AND published_at IS NOT NULL") == 1);
        String recent = accept(1, asia);
        awaitStatus(recent, "CONFIRMED");
        await("recent order profit is credited", () -> credits(asia, recent) == 1);
        awaitCheckpointedPublication(recent);
        ObjectNode recentCommand = command(recent);
        await("recent ledger result publication is confirmed", () -> scalar(ledgerDatabase,
                "SELECT count(*) FROM ledger.ledger_outbox WHERE message_id="
                        + recentCommand.path("resultMessageId").asText() + " AND published_at IS NOT NULL") == 1);
        long oldCommandId = command.path("messageId").asLong();
        long oldResultId = command.path("resultMessageId").asLong();
        long recentCommandId = recentCommand.path("messageId").asLong();
        long recentResultId = recentCommand.path("resultMessageId").asLong();

        ObjectNode changedCommand = command.deepCopy().put("occurredAt",
                Instant.parse(command.path("occurredAt").asText()).plusSeconds(1).toString());
        ObjectNode changedResult = originalResult.deepCopy().put("occurredAt",
                Instant.parse(originalResult.path("occurredAt").asText()).plusSeconds(1).toString());
        publishCommand(changedCommand);
        publishResult(changedResult);
        await("changed duplicate envelopes are quarantined", () ->
                scalar(ledgerDatabase, "SELECT count(*) FROM ledger.conflicting_commands WHERE order_id=" + old) == 1
                        && conflicts(old) == 1);
        ageQuarantine(old);
        ageRecentOutboxCreation(recent, recentResultId);

        ageOrderOutbox(old);
        ageOrderInbox(oldResultId);
        ageLedgerTransport(oldCommandId, oldResultId);
        await("all four expired transport rows are removed by packaged workers", () ->
                outboxes(old) == 0 && orderInbox(originalResult) == 0
                        && ledgerTransport("ledger.ledger_inbox", "message_id", oldCommandId) == 0
                        && ledgerTransport("ledger.ledger_outbox", "message_id", oldResultId) == 0);
        long afterDelete = walLsn();
        CdcRelay relay = new CdcRelay(shards, broker);
        await("CDC checkpoints past the cleanup delete", () -> relay.offsetLsn(home()) > afterDelete);
        assertEquals(1, outboxes(recent), "A recently published command remains");
        assertEquals(1, orderInbox(recentResultId), "A recent result delivery remains");
        assertEquals(1, ledgerTransport("ledger.ledger_inbox", "message_id", recentCommandId));
        assertEquals(1, ledgerTransport("ledger.ledger_outbox", "message_id", recentResultId));
        assertEquals(1, scalar(home(), "SELECT count(*) FROM ordering.order_result_ids WHERE message_id=" + oldCommandId));
        assertEquals(1, scalar(ledgerDatabase, "SELECT count(*) FROM ledger.ledger_result_ids WHERE message_id=" + oldCommandId));
        assertEquals(1, scalar(home(), "SELECT count(*) FROM ordering.orders WHERE order_id=" + old));
        assertEquals(1, scalar(home(), "SELECT count(*) FROM ordering.order_sagas WHERE order_id=" + old
                + " AND reconciliation_attempts=0"));
        assertEquals(1, scalar(shards.ofSeller(us.seller().id()),
                "SELECT count(*) FROM catalog.stock_reservations WHERE order_id=" + old));
        assertEquals(1, scalar(shards.ofSeller(us.seller().id()),
                "SELECT count(*) FROM catalog.stock_reservation_guards WHERE order_id=" + old));
        assertEquals(1, scalar(home(), "SELECT count(*) FROM ordering.profit_intents WHERE order_id=" + old));
        assertEquals(1, scalar(ledgerDatabase, "SELECT count(*) FROM ledger.ledger_operations WHERE order_id=" + old));
        assertEquals(1, scalar(ledgerDatabase, "SELECT count(*) FROM ledger.ledger_entries WHERE order_id=" + old));
        assertEquals(1, credits(us, old));
        assertEquals(1, conflicts(old), "Unresolved order quarantine survives cleanup");
        assertEquals(1, scalar(ledgerDatabase, "SELECT count(*) FROM ledger.conflicting_commands WHERE order_id=" + old),
                "Unresolved ledger quarantine survives cleanup");
        assertEquals(0, queueCount("ledger.record-order"), "Cleanup deletes must not become commands");
        assertEquals(2, scalar(ledgerDatabase, "SELECT count(*) FROM ledger.ledger_result_ids"),
                "Cleanup must not initiate another result publication");
        assertEquals(1, scalar(ledgerDatabase,
                "SELECT publication_attempt FROM ledger.ledger_result_ids WHERE result_message_id=" + oldResultId));
        assertCleanupIndex(home().database(), "order_inbox_retention_idx");
        assertCleanupIndex(home().database(), "order_outbox_retention_idx");
        assertCleanupIndex(ledgerDatabase, "ledger_inbox_retention_idx");
        assertCleanupIndex(ledgerDatabase, "ledger_outbox_retention_idx");

        publishCommand(command);
        await("ledger recreates and publishes the saved result after cleanup", () ->
                ledgerTransport("ledger.ledger_outbox", "message_id", oldResultId) == 1
                        && scalar(ledgerDatabase, "SELECT count(*) FROM ledger.ledger_outbox WHERE message_id="
                        + oldResultId + " AND publication_attempt=2 AND published_at IS NOT NULL") == 1);
        await("order records the duplicate result after inbox cleanup", () -> orderInbox(originalResult) == 1);
        assertEquals(originalResult, ledgerResult(oldResultId), "Replay preserves the exact result envelope");
        assertEquals("CONFIRMED", status(old));
        assertEquals(0, attempts(old));
        assertEquals(1, scalar(ledgerDatabase, "SELECT count(*) FROM ledger.ledger_operations WHERE order_id=" + old));
        assertEquals(1, scalar(ledgerDatabase, "SELECT count(*) FROM ledger.ledger_entries WHERE order_id=" + old));
        assertEquals(1, credits(us, old));
        assertEquals(9, scalar(shards.ofSeller(us.seller().id()),
                "SELECT stock FROM catalog.products WHERE product_id=" + us.id()));
        assertEquals(9, scalar(shards.ofSeller(asia.seller().id()),
                "SELECT stock FROM catalog.products WHERE product_id=" + asia.id()));
        assertEquals(1, conflicts(old));
        assertEquals(1, scalar(ledgerDatabase, "SELECT count(*) FROM ledger.conflicting_commands WHERE order_id=" + old));
    }

    @Then("packaged cleanup replays the original rejection after a policy change")
    public void rejectedCleanupAndReplay() throws Exception {
        for (var shard : shards.all()) postgres.createOrderPublication(shard.database());
        order.enableStock();
        order.enableCdc(broker);
        order.enableResults(broker);
        order.enableCleanup();
        order.restart(Set.of());
        ledger.enableCleanup();
        startLedger("EUR");

        String id = accept(1, us);
        awaitStatus(id, "CANCELLED");
        await("rejected reservation is released", () -> scalar(home(),
                "SELECT count(*) FROM ordering.order_sagas WHERE order_id=" + id
                        + " AND stock_released_at IS NOT NULL") == 1);
        ObjectNode command = command(id);
        ObjectNode savedResult = ledgerResult(command.path("resultMessageId").asLong());
        assertEquals("REJECTED", savedResult.path("outcome").asText());
        awaitCheckpointedPublication(id);
        long commandId = command.path("messageId").asLong();
        long resultId = command.path("resultMessageId").asLong();
        await("rejection publication is confirmed", () -> scalar(ledgerDatabase,
                "SELECT count(*) FROM ledger.ledger_outbox WHERE message_id=" + resultId
                        + " AND published_at IS NOT NULL") == 1);

        ageOrderOutbox(id);
        ageOrderInbox(resultId);
        ageLedgerTransport(commandId, resultId);
        await("rejected decision transport is cleaned by packaged workers", () ->
                outboxes(id) == 0 && orderInbox(resultId) == 0
                        && ledgerTransport("ledger.ledger_inbox", "message_id", commandId) == 0
                        && ledgerTransport("ledger.ledger_outbox", "message_id", resultId) == 0);
        assertEquals(1, scalar(ledgerDatabase, "SELECT count(*) FROM ledger.ledger_operations WHERE order_id=" + id
                + " AND outcome='REJECTED'"));
        assertEquals(0, scalar(ledgerDatabase, "SELECT count(*) FROM ledger.ledger_entries WHERE order_id=" + id));
        assertEquals(1, scalar(shards.ofSeller(us.seller().id()),
                "SELECT count(*) FROM catalog.stock_reservation_guards WHERE order_id=" + id + " AND closed"));

        ledger.restartWithPublisher("USD");
        publishCommand(command);
        await("saved rejection is republished after cleanup", () -> scalar(ledgerDatabase,
                "SELECT count(*) FROM ledger.ledger_outbox WHERE message_id=" + resultId
                        + " AND publication_attempt=2 AND published_at IS NOT NULL") == 1);
        await("order accepts the duplicate rejection after inbox cleanup", () -> orderInbox(resultId) == 1);
        assertEquals(savedResult, ledgerResult(resultId), "Policy changes cannot alter the saved rejection envelope");
        assertEquals("CANCELLED", status(id));
        assertEquals(0, attempts(id));
        assertEquals(1, scalar(ledgerDatabase, "SELECT count(*) FROM ledger.ledger_operations WHERE order_id=" + id));
        assertEquals(0, scalar(ledgerDatabase, "SELECT count(*) FROM ledger.ledger_entries WHERE order_id=" + id));
        assertEquals(0, credits(us, id));
        assertEquals(10, scalar(shards.ofSeller(us.seller().id()),
                "SELECT stock FROM catalog.products WHERE product_id=" + us.id()));
    }

    @Then("an overdue unpublished command waits while another eligible saga is replayed")
    public void unpublishedCommandDoesNotSpendBudget() throws Exception {
        String unpublished = accept(1, us);
        String eligible = accept(1, asia);
        order.enableStock();
        order.enableReconciliation();
        order.restart(Set.of());
        awaitStatus(unpublished, "PENDING_LEDGER");
        awaitStatus(eligible, "PENDING_LEDGER");
        ObjectNode original = command(unpublished);
        // The second command models a completed publication; the first remains pending for CDC.
        markPublished(eligible);
        forceDue(unpublished);
        forceDue(eligible);
        await("eligible saga is replayed by the packaged scanner", () -> attempts(eligible) == 1);
        assertEquals(2, outboxes(eligible));
        assertEquals(0, attempts(unpublished));
        assertEquals(1, bindings(unpublished));
        assertEquals(1, outboxes(unpublished));
        assertEquals(original, command(unpublished));
    }

    @Then("competing packaged coordinators persist five distinct replays without a sixth attempt")
    public void durableBudgetAndReplayIdentity() throws Exception {
        String id = accept(1, us);
        enableCheckpointedReconciliation();
        awaitStatus(id, "PENDING_LEDGER");
        ObjectNode original = command(id);
        awaitCheckpointedPublication(id);
        deletePublishedOutbox(id);
        competitor = new OrderRuntime(shards);
        competitor.enableReconciliation();
        competitor.start(Set.of());

        for (int attempt = 1; attempt <= 5; attempt++) {
            forceDue(id);
            int expected = attempt;
            await("replay attempt " + attempt + " commits with one outbox", () ->
                    attempts(id) == expected && outboxes(id) == 1);
            ObjectNode replay = command(id);
            assertEquals(original.path("commandId"), replay.path("commandId"));
            assertEquals(original.path("sagaId"), replay.path("sagaId"));
            assertEquals(original.path("orderId"), replay.path("orderId"));
            assertEquals(original.path("buyerId"), replay.path("buyerId"));
            assertEquals(original.path("requestFingerprint"), replay.path("requestFingerprint"));
            assertEquals(original.path("snapshot"), replay.path("snapshot"));
            assertNotEquals(original.path("messageId"), replay.path("messageId"));
            assertNotEquals(original.path("resultMessageId"), replay.path("resultMessageId"));
            assertEquals(attempt + 1, bindings(id), "Every replay reserves a permanent result identity");
            if (attempt < 5) assertNextEligibilityIsDelayed(id, attempt);
            awaitCheckpointedPublication(id);
            deletePublishedOutbox(id);
            if (attempt == 2) {
                order.restartSaga();
                competitor.restartSaga();
            }
        }

        // A fresh due saga proves that the same running scanner made another pass after exhaustion.
        String witness = accept(1, asia);
        awaitStatus(witness, "PENDING_LEDGER");
        awaitCheckpointedPublication(witness);
        deletePublishedOutbox(witness);
        forceDue(id);
        forceDue(witness);
        await("scanner visits a new due saga", () -> attempts(witness) == 1);
        assertEquals(5, attempts(id));
        assertEquals(6, bindings(id));
        assertEquals(0, outboxes(id));
    }

    @Then("an undelivered {word} result recovers through a checkpointed replay")
    public void lostLedgerResult(String outcome) throws Exception {
        String id = accept(1, us, asia);
        enableCheckpointedReconciliation();
        startLedger(outcome.equals("RECORDED") ? "USD" : "EUR");
        awaitStatus(id, "PENDING_LEDGER");
        ObjectNode original = command(id);
        awaitCheckpointedPublication(id);
        await("ledger stores and publishes its permanent decision", () ->
                scalar(ledgerDatabase, "SELECT count(*) FROM ledger.ledger_operations WHERE order_id=" + id
                        + " AND outcome='" + outcome + "'") == 1
                        && scalar(ledgerDatabase, "SELECT count(*) FROM ledger.ledger_outbox WHERE message_id="
                        + original.path("resultMessageId").asText() + " AND published_at IS NOT NULL") == 1
                        && queueCount(RESULT_QUEUE) == 1);
        assertEquals("PENDING_LEDGER", status(id));
        ObjectNode savedResult = ledgerResult(original.path("resultMessageId").asLong());
        assertEquals(outcome, savedResult.path("outcome").asText());

        // Remove the original result delivery and eligible transport rows, retaining both permanent identities.
        try (com.rabbitmq.client.Connection connection = broker.connect("admin");
             Channel channel = connection.createChannel()) {
            assertEquals(1, channel.queuePurge(RESULT_QUEUE).getMessageCount());
        }
        assertEquals(0, queueCount(RESULT_QUEUE));
        deletePublishedOutbox(id);
        try (Connection connection = postgres.superuser(ledgerDatabase); PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM ledger.ledger_outbox WHERE message_id=? AND published_at IS NOT NULL")) {
            delete.setLong(1, original.path("resultMessageId").asLong());
            assertEquals(1, delete.executeUpdate());
        }
        try (Connection connection = postgres.superuser(ledgerDatabase); PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM ledger.ledger_inbox WHERE message_id=?")) {
            delete.setLong(1, original.path("messageId").asLong());
            assertEquals(1, delete.executeUpdate());
        }
        assertEquals(1, scalar(ledgerDatabase, "SELECT count(*) FROM ledger.ledger_result_ids WHERE result_message_id="
                + original.path("resultMessageId").asText()));
        if (outcome.equals("REJECTED")) {
            // The policy now accepts USD; replay must still return the saved rejection.
            ledger.restartWithPublisher("USD");
        }

        order.enableResults(broker);
        order.enableProfit();
        order.restart(Set.of());
        forceDue(id);
        await("lost-result replay is enqueued", () -> attempts(id) == 1 && outboxes(id) == 1);
        ObjectNode replay = command(id);
        assertEquals(original.path("commandId"), replay.path("commandId"));
        assertEquals(original.path("snapshot"), replay.path("snapshot"));
        assertNotEquals(original.path("resultMessageId"), replay.path("resultMessageId"));
        awaitCheckpointedPublication(id);
        awaitStatus(id, outcome.equals("RECORDED") ? "CONFIRMED" : "CANCELLED");
        ObjectNode replayedResult = ledgerResult(replay.path("resultMessageId").asLong());
        assertEquals(savedResult.path("outcome"), replayedResult.path("outcome"));
        assertEquals(savedResult.path("decidedAt"), replayedResult.path("decidedAt"));
        assertEquals(savedResult.path("amount"), replayedResult.path("amount"));
        assertEquals(savedResult.path("currency"), replayedResult.path("currency"));
        assertEquals(savedResult.path("rejectionReason"), replayedResult.path("rejectionReason"));
        assertEquals(1, scalar(ledgerDatabase, "SELECT count(*) FROM ledger.ledger_operations WHERE order_id=" + id
                + " AND outcome='" + outcome + "'"));
        assertEquals(2, scalar(ledgerDatabase, "SELECT count(*) FROM ledger.ledger_result_ids WHERE order_id=" + id));
        assertEquals(1, scalar(home(), "SELECT count(*) FROM ordering.order_inbox WHERE saga_id="
                + original.path("sagaId").asText()));
        assertEquals(0, scalar(home(), "SELECT count(*) FROM ordering.conflicting_results WHERE order_id=" + id));
        if (outcome.equals("RECORDED")) {
            await("each seller receives one confirmed profit credit", () ->
                    credits(us, id) == 1 && credits(asia, id) == 1);
            assertEquals(1, scalar(ledgerDatabase, "SELECT count(*) FROM ledger.ledger_entries WHERE order_id=" + id));
            assertEquals(9, scalar(shards.ofSeller(us.seller().id()),
                    "SELECT stock FROM catalog.products WHERE product_id=" + us.id()));
            assertEquals(9, scalar(shards.ofSeller(asia.seller().id()),
                    "SELECT stock FROM catalog.products WHERE product_id=" + asia.id()));
        } else {
            await("rejected order releases both reservations", () -> scalar(home(),
                    "SELECT count(*) FROM ordering.order_sagas WHERE order_id=" + id
                            + " AND stock_released_at IS NOT NULL") == 1);
            assertEquals(0, scalar(ledgerDatabase, "SELECT count(*) FROM ledger.ledger_entries WHERE order_id=" + id));
            assertEquals(0, credits(us, id));
            assertEquals(0, credits(asia, id));
            assertEquals(10, scalar(shards.ofSeller(us.seller().id()),
                    "SELECT stock FROM catalog.products WHERE product_id=" + us.id()));
            assertEquals(10, scalar(shards.ofSeller(asia.seller().id()),
                    "SELECT stock FROM catalog.products WHERE product_id=" + asia.id()));
        }
        publishCommand(original);
        await("late original result is handled after the replay result", () ->
                scalar(ledgerDatabase, "SELECT count(*) FROM ledger.ledger_outbox WHERE message_id="
                        + original.path("resultMessageId").asText()
                        + " AND publication_attempt>=2 AND published_at IS NOT NULL") == 1
                        && scalar(home(), "SELECT count(*) FROM ordering.order_inbox WHERE saga_id="
                        + original.path("sagaId").asText()) == 2);
        assertEquals(outcome.equals("RECORDED") ? "CONFIRMED" : "CANCELLED", status(id));
        assertEquals(outcome.equals("RECORDED") ? 1 : 0,
                scalar(ledgerDatabase, "SELECT count(*) FROM ledger.ledger_entries WHERE order_id=" + id));
        assertEquals(outcome.equals("RECORDED") ? 1 : 0, credits(us, id));
        assertEquals(outcome.equals("RECORDED") ? 1 : 0, credits(asia, id));
        int expectedStock = outcome.equals("RECORDED") ? 9 : 10;
        assertEquals(expectedStock, scalar(shards.ofSeller(us.seller().id()),
                "SELECT stock FROM catalog.products WHERE product_id=" + us.id()));
        assertEquals(expectedStock, scalar(shards.ofSeller(asia.seller().id()),
                "SELECT stock FROM catalog.products WHERE product_id=" + asia.id()));
    }

    @Then("result replay and reconciliation are idempotent while changed identities and envelopes are quarantined")
    public void replayAndConflicts() throws Exception {
        String id = accept(1, us);
        startWorkers(false);
        awaitStatus(id, "PENDING_LEDGER");
        ObjectNode originalCommand = command(id);
        ObjectNode recorded = result(originalCommand, "RECORDED");
        publishResult(recorded);
        awaitStatus(id, "CONFIRMED");
        publishResult(recorded);
        await("duplicate remains one inbox decision", () -> scalar(home(),
                "SELECT count(*) FROM ordering.order_inbox WHERE saga_id=" + recorded.path("sagaId").asText()) == 1);
        assertEquals(0, scalar(home(), "SELECT count(*) FROM ordering.conflicting_results"));
        try (Connection connection = shards.superuser(home()); var statement = connection.createStatement()) {
            statement.execute("DELETE FROM ordering.order_inbox");
            statement.execute("DELETE FROM ordering.order_outbox");
        }
        publishResult(recorded);
        await("same result restored after transport cleanup", () -> scalar(home(),
                "SELECT count(*) FROM ordering.order_inbox WHERE saga_id=" + recorded.path("sagaId").asText()) == 1);
        String resultId = reserveResult(recorded.path("sagaId").asLong());
        ObjectNode reconciliation = recorded.deepCopy().put("messageId", resultId)
                .put("occurredAt", later(recorded, 1));
        publishResult(reconciliation);
        await("new reserved result accepted for terminal decision", () -> scalar(home(),
                "SELECT count(*) FROM ordering.order_inbox WHERE message_id=" + resultId) == 1);
        ObjectNode changedTimestamp = recorded.deepCopy().put("occurredAt", later(recorded, 2));
        publishResult(changedTimestamp);
        await("same result ID with changed timestamp quarantined", () -> conflicts(id) == 1);
        publishResult(changedTimestamp);
        await("repeated conflict increments its audit count", () -> scalar(home(),
                "SELECT COALESCE(MAX(delivery_count),0) FROM ordering.conflicting_results WHERE order_id=" + id
                        + " AND reason='RESULT_ENVELOPE_MISMATCH'") == 2);
        ObjectNode changedCommand = recorded.deepCopy().put("commandId", recorded.path("orderId").asText());
        publishResult(changedCommand);
        await("wrong command quarantined", () -> conflicts(id) == 2);
        ObjectNode changedOutcome = result(originalCommand, "REJECTED")
                .put("messageId", reserveResult(recorded.path("sagaId").asLong()))
                .put("occurredAt", later(recorded, 3));
        publishResult(changedOutcome);
        await("opposite terminal outcome quarantined", () -> conflicts(id) == 3);
        ObjectNode unreserved = recorded.deepCopy().put("messageId", allocate())
                .put("occurredAt", later(recorded, 4));
        publishResult(unreserved);
        await("unreserved result ID quarantined", () -> conflicts(id) == 4);
        assertEquals("CONFIRMED", status(id));
        assertEquals(0, scalar(home(), "SELECT count(*) FROM ordering.orphan_results"));
        assertEquals(1, scalar(home(), "SELECT count(*) FROM ordering.profit_intents WHERE order_id=" + id));
    }

    @Then("an orphan result blocks creation and an unavailable home does not create an orphan")
    public void orphanAndUnavailable() throws Exception {
        String id = allocate();
        startWorkers(false);
        // A valid result may arrive for a lost order while its allocation survives.
        ObjectNode ghost = resultForMissingOrder(id);
        publishResult(ghost);
        await("reachable-primary orphan committed", () -> scalar(home(),
                "SELECT count(*) FROM ordering.orphan_results WHERE order_id=" + id) == 1);
        assertEquals(409, http("PUT", path(id), null, request(1, us)).status());
        assertEquals(0, scalar(home(), "SELECT count(*) FROM ordering.orders WHERE order_id=" + id));

        String second = allocate();
        order.restart(Set.of("shard-b"));
        publishResult(resultForMissingOrder(second));
        await("broker parks after bounded unavailable-home retries", () -> queueCount(PARKING_QUEUE) == 1);
        assertEquals(0, scalar(home(), "SELECT count(*) FROM ordering.orphan_results WHERE order_id=" + second));
        order.restart(Set.of());
    }

    /** Creation checks quarantine under this lock, thus an orphan that ignored it could coexist with a new order. */
    @Then("an orphan result waits while order creation holds the lock of its order ID")
    public void orphanWaitsForCreationLock() throws Exception {
        String id = allocate();
        startWorkers(false);
        try (Connection creation = shards.superuser(home()); PreparedStatement lock = creation.prepareStatement(
                "SELECT pg_advisory_xact_lock(?)")) {
            creation.setAutoCommit(false);
            lock.setLong(1, CatalogRouting.creationLock("order", "", id));
            lock.execute();
            publishResult(resultForMissingOrder(id));
            await("result transaction waits for the creation lock", () -> {
                assertEquals(0, orphans(id), "Orphan quarantine must not pass a held creation lock");
                return scalar(home(), "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() "
                        + "AND usename='order_app' AND wait_event_type='Lock' AND wait_event='advisory'") > 0;
            });
            assertEquals(0, orphans(id));
            creation.rollback();
        }
        await("orphan committed after creation released its lock", () -> orphans(id) == 1);
        assertEquals(0, scalar(home(), "SELECT count(*) FROM ordering.orders WHERE order_id=" + id));
    }

    @Then("a malformed result is parked without a database side effect")
    public void malformedParking() throws Exception {
        String id = accept(1, us);
        startWorkers(false);
        awaitStatus(id, "PENDING_LEDGER");
        ObjectNode malformed = result(command(id), "RECORDED").put("messageId", "00");
        assertFalse(Contracts.LEDGER_RESULT.errors("#", malformed).isEmpty());
        publishResult(malformed);
        await("malformed result parked", () -> queueCount(PARKING_QUEUE) == 1);
        assertEquals("PENDING_LEDGER", status(id));
        assertEquals(0, scalar(home(), "SELECT count(*) FROM ordering.order_inbox"));
        assertEquals(0, conflicts(id));
        assertEquals(0, scalar(home(), "SELECT count(*) FROM ordering.orphan_results"));
    }

    @Then("confirmed profit intents survive a catalog outage and order restart")
    public void profitRestart() throws Exception {
        String id = accept(1, us, asia);
        startWorkers(false);
        awaitStatus(id, "PENDING_LEDGER");
        publishResult(result(command(id), "RECORDED"));
        awaitStatus(id, "CONFIRMED");
        assertEquals(2, scalar(home(), "SELECT count(*) FROM ordering.profit_intents WHERE order_id=" + id));
        order.enableProfit();
        order.restart(Set.of("shard-d"));
        await("reachable seller credited", () -> credits(us, id) == 1);
        assertEquals(0, credits(asia, id));
        order.restart(Set.of());
        await("deferred seller credited", () -> credits(asia, id) == 1);
        assertEquals(new BigDecimal("7.45"), balance(us));
        assertEquals(new BigDecimal("-2.00"), balance(asia));
        assertEquals("CONFIRMED", status(id));
    }

    @Then("a failed profit intent insert leaves the result unacknowledged until retry succeeds")
    public void transitionRollback() throws Exception {
        String id = accept(1, us);
        startWorkers(false);
        awaitStatus(id, "PENDING_LEDGER");
        ObjectNode recorded = result(command(id), "RECORDED");
        try (Connection connection = shards.superuser(home()); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE ordering.profit_intents ADD CONSTRAINT injected_intent_failure "
                    + "CHECK (amount <> 7.45) NOT VALID");
        }
        publishResult(recorded);
        await("failed transaction reaches broker parking", () -> queueCount(PARKING_QUEUE) == 1);
        assertEquals("PENDING_LEDGER", status(id));
        assertEquals(0, scalar(home(), "SELECT count(*) FROM ordering.profit_intents WHERE order_id=" + id));
        assertEquals(0, scalar(home(), "SELECT count(*) FROM ordering.order_inbox WHERE message_id="
                + recorded.path("messageId").asText()));
        assertEquals(0, scalar(home(), "SELECT count(*) FROM ordering.order_result_ids WHERE result_message_id="
                + recorded.path("messageId").asText() + " AND received_envelope IS NOT NULL"));
        try (Connection connection = shards.superuser(home()); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE ordering.profit_intents DROP CONSTRAINT injected_intent_failure");
        }
        publishResult(recorded);
        awaitStatus(id, "CONFIRMED");
        assertEquals(1, scalar(home(), "SELECT count(*) FROM ordering.profit_intents WHERE order_id=" + id));
    }

    @Then("a recorded result with an unknown item cost is quarantined before confirmation")
    public void unknownHistoricalCost() throws Exception {
        String id = accept(1, us);
        startWorkers(true);
        awaitStatus(id, "PENDING_LEDGER");
        try (Connection connection = shards.superuser(home()); PreparedStatement change = connection.prepareStatement(
                "UPDATE ordering.order_items SET unit_cost=NULL WHERE order_id=? AND product_id=?")) {
            change.setLong(1, Long.parseLong(id));
            change.setLong(2, Long.parseLong(us.id()));
            assertEquals(1, change.executeUpdate());
        }
        ObjectNode recorded = result(command(id), "RECORDED");
        publishResult(recorded);
        await("unknown historical cost quarantined", () -> scalar(home(),
                "SELECT count(*) FROM ordering.conflicting_results WHERE order_id=" + id
                        + " AND reason='ITEM_COST_UNKNOWN'") == 1);
        assertEquals("PENDING_LEDGER", status(id));
        assertEquals(0, scalar(home(), "SELECT count(*) FROM ordering.profit_intents WHERE order_id=" + id));
        assertEquals(0, credits(us, id));
        assertEquals(BigDecimal.ZERO.setScale(2), balance(us));
        assertEquals(0, scalar(home(), "SELECT count(*) FROM ordering.order_inbox WHERE message_id="
                + recorded.path("messageId").asText()));
    }

    @Then("a committed seller credit is reused after an interrupted home mark by two workers")
    public void interruptedProfitMark() throws Exception {
        String id = accept(1, us);
        startWorkers(false);
        awaitStatus(id, "PENDING_LEDGER");
        publishResult(result(command(id), "RECORDED"));
        awaitStatus(id, "CONFIRMED");
        try (Connection holder = shards.superuser(home()); PreparedStatement lock = holder.prepareStatement(
                "SELECT item_position FROM ordering.profit_intents WHERE order_id=? FOR UPDATE")) {
            holder.setAutoCommit(false);
            lock.setLong(1, Long.parseLong(id));
            try (ResultSet rows = lock.executeQuery()) {
                assertTrue(rows.next());
            }
            order.enableProfit();
            order.restart(Set.of());
            await("seller transaction commits before home mark", () -> credits(us, id) == 1
                    && scalar(home(), "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() "
                            + "AND usename='order_app' AND wait_event_type='Lock' "
                            + "AND query ILIKE 'UPDATE ordering.profit_intents%'") > 0);
            order.stop();
            holder.rollback();
        }
        assertEquals(1, credits(us, id));
        assertEquals(new BigDecimal("7.45"), balance(us));
        assertEquals(0, scalar(home(), "SELECT count(*) FROM ordering.profit_intents WHERE order_id=" + id
                + " AND applied_at IS NOT NULL"));
        competitor = new OrderRuntime(shards);
        competitor.enableProfit();
        secondCompetitor = new OrderRuntime(shards);
        secondCompetitor.enableProfit();
        competitor.start(Set.of());
        secondCompetitor.start(Set.of());
        await("one durable intent marked after both workers retry", () -> scalar(home(),
                "SELECT count(*) FROM ordering.profit_intents WHERE order_id=" + id + " AND applied_at IS NOT NULL") == 1);
        assertEquals(1, credits(us, id));
        assertEquals(new BigDecimal("7.45"), balance(us));
    }

    private void profitGrants() throws Exception {
        String database = shards.ofSeller(us.seller().id()).database();
        assertTrue(columnPrivilege(database, "order_app", "catalog.profit_credits", "amount", "INSERT"));
        assertTrue(columnPrivilege(database, "order_app", "catalog.seller_profits", "amount", "UPDATE"));
        assertTrue(columnPrivilege(database, "order_app", "catalog.products", "stock", "UPDATE"));
        assertFalse(columnPrivilege(database, "order_app", "catalog.profit_credits", "amount", "UPDATE"));
        assertFalse(columnPrivilege(database, "order_app", "catalog.products", "unit_cost", "UPDATE"));
        assertFalse(tablePrivilege(database, "order_app", "catalog.profit_credits", "DELETE"));
        assertFalse(columnPrivilege(database, "product_app", "catalog.seller_profits", "amount", "UPDATE"));
        assertFalse(columnPrivilege(database, "product_app", "catalog.profit_credits", "amount", "INSERT"));
    }

    private boolean columnPrivilege(String database, String role, String table, String column, String action)
            throws SQLException {
        try (Connection connection = postgres.superuser(database); PreparedStatement query = connection.prepareStatement(
                "SELECT has_column_privilege(?, ?, ?, ?)")) {
            query.setString(1, role);
            query.setString(2, table);
            query.setString(3, column);
            query.setString(4, action);
            try (ResultSet rows = query.executeQuery()) {
                assertTrue(rows.next());
                return rows.getBoolean(1);
            }
        }
    }

    private boolean tablePrivilege(String database, String role, String table, String action) throws SQLException {
        try (Connection connection = postgres.superuser(database); PreparedStatement query = connection.prepareStatement(
                "SELECT has_table_privilege(?, ?, ?)")) {
            query.setString(1, role);
            query.setString(2, table);
            query.setString(3, action);
            try (ResultSet rows = query.executeQuery()) {
                assertTrue(rows.next());
                return rows.getBoolean(1);
            }
        }
    }

    private CatalogApi.Product createProduct(String alias, String region, String shardName,
                                             String price, String cost) throws Exception {
        String key = alias;
        for (int candidate = 0; !shards.ofKey(region, key).name().equals(shardName); candidate++) key = alias + candidate;
        ObjectNode sellerRequest = CatalogApi.sellerRequest(alias + " company", region);
        var sellerResponse = catalog.createSeller(key, sellerRequest);
        body(sellerResponse, 201);
        var seller = catalog.remember(alias, key, sellerRequest, sellerResponse);
        assertEquals(shardName, shards.ofSeller(seller.id()).name());
        ObjectNode payload = CatalogApi.itemRequest().put("price", price).put("unitCost", cost).put("initialStock", 10);
        var productResponse = catalog.post(seller.path() + "/products", alias, Json.bytes(payload));
        body(productResponse, 201);
        return catalog.remember(alias, seller, alias, payload, productResponse);
    }

    private String accept(int quantity, CatalogApi.Product... fixtures) throws Exception {
        String id = allocate();
        assertEquals("PENDING_STOCK", body(http("PUT", path(id), null, request(quantity, fixtures)), 202)
                .path("status").asText());
        return id;
    }

    private String allocate() throws Exception {
        return body(http("POST", "/api/v1/buyers/" + buyer + "/order-allocations", null,
                Json.newObject().put("runName", "order-results").put("requestOrdinal", Integer.toString(ordinal++))),
                201).path("orderId").asText();
    }

    private String reserveResult(long sagaId) throws Exception {
        String commandId = allocate();
        String resultId = allocate();
        try (Connection connection = shards.superuser(home()); PreparedStatement binding = connection.prepareStatement(
                "INSERT INTO ordering.order_result_ids(message_id, result_message_id, saga_id) VALUES (?, ?, ?)")) {
            binding.setLong(1, Long.parseLong(commandId));
            binding.setLong(2, Long.parseLong(resultId));
            binding.setLong(3, sagaId);
            assertEquals(1, binding.executeUpdate());
        }
        return resultId;
    }

    private void startWorkers(boolean profit) throws Exception {
        order.enableStock();
        order.enableResults(broker);
        if (profit) order.enableProfit();
        order.restart(Set.of());
    }

    private void enableCheckpointedReconciliation() throws Exception {
        for (var shard : shards.all()) postgres.createOrderPublication(shard.database());
        order.enableStock();
        order.enableCdc(broker);
        order.enableReconciliation();
        order.restart(Set.of());
    }

    private long walLsn() throws SQLException {
        try (Connection connection = shards.superuser(home()); var statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), '0/0')::bigint")) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private void awaitCheckpointedPublication(String id) throws Exception {
        CdcRelay relay = new CdcRelay(shards, broker);
        await("published command for order " + id, () ->
                scalar(home(), "SELECT count(*) FROM ordering.order_outbox o JOIN ordering.order_sagas s"
                        + " USING (saga_id) WHERE s.order_id=" + id + " AND o.published_at IS NOT NULL") == 1);
        // Capture a WAL position after the committed insert. The reader's transactional
        // logical heartbeat advances its durable offset beyond this barrier without changing
        // the production insert-only publication or the command transport row.
        long barrier = walLsn();
        await("durable CDC checkpoint after published command " + id, () -> relay.offsetLsn(home()) > barrier);
    }

    private void ageOrderOutbox(String id) throws SQLException {
        try (Connection connection = shards.superuser(home()); PreparedStatement age = connection.prepareStatement(
                "UPDATE ordering.order_outbox SET created_at=clock_timestamp()-INTERVAL '3 hours', "
                        + "published_at=clock_timestamp()-INTERVAL '2 hours' WHERE saga_id="
                        + "(SELECT saga_id FROM ordering.order_sagas WHERE order_id=?) AND published_at IS NOT NULL")) {
            age.setLong(1, Long.parseLong(id));
            assertEquals(1, age.executeUpdate());
        }
    }

    private void ageUnpublishedOrderOutbox(String id) throws SQLException {
        try (Connection connection = shards.superuser(home()); PreparedStatement age = connection.prepareStatement(
                "UPDATE ordering.order_outbox SET created_at=clock_timestamp()-INTERVAL '2 hours' "
                        + "WHERE saga_id=(SELECT saga_id FROM ordering.order_sagas WHERE order_id=?) "
                        + "AND published_at IS NULL")) {
            age.setLong(1, Long.parseLong(id));
            assertEquals(1, age.executeUpdate());
        }
    }

    private void ageRecentOutboxCreation(String orderId, long ledgerResultId) throws SQLException {
        try (Connection connection = shards.superuser(home()); PreparedStatement age = connection.prepareStatement(
                "UPDATE ordering.order_outbox SET created_at=clock_timestamp()-INTERVAL '2 hours' "
                        + "WHERE saga_id=(SELECT saga_id FROM ordering.order_sagas WHERE order_id=?) "
                        + "AND published_at IS NOT NULL")) {
            age.setLong(1, Long.parseLong(orderId));
            assertEquals(1, age.executeUpdate());
        }
        try (Connection connection = postgres.superuser(ledgerDatabase); PreparedStatement age = connection.prepareStatement(
                "UPDATE ledger.ledger_outbox SET created_at=clock_timestamp()-INTERVAL '2 hours' "
                        + "WHERE message_id=? AND published_at IS NOT NULL")) {
            age.setLong(1, ledgerResultId);
            assertEquals(1, age.executeUpdate());
        }
    }

    private void ageOrderInbox(long resultId) throws SQLException {
        try (Connection connection = shards.superuser(home()); PreparedStatement age = connection.prepareStatement(
                "UPDATE ordering.order_inbox SET received_at=clock_timestamp()-INTERVAL '2 hours' WHERE message_id=?")) {
            age.setLong(1, resultId);
            assertEquals(1, age.executeUpdate());
        }
    }

    private void ageLedgerTransport(long commandId, long resultId) throws SQLException {
        try (Connection connection = postgres.superuser(ledgerDatabase);
             PreparedStatement inbox = connection.prepareStatement(
                     "UPDATE ledger.ledger_inbox SET received_at=clock_timestamp()-INTERVAL '2 hours' "
                             + "WHERE message_id=?");
             PreparedStatement outbox = connection.prepareStatement(
                     "UPDATE ledger.ledger_outbox SET created_at=clock_timestamp()-INTERVAL '3 hours', "
                             + "published_at=clock_timestamp()-INTERVAL '2 hours' "
                             + "WHERE message_id=? AND published_at IS NOT NULL")) {
            inbox.setLong(1, commandId);
            outbox.setLong(1, resultId);
            assertEquals(1, inbox.executeUpdate());
            assertEquals(1, outbox.executeUpdate());
        }
    }

    private void ageQuarantine(String orderId) throws SQLException {
        for (String database : List.of(home().database(), ledgerDatabase)) {
            String table = database.equals(ledgerDatabase)
                    ? "ledger.conflicting_commands" : "ordering.conflicting_results";
            try (Connection connection = postgres.superuser(database);
                 PreparedStatement age = connection.prepareStatement("UPDATE " + table
                         + " SET first_seen_at=clock_timestamp()-INTERVAL '2 hours', "
                         + "last_seen_at=clock_timestamp()-INTERVAL '2 hours' WHERE order_id=?")) {
                age.setLong(1, Long.parseLong(orderId));
                assertEquals(1, age.executeUpdate());
            }
        }
    }

    private void installStaleCheckpoint(long commitLsn) throws SQLException {
        String identity = "shardshop_order_" + home().name().replace('-', '_');
        String key = "[\"" + identity + "\",{\"server\":\"" + identity + "\"}]";
        String value = "{\"lsn\":" + Long.MAX_VALUE + ",\"lsn_commit\":" + commitLsn + "}";
        try (Connection connection = shards.superuser(home());
             PreparedStatement history = connection.prepareStatement(
                     "INSERT INTO ordering.cdc_reader_state(reader_name) VALUES (?)");
             PreparedStatement offset = connection.prepareStatement(
                     "INSERT INTO ordering.debezium_offset_storage"
                             + "(id,offset_key,offset_val,record_insert_ts,record_insert_seq) "
                             + "VALUES (?,?,?,CURRENT_TIMESTAMP,0)")) {
            history.setString(1, identity);
            assertEquals(1, history.executeUpdate());
            offset.setString(1, java.util.UUID.randomUUID().toString());
            offset.setString(2, key);
            offset.setString(3, value);
            assertEquals(1, offset.executeUpdate());
        }
    }

    private long orderInbox(ObjectNode result) throws SQLException {
        return orderInbox(result.path("messageId").asLong());
    }

    private long orderInbox(long resultId) throws SQLException {
        return scalar(home(), "SELECT count(*) FROM ordering.order_inbox WHERE message_id=" + resultId);
    }

    private long ledgerTransport(String table, String key, long id) throws SQLException {
        return scalar(ledgerDatabase, "SELECT count(*) FROM " + table + " WHERE " + key + "=" + id);
    }

    private void assertCleanupIndex(String database, String name) throws SQLException {
        try (Connection connection = postgres.superuser(database); PreparedStatement query = connection.prepareStatement(
                "SELECT count(*) FROM pg_indexes WHERE indexname=?")) {
            query.setString(1, name);
            try (ResultSet rows = query.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1), "Missing bounded transport cleanup index " + name);
            }
        }
    }

    private void deletePublishedOutbox(String id) throws SQLException {
        try (Connection connection = shards.superuser(home()); PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM ordering.order_outbox WHERE saga_id=(SELECT saga_id FROM ordering.order_sagas WHERE order_id=?)"
                        + " AND published_at IS NOT NULL")) {
            delete.setLong(1, Long.parseLong(id));
            assertEquals(1, delete.executeUpdate(), "Only a published command is eligible for this fixture cleanup");
        }
        assertEquals(0, outboxes(id));
    }

    private void markPublished(String id) throws SQLException {
        try (Connection connection = shards.superuser(home()); PreparedStatement mark = connection.prepareStatement(
                "UPDATE ordering.order_outbox SET published_at=clock_timestamp() WHERE saga_id="
                        + "(SELECT saga_id FROM ordering.order_sagas WHERE order_id=?) AND published_at IS NULL")) {
            mark.setLong(1, Long.parseLong(id));
            assertEquals(1, mark.executeUpdate());
        }
    }

    private void forceDue(String id) throws SQLException {
        try (Connection connection = shards.superuser(home()); PreparedStatement update = connection.prepareStatement(
                "UPDATE ordering.order_sagas SET next_reconciliation_at="
                        + "GREATEST(created_at, CURRENT_TIMESTAMP - INTERVAL '1 second') WHERE order_id=?")) {
            update.setLong(1, Long.parseLong(id));
            assertEquals(1, update.executeUpdate());
        }
    }

    private int attempts(String id) throws SQLException {
        return (int) scalar(home(), "SELECT reconciliation_attempts FROM ordering.order_sagas WHERE order_id=" + id);
    }

    private long bindings(String id) throws SQLException {
        return scalar(home(), "SELECT count(*) FROM ordering.order_result_ids r JOIN ordering.order_sagas s"
                + " USING (saga_id) WHERE s.order_id=" + id);
    }

    private long outboxes(String id) throws SQLException {
        return scalar(home(), "SELECT count(*) FROM ordering.order_outbox o JOIN ordering.order_sagas s"
                + " USING (saga_id) WHERE s.order_id=" + id);
    }

    private void assertNextEligibilityIsDelayed(String id, int attempt) throws SQLException {
        long seconds = switch (attempt) {
            case 1 -> 120;
            case 2 -> 240;
            case 3, 4 -> 300;
            default -> throw new IllegalArgumentException("No next automatic attempt follows attempt " + attempt);
        };
        try (Connection connection = shards.superuser(home()); PreparedStatement query = connection.prepareStatement(
                "SELECT next_reconciliation_at FROM ordering.order_sagas WHERE order_id=?")) {
            query.setLong(1, Long.parseLong(id));
            try (ResultSet rows = query.executeQuery()) {
                assertTrue(rows.next());
                Instant due = rows.getObject(1, java.time.OffsetDateTime.class).toInstant();
                Instant expected = Instant.now().plusSeconds(seconds);
                assertFalse(due.isBefore(expected.minusSeconds(10)), "Next eligibility follows the persisted schedule");
                assertFalse(due.isAfter(expected.plusSeconds(10)), "Next eligibility follows the persisted schedule");
            }
        }
    }

    private ObjectNode ledgerResult(long resultMessageId) throws SQLException {
        try (Connection connection = postgres.superuser(ledgerDatabase); PreparedStatement query = connection.prepareStatement(
                "SELECT envelope::text FROM ledger.ledger_result_ids WHERE result_message_id=?")) {
            query.setLong(1, resultMessageId);
            try (ResultSet rows = query.executeQuery()) {
                assertTrue(rows.next(), "Ledger retains the immutable result envelope");
                ObjectNode envelope = Json.object(rows.getString(1));
                assertFalse(rows.next());
                return envelope;
            }
        }
    }

    private void startLedger(String allowedCurrencies) throws Exception {
        ledgerDatabase = postgres.createLedgerDatabase();
        postgres.migrateLedger(ledgerDatabase, ShardShopFiles.resolve("database/ledger/ledger"));
        ledger.startWithPublisher(ledgerDatabase, broker, allowedCurrencies);
    }

    private ObjectNode command(String id) throws Exception {
        try (Connection connection = shards.superuser(home()); PreparedStatement query = connection.prepareStatement(
                "SELECT envelope::text FROM ordering.order_outbox o JOIN ordering.order_sagas s USING (saga_id) "
                        + "WHERE s.order_id=?")) {
            query.setLong(1, Long.parseLong(id));
            try (ResultSet rows = query.executeQuery()) {
                assertTrue(rows.next(), "Reserved order has a command envelope");
                ObjectNode envelope = Json.object(rows.getString(1));
                assertEquals(List.of(), Contracts.RECORD_ORDER.errors("#", envelope));
                assertFalse(rows.next());
                return envelope;
            }
        }
    }

    private static ObjectNode result(ObjectNode command, String outcome) {
        ObjectNode result = Json.newObject();
        result.put("type", outcome.equals("RECORDED") ? "LedgerRecorded" : "LedgerRejected");
        result.put("schemaVersion", 1);
        result.set("messageId", command.path("resultMessageId"));
        for (String field : List.of("commandId", "sagaId", "orderId", "buyerId", "requestFingerprint")) {
            result.set(field, command.path(field));
        }
        result.set("occurredAt", command.path("occurredAt"));
        result.set("amount", command.at("/snapshot/amount"));
        result.set("currency", command.at("/snapshot/currency"));
        result.put("outcome", outcome);
        if (outcome.equals("REJECTED")) result.put("rejectionReason", "UNSUPPORTED_CURRENCY");
        result.set("decidedAt", command.path("occurredAt"));
        assertEquals(List.of(), Contracts.LEDGER_RESULT.errors("#", result));
        return result;
    }

    private static String later(ObjectNode result, long seconds) {
        return Instant.parse(result.path("occurredAt").asText()).plusSeconds(seconds).toString();
    }

    private ObjectNode resultForMissingOrder(String id) {
        ObjectNode command = Json.newObject();
        command.put("resultMessageId", id);
        command.put("commandId", id);
        command.put("sagaId", id);
        command.put("orderId", id);
        command.put("buyerId", buyer);
        command.put("requestFingerprint", "a".repeat(64));
        command.put("occurredAt", "2026-10-06T12:00:00Z");
        command.putObject("snapshot").put("amount", "19.95").put("currency", "USD");
        return result(command, "RECORDED");
    }

    private void publishCommand(ObjectNode envelope) throws Exception {
        publish("ledger.commands", "ledger.record-order", envelope);
    }

    private void publishResult(ObjectNode envelope) throws Exception {
        publish("order.saga-results", RESULT_QUEUE, envelope);
    }

    private void publish(String exchange, String route, ObjectNode envelope) throws Exception {
        try (com.rabbitmq.client.Connection connection = broker.connect(exchange.equals("ledger.commands") ? "order" : "ledger");
             Channel channel = connection.createChannel()) {
            channel.confirmSelect();
            channel.basicPublish(exchange, route, true,
                    new AMQP.BasicProperties.Builder().contentType("application/json").deliveryMode(2)
                            .messageId(envelope.path("messageId").asText()).build(), Json.bytes(envelope));
            channel.waitForConfirmsOrDie(5_000);
        }
    }

    private static ObjectNode request(int quantity, CatalogApi.Product... fixtures) {
        ObjectNode request = Json.newObject().put("currency", fixtures[0].request().path("currency").asText());
        var items = request.putArray("items");
        for (var fixture : fixtures) items.addObject().put("sellerId", fixture.seller().id())
                .put("productId", fixture.id()).put("quantity", quantity);
        return request;
    }

    private String status(String id) throws Exception {
        return body(http("GET", path(id), null, null), 200).path("status").asText();
    }

    private void awaitStatus(String id, String expected) throws Exception {
        await("order " + id + " reaches " + expected, () -> expected.equals(status(id)));
    }

    private int queueCount(String queue) throws Exception {
        try (com.rabbitmq.client.Connection connection = broker.connect("admin"); Channel channel = connection.createChannel()) {
            return channel.queueDeclarePassive(queue).getMessageCount();
        }
    }

    private long credits(CatalogApi.Product fixture, String id) throws Exception {
        return scalar(shards.ofSeller(fixture.seller().id()).database(),
                "SELECT count(*) FROM catalog.profit_credits WHERE order_id=" + id + " AND product_id=" + fixture.id());
    }

    private BigDecimal credit(CatalogApi.Product fixture, String id) throws Exception {
        return decimal(shards.ofSeller(fixture.seller().id()).database(),
                "SELECT amount FROM catalog.profit_credits WHERE order_id=" + id + " AND product_id=" + fixture.id());
    }

    private BigDecimal balance(CatalogApi.Product fixture) throws Exception {
        return decimal(shards.ofSeller(fixture.seller().id()).database(),
                "SELECT amount FROM catalog.seller_profits WHERE seller_id=" + fixture.seller().id()
                        + " AND currency=" + "'" + fixture.request().path("currency").asText() + "'");
    }

    private BigDecimal decimal(String database, String query) throws SQLException {
        try (Connection connection = postgres.superuser(database); var statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(query)) {
            assertTrue(rows.next());
            return rows.getBigDecimal(1);
        }
    }

    private long conflicts(String id) throws SQLException {
        return scalar(home(), "SELECT count(*) FROM ordering.conflicting_results WHERE order_id=" + id);
    }

    private long orphans(String id) throws SQLException {
        return scalar(home(), "SELECT count(*) FROM ordering.orphan_results WHERE order_id=" + id);
    }

    private long scalar(CatalogShards.Shard shard, String query) throws SQLException {
        return scalar(shard.database(), query);
    }

    private long scalar(String database, String query) throws SQLException {
        try (Connection connection = postgres.superuser(database); var statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(query)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private CatalogShards.Shard home() {
        return shards.ofSeller(buyer);
    }

    private String path(String id) {
        return "/api/v1/buyers/" + buyer + "/orders/" + id;
    }

    private RawHttp.Response http(String method, String target, String key, JsonNode payload) throws Exception {
        var headers = new ArrayList<RawHttp.Header>();
        if (key != null) headers.add(new RawHttp.Header("Idempotency-Key", key));
        if (payload != null) headers.add(new RawHttp.Header("Content-Type", "application/json"));
        RawHttp.Response response = RawHttp.exchange(order.port(), method, target, headers,
                payload == null ? new byte[0] : Json.bytes(payload),
                payload == null ? RawHttp.Framing.NONE : RawHttp.Framing.CONTENT_LENGTH);
        Contracts.ORDER_API.responseSchema(method, target, response.status()).ifPresent(pointer ->
                assertEquals(List.of(), Contracts.ORDER_API.errors(pointer, Json.parse(response.body()))));
        return response;
    }

    private static JsonNode body(RawHttp.Response response, int expected) {
        assertEquals(expected, response.status(), response::text);
        return Json.parse(response.body());
    }

    @FunctionalInterface
    private interface Condition {
        boolean met() throws Exception;
    }

    private static void await(String description, Condition condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        do {
            if (condition.met()) return;
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        fail(description + " within 45 seconds");
    }
}
