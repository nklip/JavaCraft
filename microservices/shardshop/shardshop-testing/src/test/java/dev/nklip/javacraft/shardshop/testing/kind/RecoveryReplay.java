package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nklip.javacraft.shardshop.common.amqp.BrokerSettings;
import dev.nklip.javacraft.shardshop.common.amqp.ConfirmedPublisher;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.CatalogDatabase;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.PackagedApplication;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.RabbitMqBroker;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.RawHttp;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** Packaged, target-only delivery witnesses using authentic command and result bytes. */
final class RecoveryReplay {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final List<String> SHARDS = List.of("shard-a", "shard-b", "shard-c");
    private RecoveryReplay() { }

    static void late(OwnedRestoreTarget target, List<RecoveryLossFixture.Witness> missing, Path privateEvidence) throws Exception {
        require(missing != null && !missing.isEmpty(), "Late-delivery witnesses are missing");
        for (var witness : missing) RecoveryLossFixture.validateWitnesses(witness.shard(), List.of(witness));
        Path directory = directory(directory(privateEvidence).resolve("late-delivery"));
        try (Runtime runtime = new Runtime()) {
            runtime.broker.start();
            Properties resultConfig = restrictOrder(RestoreCdcRecovery.orderProperties(target, SHARDS,
                    runtime.broker.port(), runtime.broker.orderPassword()), false, 0);
            runtime.order = start("shardshop-order", directory, "results.properties", resultConfig);
            await("results-only consumer starts", () -> {
                alive(runtime.order);
                return runtime.broker.queue("order.ledger-results").path("consumers").asInt() > 0;
            });
            for (var witness : missing) {
                long previous = deliveries(target, witness);
                long acknowledged = acknowledgements(runtime.broker, "order.ledger-results");
                publish(runtime.broker, false, witness.resultEnvelope());
                await("late original result remains quarantined after broker acknowledgement", () -> {
                    alive(runtime.order);
                    return deliveries(target, witness) > previous && unresolved(target, witness) > 0
                            && acknowledgements(runtime.broker, "order.ledger-results") > acknowledged && queuesEmpty(runtime.broker);
                });
            }
            int port = CatalogDatabase.freePort();
            Properties httpConfig = restrictOrder(resultConfig, true, port);
            runtime.http = start("shardshop-order", directory, "http.properties", httpConfig);
            await("isolated order HTTP starts", () -> {
                alive(runtime.http);
                try { return RawHttp.exchange(port, "GET", "/api/v1/buyers/0", List.of(), new byte[0], RawHttp.Framing.NONE).status() == 400; }
                catch (IOException starting) { return false; }
            });
            for (var witness : missing) {
                RawHttp.Response response = RawHttp.exchange(port, "PUT", "/api/v1/buyers/" + witness.buyerId()
                                + "/orders/" + witness.orderId(), List.of(new RawHttp.Header("Content-Type", "application/json")),
                        JSON.writeValueAsBytes(originalPut(witness.commandEnvelope())), RawHttp.Framing.CONTENT_LENGTH);
                require(response.status() == 409 && JSON.readTree(response.body()).path("code").asText().equals("ORDER_RECONCILIATION_REQUIRED"),
                        "Original HTTP order reuse did not remain blocked by unresolved quarantine");
                require(unresolved(target, witness) > 0, "HTTP order reuse cleared its quarantine");
            }
        } catch (Exception | AssertionError failure) { throw safe("Late-delivery recovery witness failed; inspect private evidence", failure); }
    }

    static void replay(OwnedRestoreTarget target, RecoveryLossFixture.Witness recovered, Path privateEvidence) throws Exception {
        RecoveryLossFixture.validateWitnesses(recovered.shard(), List.of(recovered));
        Path directory = directory(directory(privateEvidence).resolve("command-replay"));
        long entries = ledgerEntries(target);
        Saved before = saved(target, recovered);
        JsonNode accepted = accepted(target, recovered);
        require(accepted.equals(recovered.resultEnvelope()), "Recovered order has not accepted its original ledger outcome");
        require(unresolved(target, recovered) == 0, "Unresolved quarantine prohibits command replay");
        try (Runtime runtime = new Runtime()) {
            runtime.broker.start();
            Properties resultConfig = restrictOrder(RestoreCdcRecovery.orderProperties(target, SHARDS,
                    runtime.broker.port(), runtime.broker.orderPassword()), false, 0);
            runtime.order = start("shardshop-order", directory, "results.properties", resultConfig);
            runtime.ledger = start("shardshop-ledger", directory, "ledger.properties", RestoreCdcRecovery.ledgerProperties(target, runtime.broker));
            await("isolated original-command replay consumers start", () -> {
                alive(runtime.order); alive(runtime.ledger);
                return runtime.broker.queue("ledger.record-order").path("consumers").asInt() > 0
                        && runtime.broker.queue("order.ledger-results").path("consumers").asInt() > 0;
            });
            publish(runtime.broker, true, recovered.commandEnvelope());
            await("original command replays and its result is durably acknowledged", () -> {
                alive(runtime.order); alive(runtime.ledger);
                Saved current = saved(target, recovered);
                return current.attempt() > before.attempt() && current.published()
                        && acknowledgements(runtime.broker, "ledger.record-order") > 0
                        && acknowledgements(runtime.broker, "order.ledger-results") > 0 && queuesEmpty(runtime.broker);
            });
            Saved after = saved(target, recovered);
            require(before.command().equals(after.command()) && before.result().equals(after.result()),
                    "Command replay changed an original permanent transport envelope");
            require(accepted.equals(accepted(target, recovered)), "Command replay changed the original accepted result binding");
            require(ledgerEntries(target) == entries, "Command replay duplicated a permanent ledger entry");
            require(unresolved(target, recovered) == 0, "Command replay introduced unresolved quarantine");
        } catch (Exception | AssertionError failure) { throw safe("Original command replay witness failed; inspect private evidence", failure); }
    }

    static Properties restrictOrder(Properties base, boolean http, int port) {
        Properties config = new Properties(); config.putAll(base);
        config.setProperty("shardshop.order.process", http ? "http" : "saga");
        config.setProperty("quarkus.http.host", "127.0.0.1"); config.setProperty("quarkus.http.port", Integer.toString(port));
        config.setProperty("shardshop.id.generator-id", "1");
        for (String worker : List.of("cdc", "stock", "profit", "reconciliation", "cleanup"))
            config.setProperty("shardshop.order." + worker + ".enabled", "false");
        config.setProperty("shardshop.order.results.enabled", Boolean.toString(!http));
        return config;
    }

    static ObjectNode originalPut(JsonNode command) throws IOException {
        JsonNode snapshot = command.path("snapshot");
        require(snapshot.path("currency").isTextual() && snapshot.path("items").isArray() && !snapshot.path("items").isEmpty(),
                "Original command lacks its immutable HTTP request");
        ObjectNode request = JSON.createObjectNode().put("currency", snapshot.path("currency").asText());
        var items = request.putArray("items");
        for (JsonNode item : snapshot.path("items")) {
            require(item.path("sellerId").asText().matches("[1-9][0-9]{0,18}")
                    && item.path("productId").asText().matches("[1-9][0-9]{0,18}")
                    && item.path("quantity").isInt() && item.path("quantity").asInt() > 0, "Original command item identity is malformed");
            items.addObject().put("sellerId", item.path("sellerId").asText()).put("productId", item.path("productId").asText())
                    .put("quantity", item.path("quantity").asInt());
        }
        return request;
    }

    private static void publish(RabbitMqBroker broker, boolean command, JsonNode envelope) throws Exception {
        BrokerSettings settings = new BrokerSettings("127.0.0.1", broker.port(), "shardshop",
                command ? "shardshop-order" : "shardshop-ledger", command ? broker.orderPassword() : broker.ledgerPassword(),
                java.time.Duration.ofSeconds(5));
        try (ConfirmedPublisher publisher = new ConfirmedPublisher(settings, new ConfirmedPublisher.Destination(
                command ? "ledger.commands" : "order.saga-results", command ? "ledger.record-order" : "order.ledger-results",
                "recovery replay", "recovery-replay"))) {
            publisher.publish(envelope.path("messageId").asText(), JSON.writeValueAsBytes(envelope));
        }
    }

    private record Saved(long attempt, boolean published, JsonNode command, JsonNode result) { }

    private static Saved saved(OwnedRestoreTarget target, RecoveryLossFixture.Witness witness) throws Exception {
        try (Connection connection = target.connection("ledger-db"); PreparedStatement query = connection.prepareStatement(
                "SELECT r.publication_attempt,b.published_at IS NOT NULL,r.command_envelope::text,r.envelope::text "
                        + "FROM ledger.ledger_result_ids r LEFT JOIN ledger.ledger_outbox b "
                        + "ON b.message_id=r.result_message_id AND b.publication_attempt=r.publication_attempt WHERE r.order_id=? AND r.result_message_id=?")) {
            query.setQueryTimeout(15); query.setLong(1, witness.orderId()); query.setLong(2, witness.resultMessageId());
            try (ResultSet rows = query.executeQuery()) {
                require(rows.next(), "Original permanent ledger binding is absent");
                return new Saved(rows.getLong(1), rows.getBoolean(2), JSON.readTree(rows.getString(3)), JSON.readTree(rows.getString(4)));
            }
        }
    }

    private static JsonNode accepted(OwnedRestoreTarget target, RecoveryLossFixture.Witness witness) throws Exception {
        try (Connection connection = target.connection(witness.shard()); PreparedStatement query = connection.prepareStatement(
                "SELECT s.ledger_result::text,r.received_envelope::text FROM ordering.order_sagas s "
                        + "JOIN ordering.order_result_ids r ON r.saga_id=s.saga_id WHERE s.order_id=? AND r.result_message_id=? "
                        + "AND s.status='CONFIRMED'")) {
            query.setQueryTimeout(15); query.setLong(1, witness.orderId()); query.setLong(2, witness.resultMessageId());
            try (ResultSet rows = query.executeQuery()) {
                require(rows.next() && rows.getString(1) != null && rows.getString(2) != null, "Original accepted result is absent");
                JsonNode result = JSON.readTree(rows.getString(1));
                require(result.equals(JSON.readTree(rows.getString(2))), "Original accepted result binding disagrees with the saga");
                return result;
            }
        }
    }

    private static long deliveries(OwnedRestoreTarget target, RecoveryLossFixture.Witness witness) throws Exception {
        return count(target, witness.shard(), "SELECT coalesce(sum(delivery_count),0) FROM ordering.orphan_results WHERE order_id=?", witness.orderId());
    }

    private static long unresolved(OwnedRestoreTarget target, RecoveryLossFixture.Witness witness) throws Exception {
        return count(target, witness.shard(), "SELECT (SELECT count(*) FROM ordering.orphan_results WHERE order_id=? AND resolved_at IS NULL)"
                + "+(SELECT count(*) FROM ordering.conflicting_results WHERE order_id=? AND resolved_at IS NULL)", witness.orderId(), witness.orderId());
    }

    private static long ledgerEntries(OwnedRestoreTarget target) throws Exception {
        return count(target, "ledger-db", "SELECT count(*) FROM ledger.ledger_entries");
    }

    private static long count(OwnedRestoreTarget target, String database, String sql, long... ids) throws Exception {
        try (Connection connection = target.connection(database); PreparedStatement query = connection.prepareStatement(sql)) {
            query.setQueryTimeout(15);
            for (int i = 0; i < ids.length; i++) query.setLong(i + 1, ids[i]);
            try (ResultSet rows = query.executeQuery()) { require(rows.next(), "Recovery replay observation is absent"); return rows.getLong(1); }
        }
    }

    private static long acknowledgements(RabbitMqBroker broker, String queue) throws Exception {
        return broker.queue(queue).path("message_stats").path("ack").asLong(0);
    }

    private static boolean queuesEmpty(RabbitMqBroker broker) throws Exception {
        for (String queue : List.of("ledger.record-order", "order.ledger-results", "ledger.record-order.dlq", "order.ledger-results.dlq")) {
            JsonNode state = broker.queue(queue);
            if (state.path("messages_ready").asLong(-1) != 0 || state.path("messages_unacknowledged").asLong(-1) != 0) return false;
        }
        return true;
    }

    private static Path directory(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(path,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        return OwnedRestoreTarget.privateDirectory(path);
    }

    private static PackagedApplication start(String module, Path directory, String filename, Properties properties) throws IOException {
        Path config = Files.createFile(directory.resolve(filename), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try (OutputStream output = Files.newOutputStream(config)) { properties.store(output, "Isolated recovery replay credentials"); }
        PackagedApplication app = PackagedApplication.start(module, directory, List.of((module.equals("shardshop-ledger") ? "-Dquarkus.config.locations=" : "-Dshardshop.routing.config=") + config.toUri()));
        try { Files.setPosixFilePermissions(app.log(), PosixFilePermissions.fromString("rw-------")); }
        catch (IOException failure) {
            try { app.kill(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            throw new IOException("Could not protect isolated replay log");
        }
        return app;
    }

    private static void alive(PackagedApplication app) throws IOException { require(app != null && app.isAlive(), "Packaged recovery worker exited"); }
    @FunctionalInterface private interface Probe { boolean ready() throws Exception; }
    private static void await(String reason, Probe probe) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        do {
            if (probe.ready()) return;
            if (System.nanoTime() >= deadline) break;
            TimeUnit.MILLISECONDS.sleep(100);
        } while (System.nanoTime() < deadline);
        throw new IOException("Timed out waiting for " + reason);
    }

    private static final class Runtime implements AutoCloseable {
        final RabbitMqBroker broker = new RabbitMqBroker();
        PackagedApplication order, ledger, http;
        private boolean closed;
        private final Thread shutdown = new Thread(() -> {
            try { close(); } catch (IOException failure) { System.err.println("Recovery replay cleanup needs attention"); }
        }, "owned-recovery-replay-cleanup");
        Runtime() { java.lang.Runtime.getRuntime().addShutdownHook(shutdown); }
        @Override public synchronized void close() throws IOException {
            if (closed) return;
            closed = true;
            try { java.lang.Runtime.getRuntime().removeShutdownHook(shutdown); }
            catch (IllegalStateException shuttingDown) { /* This hook owns cleanup during JVM shutdown. */ }
            boolean interrupted = Thread.interrupted();
            boolean failed = false;
            for (PackagedApplication app : java.util.Arrays.asList(http, order, ledger)) {
                if (app == null) continue;
                try { if (app.isAlive()) app.stop(); }
                catch (Exception | AssertionError failure) {
                    interrupted |= failure instanceof InterruptedException; Thread.interrupted();
                    try { if (app.isAlive()) app.kill(); }
                    catch (Exception | AssertionError killFailure) { interrupted |= killFailure instanceof InterruptedException; Thread.interrupted(); failed = true; }
                }
            }
            try { broker.dispose(); } catch (RuntimeException | AssertionError failure) { failed = true; }
            if (interrupted) Thread.currentThread().interrupt();
            if (failed) throw new IOException("Recovery replay cleanup needs attention");
        }
    }

    private static Exception safe(String message, Throwable failure) {
        if (failure instanceof InterruptedException interrupted) { Thread.currentThread().interrupt(); return interrupted; }
        return new IOException(message);
    }
    private static void require(boolean value, String message) throws IOException { if (!value) throw new IOException(message); }
}
