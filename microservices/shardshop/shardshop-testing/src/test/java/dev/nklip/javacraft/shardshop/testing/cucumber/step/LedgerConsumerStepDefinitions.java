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
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The ledger parts of scenarios 5, 7 and 10: broker deliveries into the packaged ledger, observed only through SQL,
 * broker state and the ledger log.
 */
public final class LedgerConsumerStepDefinitions {
    private static final String SOURCE = "ledger.record-order";
    private static final String PARKING = "ledger.record-order.dlq";
    private static final long BASE = 100925016375300096L;
    private final CatalogDatabase postgres = CatalogDatabase.instance();
    private final RabbitMqBroker broker;
    private final LedgerRuntime runtime;
    private LedgerRuntime competitor;
    private String database;

    public LedgerConsumerStepDefinitions(RabbitMqBroker broker, LedgerRuntime runtime) {
        this.broker = broker;
        this.runtime = runtime;
    }

    @Given("a migrated disposable ledger database and preprovisioned command broker")
    public void setup() throws Exception {
        database = postgres.createLedgerDatabase();
        postgres.migrateLedger(database, ShardShopFiles.resolve("database/ledger/ledger"));
        broker.start();
    }

    @After("@ledger-consumer")
    public void cleanup() throws Exception {
        if (competitor != null) competitor.stop();
        runtime.stop();
        if (database != null) postgres.dropDatabase(database);
    }

    @Then("startup validation exits without running the command consumer")
    public void startupCheck() throws Exception {
        ObjectNode command = command(13, "USD");
        publish(command);
        runtime.checkStartup(database, broker, "USD");
        try (Connection connection = broker.connect("admin"); Channel channel = connection.createChannel()) {
            assertEquals(1, channel.queueDeclarePassive(SOURCE).getMessageCount(),
                    "Startup validation must not consume the queued command");
        }
        assertEquals(0, count("ledger.ledger_operations"));
        runtime.start(database, broker, "USD");
        await("pending command after normal start", () -> count("ledger.ledger_operations") == 1);
        assertResult(command, result(command), "RECORDED");
        awaitQueueEmpty();
        runtime.stop();
        String log = String.join("\n", runtime.lines());
        assertFalse(log.contains("RejectedExecutionException"), "Normal shutdown must drain RabbitMQ callbacks");
        assertFalse(log.contains("unexpected connection driver error"), "Normal shutdown must close RabbitMQ cleanly");
    }

    @Then("the consumer reconnects and processes a new command after connection loss")
    public void brokerReconnect() throws Exception {
        ObjectNode first = command(14, "USD");
        ObjectNode second = command(15, "USD");
        runtime.start(database, broker, "USD");
        publish(first);
        await("first broker delivery", () -> count("ledger.ledger_operations") == 1);
        awaitQueueEmpty();
        broker.disconnectLedgerConsumers();
        publish(second);
        await("delivery after broker connection loss", () -> count("ledger.ledger_operations") == 2);
        assertEquals(2, count("ledger.ledger_entries"));
        assertResult(first, result(first), "RECORDED");
        assertResult(second, result(second), "RECORDED");
        awaitQueueEmpty();
        List<String> restarts = warnings("Ledger command consumer stopped; retrying in ");
        assertEquals(1, restarts.size(), runtime.tail());
        // The first retry after a working connection waits 500 ms with 25% jitter.
        var delay = Pattern.compile("retrying in (\\d+) ms: ").matcher(restarts.getFirst());
        assertTrue(delay.find(), restarts.getFirst());
        long millis = Long.parseLong(delay.group(1));
        assertTrue(millis >= 375 && millis <= 625, restarts.getFirst());
        assertTrue(restarts.getFirst().contains("CONNECTION_FORCED"), "The restart names the broker's reason");
    }

    @Then("a valid USD command records exactly one complete result")
    public void recorded() throws Exception {
        ObjectNode command = command(0, "USD");
        runtime.start(database, broker, "USD");
        publish(command);
        await("recorded decision", () -> count("ledger.ledger_operations") == 1);
        assertEquals(1, count("ledger.ledger_entries"));
        assertEquals(1, count("ledger.ledger_result_ids"));
        assertEquals(1, count("ledger.ledger_inbox"));
        assertEquals(1, count("ledger.ledger_outbox"));
        assertEquals(0, count("ledger.conflicting_commands"));
        assertEquals("RECORDED", value("SELECT outcome FROM ledger.ledger_operations WHERE order_id=?", id(command, "orderId")));
        assertEquals("39.90", value("SELECT amount::text FROM ledger.ledger_entries WHERE order_id=?", id(command, "orderId")));
        assertEquals("USD", value("SELECT currency FROM ledger.ledger_entries WHERE order_id=?", id(command, "orderId")));
        assertEquals(command, document("SELECT command_envelope::text FROM ledger.ledger_result_ids WHERE message_id=?",
                id(command, "messageId")));
        assertEquals(command, document("SELECT envelope::text FROM ledger.ledger_inbox WHERE message_id=?",
                id(command, "messageId")));
        JsonNode result = result(command);
        assertEquals(List.of(), Contracts.LEDGER_RESULT.errors("#", result));
        assertResult(command, result, "RECORDED");
        assertEquals("1", value("SELECT publication_attempt::text FROM ledger.ledger_outbox WHERE message_id=?",
                id(command, "resultMessageId")));
        assertNull(value("SELECT published_at::text FROM ledger.ledger_outbox WHERE message_id=?",
                id(command, "resultMessageId")));
        awaitQueueEmpty();
    }

    @Then("a killed ledger consumer before database commit redelivers its command")
    public void crashBeforeCommit() throws Exception {
        ObjectNode command = command(900, "USD");
        // This session method runs only inside the command's still-open write transaction.
        // A generic DatabasePhases breakpoint could stop a metrics or startup query instead.
        var crash = CrashBreakpoint.atSourceAnchor("shardshop-ledger",
                "dev.nklip.javacraft.shardshop.ledger.persistence.JdbcLedgerStore$JdbcSession",
                "insertDecision", "JdbcLedgerStore.insertDecision(connection, command, decision);",
                "return null;", "});", "}");
        try (crash) {
            runtime.crashAt(crash);
            runtime.start(database, broker, "USD");
            publish(command);
            runtime.awaitCrash(crash, Duration.ofSeconds(30));
            await("the suspended command is broker-owned", () ->
                    broker.queue(SOURCE).path("messages_unacknowledged").asInt(-1) == 1);
            assertEquals(0, count("ledger.ledger_operations"), "The decision is not visible before commit");
            long redeliveries = broker.queue(SOURCE).path("message_stats").path("redeliver").asLong(0);
            runtime.killAt(crash, Duration.ofSeconds(30));
            assertEquals(0, count("ledger.ledger_operations"), "The stopped transaction must roll back");
            assertEquals(0, count("ledger.ledger_entries"));
            runtime.restart("USD");
            await("redelivered command commits", () -> count("ledger.ledger_operations") == 1);
            await("broker reports redelivery of the killed transaction", () ->
                    broker.queue(SOURCE).path("message_stats").path("redeliver").asLong(0) > redeliveries);
            assertEquals(1, count("ledger.ledger_entries"));
            assertResult(command, result(command), "RECORDED");
            awaitQueueEmpty();
        }
    }

    @Then("a killed ledger consumer after database commit but before acknowledgement reuses its decision")
    public void crashBeforeAck() throws Exception {
        ObjectNode command = command(901, "USD");
        var crash = CrashBreakpoint.atSourceAnchor("shardshop-core/common",
                "dev.nklip.javacraft.shardshop.common.amqp.QueueConsumer", "settle",
                "channel.basicAck(deliveryTag, false);");
        try (crash) {
            runtime.crashAt(crash);
            runtime.start(database, broker, "USD");
            publish(command);
            runtime.killAt(crash, Duration.ofSeconds(30));
            assertEquals(1, count("ledger.ledger_operations"), "The decision committed before acknowledgement");
            assertEquals(1, count("ledger.ledger_entries"));
            JsonNode first = result(command);
            runtime.restart("USD");
            await("redelivered command rearms the saved result", () -> attempt(command) == 2);
            awaitQueueEmpty();
            assertEquals(1, count("ledger.ledger_operations"));
            assertEquals(1, count("ledger.ledger_entries"));
            assertEquals(first, result(command), "Redelivery must retain the exact result identity and decision");
            assertEquals(2, attempt(command), "An identical redelivery rearms the saved result once");
        }
    }

    @Then("a killed ledger consumer after acknowledgement does not reprocess its command")
    public void crashAfterAck() throws Exception {
        ObjectNode command = command(902, "USD");
        // The method return follows the acknowledgement and its enclosing blocks.
        // A one-shot breakpoint avoids class-wide method-exit suspension during startup.
        var crash = CrashBreakpoint.atSourceAnchor("shardshop-core/common",
                "dev.nklip.javacraft.shardshop.common.amqp.QueueConsumer", "settle",
                "channel.basicAck(deliveryTag, false);", "}", "}", "}");
        try (crash) {
            runtime.crashAt(crash);
            runtime.start(database, broker, "USD");
            publish(command);
            runtime.awaitCrash(crash, Duration.ofSeconds(30));
            await("broker has accepted the acknowledgement", () ->
                    broker.queue(SOURCE).path("messages").asInt(-1) == 0
                            && broker.queue(SOURCE).path("messages_unacknowledged").asInt(-1) == 0);
            assertEquals(1, count("ledger.ledger_operations"));
            assertEquals(1, count("ledger.ledger_entries"));
            assertEquals(1, attempt(command));
            JsonNode original = result(command);
            runtime.killAt(crash, Duration.ofSeconds(30));
            runtime.restart("USD");
            await("restarted ledger consumer registers", () ->
                    broker.queue(SOURCE).path("consumers").asInt(-1) == 1);
            awaitQueueEmpty();
            assertEquals(1, count("ledger.ledger_operations"));
            assertEquals(1, count("ledger.ledger_entries"));
            assertEquals(1, attempt(command), "An acknowledged command must not be redelivered");
            assertEquals(original, result(command));
        }
    }

    @Then("an unsupported EUR command saves a permanent rejection without an entry")
    public void rejected() throws Exception {
        ObjectNode command = command(1, "EUR");
        runtime.start(database, broker, "USD");
        publish(command);
        await("rejected decision", () -> count("ledger.ledger_operations") == 1);
        assertEquals(0, count("ledger.ledger_entries"));
        assertEquals("REJECTED", value("SELECT outcome FROM ledger.ledger_operations WHERE order_id=?", id(command, "orderId")));
        assertEquals("UNSUPPORTED_CURRENCY", value(
                "SELECT rejection_reason FROM ledger.ledger_operations WHERE order_id=?", id(command, "orderId")));
        JsonNode rejection = result(command);
        assertResult(command, rejection, "REJECTED");
        assertEquals(1, count("ledger.ledger_result_ids"));
        assertEquals(1, count("ledger.ledger_inbox"));
        assertEquals(1, count("ledger.ledger_outbox"));
        awaitQueueEmpty();
        try (var connection = postgres.superuser(database); var statement = connection.createStatement()) {
            statement.execute("DELETE FROM ledger.ledger_inbox");
            statement.execute("DELETE FROM ledger.ledger_outbox");
        }
        runtime.restart("USD,EUR");
        publish(command);
        await("replayed rejection after policy expansion", () -> attempt(command) == 2);
        assertEquals(rejection, result(command));
        assertEquals(0, count("ledger.ledger_entries"));
        assertEquals(1, count("ledger.ledger_operations"));
        assertEquals(1, count("ledger.ledger_outbox"));
        awaitQueueEmpty();
    }

    @Then("an identical command replays its exact result after publication, cleanup and restart")
    public void duplicateAfterCleanup() throws Exception {
        ObjectNode command = command(2, "USD");
        long resultId = id(command, "resultMessageId");
        runtime.start(database, broker, "USD");
        publish(command);
        await("initial result", () -> count("ledger.ledger_result_ids") == 1);
        JsonNode original = result(command);
        String decidedAt = value("SELECT decided_at::text FROM ledger.ledger_operations WHERE order_id=?", id(command, "orderId"));
        publish(command);
        await("rearmed duplicate", () -> attempt(command) == 2);
        assertEquals(original, result(command));
        assertEquals(1, count("ledger.ledger_entries"));
        awaitQueueEmpty();
        try (var connection = postgres.superuser(database); var statement = connection.createStatement()) {
            // The step 4.6 publisher confirms a result like this; the retained row is not cleaned yet.
            assertEquals(1, statement.executeUpdate("UPDATE ledger.ledger_outbox SET published_at = clock_timestamp() "
                    + "WHERE message_id = " + resultId));
        }
        publish(command);
        await("rearmed published result", () -> attempt(command) == 3);
        assertEquals("3", value("SELECT publication_attempt::text FROM ledger.ledger_outbox WHERE message_id=?", resultId));
        assertNull(value("SELECT published_at::text FROM ledger.ledger_outbox WHERE message_id=?", resultId),
                "A duplicate after publication must request the publication again");
        assertEquals(original, result(command));
        awaitQueueEmpty();
        try (var connection = postgres.superuser(database); var statement = connection.createStatement()) {
            statement.execute("DELETE FROM ledger.ledger_inbox");
            statement.execute("DELETE FROM ledger.ledger_outbox");
        }
        runtime.restart("EUR");
        publish(command);
        await("recreated outbox", () -> attempt(command) == 4 && count("ledger.ledger_outbox") == 1);
        assertEquals(original, result(command), "Policy changes cannot replace a stored decision or timestamp");
        assertEquals(decidedAt, value("SELECT decided_at::text FROM ledger.ledger_operations WHERE order_id=?",
                id(command, "orderId")));
        assertEquals(1, count("ledger.ledger_entries"));
        assertEquals(1, count("ledger.ledger_operations"));
        awaitQueueEmpty();
    }

    @Then("a reconciliation command retains the original decision and timestamp")
    public void reconciliation() throws Exception {
        ObjectNode first = command(3, "USD");
        runtime.start(database, broker, "USD");
        publish(first);
        await("first result", () -> count("ledger.ledger_result_ids") == 1);
        ObjectNode second = first.deepCopy();
        second.put("messageId", Long.toString(BASE + 90));
        second.put("resultMessageId", Long.toString(BASE + 91));
        second.put("occurredAt", "2026-10-06T12:03:00Z");
        publish(second);
        await("reconciliation binding", () -> count("ledger.ledger_result_ids") == 2);
        assertEquals(1, count("ledger.ledger_operations"));
        assertEquals(1, count("ledger.ledger_entries"));
        assertEquals(2, count("ledger.ledger_outbox"));
        assertEquals(first, document("SELECT command_envelope::text FROM ledger.ledger_result_ids WHERE message_id=?",
                id(first, "messageId")));
        assertEquals(second, document("SELECT command_envelope::text FROM ledger.ledger_result_ids WHERE message_id=?",
                id(second, "messageId")));
        JsonNode initial = result(first);
        JsonNode replay = result(second);
        assertResult(second, replay, "RECORDED");
        assertEquals(initial.get("decidedAt"), replay.get("decidedAt"));
        assertFalse(Instant.parse(replay.get("occurredAt").asText())
                .isBefore(Instant.parse(replay.get("decidedAt").asText())));
        awaitQueueEmpty();
    }

    @Then("distinct conflicts remain quarantined and repeated deliveries increment their counts")
    public void conflicts() throws Exception {
        ObjectNode original = command(4, "USD");
        runtime.start(database, broker, "USD");
        publish(original);
        await("decision", () -> count("ledger.ledger_operations") == 1);
        JsonNode result = result(original);
        long initialAttempt = attempt(original);
        ObjectNode changedSnapshot = original.deepCopy();
        ((ObjectNode) changedSnapshot.at("/snapshot/items/0")).put("productName", "Changed product");
        publish(changedSnapshot);
        await("snapshot conflict", () -> count("ledger.conflicting_commands") == 1);
        publish(changedSnapshot);
        await("repeat conflict", () -> Long.parseLong(value(
                "SELECT delivery_count::text FROM ledger.conflicting_commands WHERE message_id=?", id(original, "messageId"))) == 2);
        ObjectNode changedTime = original.deepCopy();
        changedTime.put("occurredAt", "2026-10-06T12:01:00Z");
        try (var connection = postgres.superuser(database); var statement = connection.createStatement()) {
            statement.execute("DELETE FROM ledger.ledger_inbox");
            statement.execute("DELETE FROM ledger.ledger_outbox");
        }
        publish(changedTime);
        await("transport conflict after cleanup", () -> count("ledger.conflicting_commands") == 2);
        assertEquals("2", value("SELECT count(DISTINCT payload_fingerprint)::text "
                + "FROM ledger.conflicting_commands WHERE message_id=?", id(original, "messageId")));
        JsonNode expected = document("SELECT expected_identity::text FROM ledger.conflicting_commands "
                + "WHERE message_id=? ORDER BY first_seen_at LIMIT 1", id(original, "messageId"));
        assertEquals(original.get("orderId"), expected.at("/operations/0/orderId"));
        assertEquals(original.get("resultMessageId"), expected.at("/bindings/0/resultMessageId"));
        ObjectNode changedLogical = original.deepCopy();
        changedLogical.put("messageId", Long.toString(BASE + 93));
        changedLogical.put("resultMessageId", Long.toString(BASE + 94));
        changedLogical.put("commandId", Long.toString(BASE + 95));
        publish(changedLogical);
        await("logical conflict", () -> count("ledger.conflicting_commands") == 3);
        // The fingerprint excludes product names, thus only the snapshot comparison sees this reconciliation conflict.
        ObjectNode changedSnapshotPair = changedSnapshot.deepCopy();
        changedSnapshotPair.put("messageId", Long.toString(BASE + 96));
        changedSnapshotPair.put("resultMessageId", Long.toString(BASE + 97));
        publish(changedSnapshotPair);
        await("snapshot conflict on a new transport pair", () -> count("ledger.conflicting_commands") == 4);
        assertEquals("BUSINESS_IDENTITY_CONFLICT",
                value("SELECT reason FROM ledger.conflicting_commands WHERE message_id=?", BASE + 96));
        assertEquals(1, count("ledger.ledger_operations"));
        assertEquals(1, count("ledger.ledger_entries"));
        assertEquals(1, count("ledger.ledger_result_ids"));
        assertEquals(4, count("ledger.conflicting_commands WHERE resolved_at IS NULL"));
        assertEquals(initialAttempt, attempt(original));
        assertEquals(result, result(original));
        assertEquals(0, count("ledger.ledger_outbox"));
        awaitQueueEmpty();
        long order = id(original, "orderId");
        long message = id(original, "messageId");
        assertEquals(List.of(quarantined(message, order, "BUSINESS_IDENTITY_CONFLICT"),
                quarantined(message, order, "BUSINESS_IDENTITY_CONFLICT"),
                quarantined(message, order, "PAYLOAD_CONFLICT"),
                quarantined(BASE + 93, order, "BUSINESS_IDENTITY_CONFLICT"),
                quarantined(BASE + 96, order, "BUSINESS_IDENTITY_CONFLICT")), warnings("Conflicting ledger command "));
    }

    @Then("swapped command and result IDs are quarantined without changing either saved result")
    public void swappedTransport() throws Exception {
        ObjectNode first = command(11, "USD");
        ObjectNode second = first.deepCopy();
        second.put("messageId", Long.toString(BASE + 300));
        second.put("resultMessageId", Long.toString(BASE + 301));
        runtime.start(database, broker, "USD");
        publish(first);
        publish(second);
        await("two reserved pairs", () -> count("ledger.ledger_result_ids") == 2);
        JsonNode firstResult = result(first);
        JsonNode secondResult = result(second);
        ObjectNode firstSwapped = first.deepCopy();
        firstSwapped.put("resultMessageId", second.get("resultMessageId").asText());
        ObjectNode secondSwapped = second.deepCopy();
        secondSwapped.put("resultMessageId", first.get("resultMessageId").asText());
        publish(firstSwapped);
        publish(secondSwapped);
        await("swapped bindings quarantined", () -> count("ledger.conflicting_commands") == 2);
        ObjectNode otherOrder = command(12, "USD");
        otherOrder.put("resultMessageId", first.get("resultMessageId").asText());
        publish(otherOrder);
        await("cross-order result reuse quarantined", () -> count("ledger.conflicting_commands") == 3);
        assertEquals(firstResult, result(first));
        assertEquals(secondResult, result(second));
        assertEquals(1, attempt(first));
        assertEquals(1, attempt(second));
        assertEquals(1, count("ledger.ledger_operations"));
        assertEquals(1, count("ledger.ledger_entries"));
        assertEquals(2, count("ledger.ledger_result_ids"));
        assertEquals(2, count("ledger.ledger_outbox"));
        awaitQueueEmpty();
    }

    @Then("malformed commands have no ledger effects")
    public void malformed() throws Exception {
        runtime.start(database, broker, "USD");
        ObjectNode id = command(5, "USD");
        id.put("orderId", "0001");
        ObjectNode sum = command(6, "USD");
        ((ObjectNode) sum.get("snapshot")).put("amount", "39.91");
        ObjectNode fingerprint = command(7, "USD");
        fingerprint.put("requestFingerprint", "0".repeat(64));
        for (byte[] body : List.of(Json.bytes(id), "{not-json".getBytes(StandardCharsets.UTF_8),
                Json.bytes(sum), Json.bytes(fingerprint))) {
            publish(body);
            assertArrayEquals(body, parked("rejected"), "Malformed delivery must park immediately and unchanged");
        }
        assertEquals(0, count("ledger.ledger_operations"));
        assertEquals(0, count("ledger.ledger_entries"));
        assertEquals(0, count("ledger.ledger_result_ids"));
        assertEquals(0, count("ledger.conflicting_commands"));
        awaitQueueEmpty();
        String parked = "Malformed ledger command rejected to its dead-letter queue: ";
        assertEquals(List.of(parked + "ID must be a canonical decimal string in 1..9223372036854775807",
                parked + "Invalid JSON body", parked + "Snapshot amount differs from the item total",
                parked + "requestFingerprint differs from the normalized request"), warnings("Malformed ledger command "));
    }

    @Then("concurrent consumers commit one entry and quarantine conflicting identities")
    public void competingConsumers() throws Exception {
        ObjectNode command = command(8, "USD");
        runtime.start(database, broker, "USD");
        competitor = new LedgerRuntime();
        competitor.start(database, broker, "USD");
        try (var holder = postgres.superuser(database);
             PreparedStatement lock = holder.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
            holder.setAutoCommit(false);
            lock.setLong(1, id(command, "orderId"));
            lock.execute();
            publish(command);
            publish(command);
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            boolean bothWaiting = false;
            while (System.nanoTime() < deadline) {
                if (blockedLedgerConsumers() == 2) {
                    bothWaiting = true;
                    break;
                }
                Thread.sleep(25);
            }
            assertTrue(bothWaiting, "Both packaged ledger consumers must overlap on the held order lock");
            holder.rollback();
        }
        for (int delivery = 2; delivery < 8; delivery++) publish(command);
        await("serialized duplicates", () -> attempt(command) == 8);
        ObjectNode crossOrder = command(9, "USD");
        crossOrder.put("sagaId", command.get("sagaId").asText());
        crossOrder.put("commandId", command.get("commandId").asText());
        publish(crossOrder);
        await("cross-order identity conflict", () -> count("ledger.conflicting_commands") == 1);
        assertEquals(1, count("ledger.ledger_operations"));
        assertEquals(1, count("ledger.ledger_entries"));
        assertEquals(1, count("ledger.ledger_result_ids"));
        assertEquals("8", value("SELECT publication_attempt::text FROM ledger.ledger_outbox WHERE message_id=?",
                id(command, "resultMessageId")));
        awaitQueueEmpty();
    }

    /** ARCHITECTURE: the consumer locks the order, saga, command and both transport IDs before it reads decisions. */
    @Then("the consumer waits for a held lock of each command identity")
    public void identityLocks() throws Exception {
        runtime.start(database, broker, "USD");
        List<String> identities = List.of("orderId", "sagaId", "commandId", "messageId", "resultMessageId");
        for (int index = 0; index < identities.size(); index++) {
            String identity = identities.get(index);
            ObjectNode command = command(20 + index, "USD");
            try (var holder = postgres.superuser(database);
                 PreparedStatement lock = holder.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                holder.setAutoCommit(false);
                lock.setLong(1, id(command, identity));
                lock.execute();
                publish(command);
                long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
                while (blockedLedgerConsumers() == 0 && System.nanoTime() < deadline) {
                    Thread.sleep(20);
                }
                assertEquals(1, blockedLedgerConsumers(), "The consumer must wait for the " + identity + " lock");
                assertEquals(0, count("ledger.ledger_operations WHERE order_id = " + id(command, "orderId")));
                holder.rollback();
            }
            int decided = index + 1;
            await("decision after the " + identity + " lock", () -> count("ledger.ledger_operations") == decided);
        }
        awaitQueueEmpty();
    }

    @Then("a database write failure parks the command without a business rejection and recovery records it")
    public void transactionFailure() throws Exception {
        ObjectNode command = command(10, "USD");
        long order = id(command, "orderId");
        try (var connection = postgres.superuser(database); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE ledger.ledger_entries ADD CONSTRAINT ledger_acceptance_fault CHECK (order_id <> "
                    + order + ")");
        }
        runtime.start(database, broker, "USD");
        publish(command);
        assertArrayEquals(Json.bytes(command), parked("delivery_limit"),
                "Transient SQL failures must exhaust broker retries");
        List<String> failures = warnings("Ledger command transaction failed; requested broker retry: ");
        assertFalse(failures.isEmpty(), runtime.tail());
        for (String failure : failures) {
            assertTrue(failure.contains("violates check constraint \"ledger_acceptance_fault\""), failure);
            assertFalse(failure.contains("Failing row"), "Server error details must stay out of the log: " + failure);
        }
        assertEquals(0, count("ledger.ledger_operations"), "Decision must roll back with failed entry");
        assertEquals(0, count("ledger.ledger_entries"));
        assertEquals(0, count("ledger.ledger_result_ids"));
        assertEquals(0, count("ledger.ledger_outbox"));
        try (var connection = postgres.superuser(database); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE ledger.ledger_entries DROP CONSTRAINT ledger_acceptance_fault");
        }
        publish(command);
        await("recovered decision", () -> count("ledger.ledger_operations") == 1);
        assertEquals(1, count("ledger.ledger_entries"));
        assertResult(command, result(command), "RECORDED");
        awaitQueueEmpty();
    }

    static ObjectNode command(int ordinal, String currency) throws Exception {
        ObjectNode command = Json.object(Contracts.message("shardshop-order", "record-order.json").encode());
        long base = BASE + ordinal * 20L;
        command.put("orderId", Long.toString(base));
        command.put("sagaId", Long.toString(base + 1));
        command.put("commandId", Long.toString(base + 2));
        command.put("messageId", Long.toString(base + 3));
        command.put("resultMessageId", Long.toString(base + 4));
        ObjectNode snapshot = (ObjectNode) command.get("snapshot");
        snapshot.put("currency", currency);
        for (JsonNode item : snapshot.get("items")) ((ObjectNode) item).put("currency", currency);
        String normalized = "{\"buyerId\":\"" + command.get("buyerId").asText() + "\",\"orderId\":\"" + base
                + "\",\"currency\":\"" + currency + "\",\"items\":[{\"sellerId\":\""
                + snapshot.at("/items/0/sellerId").asText() + "\",\"productId\":\""
                + snapshot.at("/items/0/productId").asText() + "\",\"quantity\":2}]}";
        command.put("requestFingerprint", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(normalized.getBytes(StandardCharsets.UTF_8))));
        assertEquals(List.of(), Contracts.RECORD_ORDER.errors("#", command));
        return command;
    }

    private void publish(ObjectNode command) throws Exception { publish(Json.bytes(command)); }

    private void publish(byte[] body) throws Exception {
        try (Connection connection = broker.connect("order"); Channel channel = connection.createChannel()) {
            channel.confirmSelect();
            channel.basicPublish("ledger.commands", SOURCE, true,
                    new AMQP.BasicProperties.Builder().contentType("application/json").deliveryMode(2).build(), body);
            channel.waitForConfirmsOrDie(5_000);
        }
    }

    private byte[] parked(String reason) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(35).toNanos();
        try (Connection connection = broker.connect("admin"); Channel channel = connection.createChannel()) {
            while (System.nanoTime() < deadline) {
                GetResponse delivery = channel.basicGet(PARKING, false);
                if (delivery != null) {
                    Object deaths = delivery.getProps().getHeaders().get("x-death");
                    assertInstanceOf(List.class, deaths);
                    Map<?, ?> death = (Map<?, ?>) ((List<?>) deaths).getFirst();
                    assertEquals(SOURCE, death.get("queue").toString());
                    assertEquals(reason, death.get("reason").toString());
                    channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                    return delivery.getBody();
                }
                assertTrue(runtime.isAlive(), runtime.tail());
                Thread.sleep(50);
            }
        }
        fail("Command did not reach the parking queue within 35 seconds: " + runtime.tail());
        return null;
    }

    /** The messages of the ledger WARN lines that start with the prefix, in log order. */
    private List<String> warnings(String prefix) {
        return runtime.lines().stream().filter(line -> line.contains(" WARN "))
                .map(line -> line.substring(line.indexOf(") ") + 2))
                .filter(message -> message.startsWith(prefix)).toList();
    }

    private static String quarantined(long messageId, long orderId, String reason) {
        return "Conflicting ledger command " + messageId + " for order " + orderId + " committed to quarantine: " + reason;
    }

    private void awaitQueueEmpty() throws Exception {
        await("command queue acknowledgement", () -> broker.queue(SOURCE).path("messages").asInt() == 0);
    }

    private void assertResult(ObjectNode command, JsonNode result, String outcome) {
        assertEquals(List.of(), Contracts.LEDGER_RESULT.errors("#", result));
        assertEquals(outcome.equals("RECORDED") ? "LedgerRecorded" : "LedgerRejected", result.get("type").asText());
        assertEquals(outcome, result.get("outcome").asText());
        assertEquals(command.get("resultMessageId"), result.get("messageId"));
        for (String key : List.of("commandId", "sagaId", "orderId", "buyerId", "requestFingerprint")) {
            assertEquals(command.get(key), result.get(key), key);
        }
        assertEquals(command.at("/snapshot/amount"), result.get("amount"));
        assertEquals(command.at("/snapshot/currency"), result.get("currency"));
        assertFalse(Instant.parse(result.get("decidedAt").asText()).isBefore(Instant.parse(command.get("occurredAt").asText())));
        if (outcome.equals("REJECTED")) assertEquals("UNSUPPORTED_CURRENCY", result.get("rejectionReason").asText());
    }

    private JsonNode result(ObjectNode command) throws SQLException {
        return document("SELECT envelope::text FROM ledger.ledger_result_ids WHERE result_message_id=?",
                id(command, "resultMessageId"));
    }

    private long attempt(ObjectNode command) throws SQLException {
        String attempt = value("SELECT publication_attempt::text FROM ledger.ledger_result_ids WHERE result_message_id=?",
                id(command, "resultMessageId"));
        return attempt == null ? 0 : Long.parseLong(attempt);
    }

    private static long id(ObjectNode command, String field) { return Long.parseLong(command.get(field).asText()); }

    private JsonNode document(String sql, long parameter) throws SQLException {
        String raw = value(sql, parameter);
        return raw == null ? null : Json.parse(raw.getBytes(StandardCharsets.UTF_8));
    }

    private String value(String sql, long parameter) throws SQLException {
        try (var connection = postgres.superuser(database); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, parameter);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private int count(String table) throws SQLException {
        try (var connection = postgres.superuser(database); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private int blockedLedgerConsumers() throws SQLException {
        try (var connection = postgres.superuser(database);
             PreparedStatement statement = connection.prepareStatement("SELECT count(*) FROM pg_stat_activity "
                     + "WHERE datname=? AND usename='ledger_app' AND wait_event_type='Lock' "
                     + "AND wait_event='advisory' AND query LIKE 'SELECT pg_advisory_xact_lock%'")) {
            statement.setString(1, database);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private void await(String requirement, Check check) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(35).toNanos();
        while (System.nanoTime() < deadline) {
            if (check.satisfied()) return;
            assertTrue(runtime.isAlive(), "Ledger exited before " + requirement + ":\n" + runtime.tail());
            Thread.sleep(50);
        }
        fail("Ledger did not reach " + requirement + " within 35 seconds:\n" + runtime.tail());
    }

    @FunctionalInterface private interface Check { boolean satisfied() throws Exception; }
}
