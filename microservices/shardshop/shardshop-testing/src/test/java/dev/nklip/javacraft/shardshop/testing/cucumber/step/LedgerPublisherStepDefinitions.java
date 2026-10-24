package dev.nklip.javacraft.shardshop.testing.cucumber.step;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.GetResponse;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.CatalogDatabase;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.CrashBreakpoint;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.Json;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.RabbitMqBroker;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.ShardShopFiles;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.Contracts;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.LedgerRuntime;
import io.cucumber.java.After;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;

import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Packaged publisher acceptance through the production ledger login and broker topology. */
public final class LedgerPublisherStepDefinitions {
    private static final String COMMAND_EXCHANGE = "ledger.commands";
    private static final String COMMAND_ROUTE = "ledger.record-order";
    private static final String RESULT_EXCHANGE = "order.saga-results";
    private static final String RESULT_QUEUE = "order.ledger-results";
    private static final Duration DEADLINE = Duration.ofSeconds(35);

    private final CatalogDatabase postgres = CatalogDatabase.instance();
    private final RabbitMqBroker broker;
    private final LedgerRuntime runtime;
    private String database;

    public LedgerPublisherStepDefinitions(RabbitMqBroker broker, LedgerRuntime runtime) {
        this.broker = broker;
        this.runtime = runtime;
    }

    @Given("a migrated ledger database and broker for result publication")
    public void setup() throws Exception {
        database = postgres.createLedgerDatabase();
        postgres.migrateLedger(database, ShardShopFiles.resolve("database/ledger/ledger"));
        broker.start();
    }

    @After("@ledger-publisher")
    public void cleanup() throws Exception {
        runtime.stop();
        if (database != null) postgres.dropDatabase(database);
    }

    @Then("recorded and rejected results reach the order queue and are marked published")
    public void recordedAndRejected() throws Exception {
        ObjectNode recorded = command(100, "USD");
        ObjectNode rejected = command(101, "EUR");
        runtime.startWithPublisher(database, broker, "USD");
        publish(recorded);
        publish(rejected);
        GetResponse first = delivery();
        GetResponse second = delivery();
        if (recorded.path("resultMessageId").equals(Json.parse(first.getBody()).path("messageId"))) {
            assertDelivery(recorded, "RECORDED", first);
            assertDelivery(rejected, "REJECTED", second);
        } else {
            assertDelivery(rejected, "REJECTED", first);
            assertDelivery(recorded, "RECORDED", second);
        }
        awaitPublished(recorded);
        awaitPublished(rejected);
        assertEquals(1, count("ledger.ledger_entries"));
        assertEquals(2, count("ledger.ledger_operations"));
    }

    @Then("a pending result is published after the ledger restarts")
    public void pendingAfterRestart() throws Exception {
        ObjectNode command = command(102, "USD");
        runtime.start(database, broker, "USD");
        publish(command);
        await("durable pending result", () -> pending(command) && count("ledger.ledger_operations") == 1);
        runtime.stop();
        runtime.checkStartupWithPublisher(database, broker, "USD");
        assertTrue(pending(command), "Startup validation must leave the result pending");
        assertEquals(0, queueCount(), "Startup validation must not publish the queued result");
        runtime.startWithPublisher(database, broker, "USD");
        assertDelivery(command, "RECORDED", delivery());
        awaitPublished(command);
    }

    @Then("a killed publisher after broker confirmation reuses its saved result")
    public void crashAfterResultConfirm() throws Exception {
        ObjectNode command = command(902, "USD");
        runtime.start(database, broker, "USD");
        publish(command);
        await("committed pending result", () -> pending(command));
        runtime.stop();
        var crash = CrashBreakpoint.atSourceLine("shardshop-ledger",
                "dev.nklip.javacraft.shardshop.ledger.result.LedgerResultRelay", "run", 67,
                "if (closed.get())");
        try (crash) {
            runtime.crashAt(crash);
            runtime.startWithPublisher(database, broker, "USD");
            GetResponse first = delivery();
            assertDelivery(command, "RECORDED", first);
            runtime.killAt(crash, DEADLINE);
            assertTrue(pending(command), "A broker confirm alone cannot clear the pending outbox");
            runtime.restartWithPublisher("USD");
            GetResponse second = delivery();
            assertArrayEquals(first.getBody(), second.getBody(), "The original result identity and bytes must replay");
            awaitPublished(command);
            assertEquals(1, count("ledger.ledger_operations"));
            assertEquals(1, count("ledger.ledger_entries"));
        }
    }

    @Then("replayed and reconciliation results retain their exact saved decisions")
    public void replayAndReconciliation() throws Exception {
        ObjectNode command = command(103, "USD");
        runtime.startWithPublisher(database, broker, "USD");
        publish(command);
        GetResponse first = delivery();
        assertDelivery(command, "RECORDED", first);
        awaitPublished(command);

        publish(command);
        await("second publication attempt", () -> attempt(command) == 2);
        GetResponse second = delivery();
        assertArrayEquals(first.getBody(), second.getBody(), "Same result ID must replay the saved bytes");
        awaitPublished(command);

        try (var connection = postgres.superuser(database); var statement = connection.createStatement()) {
            statement.execute("DELETE FROM ledger.ledger_inbox");
            statement.execute("DELETE FROM ledger.ledger_outbox");
        }
        runtime.restartWithPublisher("EUR");
        publish(command);
        await("outbox recreated after cleanup", () -> attempt(command) == 3);
        GetResponse afterCleanup = delivery();
        assertArrayEquals(first.getBody(), afterCleanup.getBody(), "Cleanup and policy change cannot alter the envelope");
        awaitPublished(command);

        ObjectNode reconciliation = command.deepCopy();
        reconciliation.put("messageId", Long.toString(id(command, "messageId") + 10_000));
        reconciliation.put("resultMessageId", Long.toString(id(command, "resultMessageId") + 10_000));
        reconciliation.put("occurredAt", "2026-10-06T12:03:00Z");
        publish(reconciliation);
        GetResponse reconciled = delivery();
        assertDelivery(reconciliation, "RECORDED", reconciled);
        JsonNode original = Json.parse(first.getBody());
        JsonNode newer = Json.parse(reconciled.getBody());
        assertEquals(original.get("decidedAt"), newer.get("decidedAt"));
        assertEquals(original.get("outcome"), newer.get("outcome"));
        assertEquals(1, count("ledger.ledger_entries"));
        assertEquals(1, count("ledger.ledger_operations"));
        awaitPublished(reconciliation);
    }

    @Then("a missing order result binding preserves the pending result until recovery")
    public void missingBinding() throws Exception {
        ObjectNode command = command(104, "USD");
        unbind();
        try {
            runtime.startWithPublisher(database, broker, "USD");
            publish(command);
            await("unroutable publication retry", () -> retryLogged() && pending(command));
            assertEquals(0, queueCount(), "Mandatory return cannot silently mark an unroutable result");
        } finally {
            bind();
        }
        assertDelivery(command, "RECORDED", delivery());
        awaitPublished(command);
    }

    @Then("the publisher reconnects and confirms a later result")
    public void reconnect() throws Exception {
        ObjectNode first = command(105, "USD");
        ObjectNode second = command(106, "USD");
        runtime.startWithPublisher(database, broker, "USD");
        publish(first);
        assertDelivery(first, "RECORDED", delivery());
        awaitPublished(first);
        broker.disconnectLedgerConsumers(2);
        publish(second);
        assertDelivery(second, "RECORDED", delivery());
        awaitPublished(second);
        // A stream that the broker closed while idle reconnects before the next result, without a failed attempt.
        assertTrue(runtime.lines().stream().noneMatch(line -> line.contains("Ledger result publisher retry")),
                "The closed idle stream failed a publication:\n" + runtime.tail());
        assertEquals(2, runtime.lines().stream()
                .filter(line -> line.contains(" INFO ") && line.contains("RabbitMQ result publisher connected to "
                        + RESULT_EXCHANGE)).count(), "One INFO line for each publisher connection:\n" + runtime.tail());
    }

    @Then("an older publisher confirmation cannot clear a newer pending attempt")
    public void staleConfirmation() throws Exception {
        ObjectNode command = command(107, "USD");
        long resultId = id(command, "resultMessageId");
        runtime.start(database, broker, "USD");
        publish(command);
        await("first pending result", () -> pending(command));
        runtime.stop();

        try (var holder = postgres.superuser(database)) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement(
                    "SELECT message_id FROM ledger.ledger_outbox WHERE message_id=? FOR UPDATE")) {
                lock.setLong(1, resultId);
                try (ResultSet row = lock.executeQuery()) {
                    assertTrue(row.next());
                }
            }
            runtime.startWithPublisher(database, broker, "USD");
            assertDelivery(command, "RECORDED", delivery());
            await("publisher waiting to mark the confirmed attempt", this::blockedPublicationUpdate);
            unbind();
            try (PreparedStatement binding = holder.prepareStatement(
                    "UPDATE ledger.ledger_result_ids SET publication_attempt=2 WHERE result_message_id=?");
                 PreparedStatement outbox = holder.prepareStatement(
                         "UPDATE ledger.ledger_outbox SET publication_attempt=2, published_at=NULL WHERE message_id=?")) {
                binding.setLong(1, resultId);
                outbox.setLong(1, resultId);
                assertEquals(1, binding.executeUpdate());
                assertEquals(1, outbox.executeUpdate());
            }
            holder.commit();
        }
        await("new attempt to fail while unroutable", () -> retryLogged() && !blockedPublicationUpdate());
        assertTrue(runtime.lines().stream().noneMatch(line -> line.contains("Ledger result publication mark unavailable")),
                "The stale mark must finish by its attempt fence, not a database timeout: " + runtime.tail());
        assertEquals(2, attempt(command));
        assertTrue(pending(command), "A stale confirm must not mark attempt two");
        bind();
        assertDelivery(command, "RECORDED", delivery());
        awaitPublished(command);
    }

    @Then("a blocked cleanup delete cannot remove a rearmed ledger result")
    public void cleanupRacesRearm() throws Exception {
        ObjectNode command = command(108, "USD");
        long resultId = id(command, "resultMessageId");
        runtime.start(database, broker, "USD");
        publish(command);
        await("initial pending result", () -> pending(command));
        JsonNode savedEnvelope = saved(command);
        runtime.stop();
        try (var connection = postgres.superuser(database);
             PreparedStatement ageInbox = connection.prepareStatement(
                     "UPDATE ledger.ledger_inbox SET received_at=clock_timestamp()-INTERVAL '2 hours' WHERE message_id=?");
             PreparedStatement ageOutbox = connection.prepareStatement(
                     "UPDATE ledger.ledger_outbox SET created_at=clock_timestamp()-INTERVAL '3 hours', "
                             + "published_at=clock_timestamp()-INTERVAL '2 hours' WHERE message_id=?")) {
            ageInbox.setLong(1, id(command, "messageId"));
            ageOutbox.setLong(1, resultId);
            assertEquals(1, ageInbox.executeUpdate());
            assertEquals(1, ageOutbox.executeUpdate());
        }

        try (var holder = postgres.superuser(database)) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement(
                    "SELECT message_id FROM ledger.ledger_outbox WHERE message_id=? FOR UPDATE")) {
                lock.setLong(1, resultId);
                try (ResultSet rows = lock.executeQuery()) {
                    assertTrue(rows.next());
                }
            }
            runtime.enableCleanup();
            runtime.start(database, broker, "USD");
            await("packaged cleanup DELETE waits for the held outbox row", this::blockedCleanupDelete);
            try (PreparedStatement binding = holder.prepareStatement(
                    "UPDATE ledger.ledger_result_ids SET publication_attempt=2 WHERE result_message_id=?");
                 PreparedStatement rearm = holder.prepareStatement(
                         "UPDATE ledger.ledger_outbox SET publication_attempt=2, published_at=NULL WHERE message_id=?")) {
                binding.setLong(1, resultId);
                rearm.setLong(1, resultId);
                assertEquals(1, binding.executeUpdate());
                assertEquals(1, rearm.executeUpdate());
            }
            holder.commit();
        }
        await("cleanup finishes and removes the expired inbox", () ->
                count("ledger.ledger_inbox") == 0 && !blockedCleanupDelete());
        assertEquals(1, count("ledger.ledger_outbox"), "The DELETE must recheck a concurrently rearmed row");
        assertEquals("2", value("SELECT publication_attempt::text FROM ledger.ledger_outbox WHERE message_id=?", resultId));
        assertTrue(pending(command), "A newer publication attempt stays pending");
        assertEquals(savedEnvelope, saved(command));
        publish(command);
        await("a real duplicate reuses the surviving result", () -> attempt(command) == 3);
        assertEquals(savedEnvelope, saved(command));
        assertTrue(pending(command));
        assertEquals(1, count("ledger.ledger_operations"));
        assertEquals(1, count("ledger.ledger_entries"));
    }

    private static ObjectNode command(int ordinal, String currency) throws Exception {
        return LedgerConsumerStepDefinitions.command(ordinal, currency);
    }

    private void publish(ObjectNode command) throws Exception {
        try (Connection connection = broker.connect("order"); Channel channel = connection.createChannel()) {
            channel.confirmSelect();
            channel.basicPublish(COMMAND_EXCHANGE, COMMAND_ROUTE, true,
                    new AMQP.BasicProperties.Builder().contentType("application/json").deliveryMode(2).build(),
                    Json.bytes(command));
            channel.waitForConfirmsOrDie(5_000);
        }
    }

    private GetResponse delivery() throws Exception {
        try (Connection connection = broker.connect("admin"); Channel channel = connection.createChannel()) {
            long deadline = System.nanoTime() + DEADLINE.toNanos();
            while (System.nanoTime() < deadline) {
                GetResponse result = channel.basicGet(RESULT_QUEUE, false);
                if (result != null) {
                    channel.basicAck(result.getEnvelope().getDeliveryTag(), false);
                    return result;
                }
                assertTrue(runtime.isAlive(), runtime.tail());
                Thread.sleep(50);
            }
        }
        fail("No order result delivery within " + DEADLINE + ":\n" + runtime.tail());
        return null;
    }

    private void assertDelivery(ObjectNode command, String outcome, GetResponse delivery) throws Exception {
        assertEquals(2, delivery.getProps().getDeliveryMode(), "Result must be persistent");
        assertEquals("application/json", delivery.getProps().getContentType());
        assertEquals(command.path("resultMessageId").asText(), delivery.getProps().getMessageId());
        JsonNode result = Json.parse(delivery.getBody());
        assertEquals(List.of(), Contracts.LEDGER_RESULT.errors("#", result));
        assertEquals(outcome.equals("RECORDED") ? "LedgerRecorded" : "LedgerRejected", result.path("type").asText());
        assertEquals(outcome, result.path("outcome").asText());
        assertEquals(command.path("resultMessageId"), result.path("messageId"));
        for (String field : List.of("commandId", "sagaId", "orderId", "buyerId", "requestFingerprint")) {
            assertEquals(command.path(field), result.path(field), field);
        }
        assertEquals(command.at("/snapshot/amount"), result.path("amount"));
        assertEquals(command.at("/snapshot/currency"), result.path("currency"));
        if (outcome.equals("REJECTED")) assertEquals("UNSUPPORTED_CURRENCY", result.path("rejectionReason").asText());
        assertEquals(saved(command), result, "Published envelope must equal the retained decision");
    }

    private JsonNode saved(ObjectNode command) throws Exception {
        String text = value("SELECT envelope::text FROM ledger.ledger_result_ids WHERE result_message_id=?",
                id(command, "resultMessageId"));
        assertNotNull(text);
        return Json.parse(text.getBytes(StandardCharsets.UTF_8));
    }

    private void awaitPublished(ObjectNode command) throws Exception {
        await("confirmed result " + command.path("resultMessageId").asText(),
                () -> value("SELECT published_at::text FROM ledger.ledger_outbox WHERE message_id=?",
                        id(command, "resultMessageId")) != null);
    }

    private boolean pending(ObjectNode command) throws Exception {
        return "pending".equals(value("SELECT CASE WHEN published_at IS NULL THEN 'pending' ELSE 'published' END "
                + "FROM ledger.ledger_outbox WHERE message_id=?", id(command, "resultMessageId")));
    }

    private long attempt(ObjectNode command) throws Exception {
        String text = value("SELECT publication_attempt::text FROM ledger.ledger_result_ids WHERE result_message_id=?",
                id(command, "resultMessageId"));
        return text == null ? 0 : Long.parseLong(text);
    }

    private boolean blockedPublicationUpdate() throws Exception {
        try (var connection = postgres.superuser(database);
             PreparedStatement query = connection.prepareStatement("SELECT count(*) FROM pg_stat_activity "
                     + "WHERE datname=? AND usename='ledger_app' AND wait_event_type='Lock' "
                     + "AND query ILIKE '%ledger_outbox%' AND query ILIKE 'UPDATE%'") ) {
            query.setString(1, database);
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                return rows.getInt(1) > 0;
            }
        }
    }

    private boolean blockedCleanupDelete() throws Exception {
        try (var connection = postgres.superuser(database);
             PreparedStatement query = connection.prepareStatement("SELECT count(*) FROM pg_stat_activity "
                     + "WHERE datname=? AND usename='ledger_app' AND wait_event_type='Lock' "
                     + "AND query ILIKE '%ledger_outbox%' AND query ILIKE 'WITH expired%'") ) {
            query.setString(1, database);
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                return rows.getInt(1) > 0;
            }
        }
    }

    private String value(String sql, long parameter) throws Exception {
        try (var connection = postgres.superuser(database); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, parameter);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private int count(String table) throws Exception {
        try (var connection = postgres.superuser(database); var statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private int queueCount() throws Exception {
        try (Connection connection = broker.connect("admin"); Channel channel = connection.createChannel()) {
            return channel.queueDeclarePassive(RESULT_QUEUE).getMessageCount();
        }
    }

    private void unbind() throws Exception {
        try (Connection connection = broker.connect("admin"); Channel channel = connection.createChannel()) {
            channel.queueUnbind(RESULT_QUEUE, RESULT_EXCHANGE, RESULT_QUEUE);
        }
    }

    private void bind() throws Exception {
        try (Connection connection = broker.connect("admin"); Channel channel = connection.createChannel()) {
            channel.queueBind(RESULT_QUEUE, RESULT_EXCHANGE, RESULT_QUEUE);
        }
    }

    private boolean retryLogged() {
        return runtime.lines().stream().anyMatch(line -> line.contains("Ledger result publisher retry")
                && line.toLowerCase().contains("result") && (line.toLowerCase().contains("unroutable")
                || line.contains("NO_ROUTE")));
    }

    private static long id(ObjectNode command, String field) {
        return Long.parseLong(command.path(field).asText());
    }

    private void await(String requirement, Check check) throws Exception {
        long deadline = System.nanoTime() + DEADLINE.toNanos();
        while (System.nanoTime() < deadline) {
            if (check.satisfied()) return;
            assertTrue(runtime.isAlive(), "Ledger exited before " + requirement + ":\n" + runtime.tail());
            Thread.sleep(50);
        }
        fail("Ledger did not reach " + requirement + " within " + DEADLINE + ":\n" + runtime.tail());
    }

    @FunctionalInterface private interface Check { boolean satisfied() throws Exception; }
}
