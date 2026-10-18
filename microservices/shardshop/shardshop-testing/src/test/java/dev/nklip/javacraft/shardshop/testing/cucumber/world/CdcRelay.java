package dev.nklip.javacraft.shardshop.testing.cucumber.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.GetResponse;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.CatalogDatabase;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.CatalogRouting;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.Json;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.RabbitMqBroker;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.ShardShopFiles;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** SQL-arranged committed source records; the only delivery path under test is the packaged order JVM. */
public final class CdcRelay {
    private static final Duration DELIVERY = Duration.ofSeconds(20);
    private final CatalogShards shards;
    private final RabbitMqBroker broker;
    private final AtomicLong sequence = new AtomicLong(10_000_000);
    private final Set<Long> buyers = ConcurrentHashMap.newKeySet();

    public CdcRelay(CatalogShards shards, RabbitMqBroker broker) {
        this.shards = shards;
        this.broker = broker;
    }

    public void prepare() throws Exception {
        shards.create("shard-a=US,shard-b=EU,shard-c=ASIA");
        for (var shard : shards.all()) {
            CatalogDatabase.instance().migrate(shard.database(), ShardShopFiles.resolve("database/shard/ordering"),
                    Map.of("shardRegion", shard.region(), "shardIndex", Integer.toString(shards.all().indexOf(shard)),
                            "shardCount", Integer.toString(shards.all().size())), "ordering");
            CatalogDatabase.instance().createOrderPublication(shard.database());
        }
        broker.start();
    }

    public record Source(CatalogShards.Shard shard, String messageId, JsonNode envelope, long walBeforeInsert) {
    }

    /** Insert a complete valid outbox chain, with a unique buyer ID whose hash belongs to this shard. */
    public Source insert(CatalogShards.Shard shard, boolean published) throws Exception {
        return insert(shard, published, true);
    }

    public Source rolledBackInsert(CatalogShards.Shard shard) throws Exception {
        return insert(shard, false, false);
    }

    private Source insert(CatalogShards.Shard shard, boolean published, boolean commit) throws Exception {
        long base = sequence.getAndAdd(10);
        long buyer = buyerId(shard, base);
        long order = base + 1;
        long saga = base + 2;
        long command = base + 3;
        long message = base + 4;
        long result = base + 5;
        ObjectNode envelope = Json.object(Contracts.message("shardshop-order", "record-order.json").encode());
        envelope.put("buyerId", Long.toString(buyer));
        envelope.put("orderId", Long.toString(order));
        envelope.put("sagaId", Long.toString(saga));
        envelope.put("commandId", Long.toString(command));
        envelope.put("messageId", Long.toString(message));
        envelope.put("resultMessageId", Long.toString(result));
        JsonNode item = envelope.at("/snapshot/items/0");
        String normalizedRequest = "{\"buyerId\":\"" + buyer + "\",\"orderId\":\"" + order
                + "\",\"currency\":\"EUR\",\"items\":[{\"sellerId\":\"" + item.path("sellerId").asText()
                + "\",\"productId\":\"" + item.path("productId").asText()
                + "\",\"quantity\":" + item.path("quantity").asInt() + "}]}";
        envelope.put("requestFingerprint", String.format("%064x", CatalogRouting.digest(normalizedRequest)));
        assertEquals(java.util.List.of(), Contracts.RECORD_ORDER.errors("#", envelope));
        long walBeforeInsert;
        try (var connection = shards.superuser(shard)) {
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), '0/0')::bigint")) {
                assertTrue(rows.next());
                walBeforeInsert = rows.getLong(1);
            }
            connection.setAutoCommit(false);
            try {
                execute(connection, "INSERT INTO ordering.buyers (buyer_id,name,region) VALUES (?,?,?)", buyer, "CDC fixture", shard.region());
                execute(connection, "INSERT INTO ordering.order_allocations (order_id,buyer_id,run_name,request_ordinal) VALUES (?,?,?,?)",
                        order, buyer, "cdc-relay", base);
                execute(connection, "INSERT INTO ordering.orders (order_id,buyer_id,request_fingerprint,currency,total_amount,status) "
                        + "VALUES (?,?,?,?,?,?)", order, buyer, envelope.path("requestFingerprint").asText(), "EUR",
                        new BigDecimal("39.90"), "PENDING_LEDGER");
                execute(connection, "INSERT INTO ordering.order_items (order_id,item_position,seller_id,product_id,quantity,"
                        + "product_name,unit_price,currency,unit_cost) VALUES (?,?,?,?,?,?,?,?,?)", order, 0,
                        Long.parseLong(item.path("sellerId").asText()), Long.parseLong(item.path("productId").asText()),
                        2, item.path("productName").asText(), new BigDecimal("19.95"), "EUR", new BigDecimal("12.50"));
                execute(connection, "INSERT INTO ordering.order_sagas (saga_id,order_id,command_id,status) VALUES (?,?,?,?)",
                        saga, order, command, "PENDING_LEDGER");
                execute(connection, "INSERT INTO ordering.order_result_ids (result_message_id,message_id,saga_id) VALUES (?,?,?)",
                        result, message, saga);
                execute(connection, "INSERT INTO ordering.order_outbox (message_id,result_message_id,saga_id,envelope,published_at) "
                        + "VALUES (?,?,?,?::jsonb," + (published ? "CURRENT_TIMESTAMP" : "NULL") + ")",
                        message, result, saga, new String(Json.bytes(envelope), StandardCharsets.UTF_8));
                if (commit) connection.commit();
                else connection.rollback();
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            }
        }
        return new Source(shard, Long.toString(message), envelope, walBeforeInsert);
    }

    public void updateAndDelete(Source source) throws SQLException {
        try (var connection = shards.superuser(source.shard()); Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE ordering.order_outbox SET published_at=CURRENT_TIMESTAMP WHERE message_id="
                    + source.messageId());
            statement.executeUpdate("DELETE FROM ordering.order_outbox WHERE message_id=" + source.messageId());
        }
    }

    public void markPublished(Source source) throws SQLException {
        try (var connection = shards.superuser(source.shard()); PreparedStatement statement = connection.prepareStatement(
                "UPDATE ordering.order_outbox SET published_at=clock_timestamp() WHERE message_id=?")) {
            statement.setLong(1, Long.parseLong(source.messageId()));
            assertEquals(1, statement.executeUpdate());
        }
    }

    public boolean published(Source source) throws SQLException {
        try (var connection = shards.superuser(source.shard()); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT published_at IS NOT NULL FROM ordering.order_outbox WHERE message_id="
                     + source.messageId())) {
            return rows.next() && rows.getBoolean(1);
        }
    }

    public long pending(CatalogShards.Shard shard) throws SQLException {
        try (var connection = shards.superuser(shard); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT count(*) FROM ordering.order_outbox WHERE published_at IS NULL")) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    public long offsets(CatalogShards.Shard shard) throws SQLException {
        try (var connection = shards.superuser(shard); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT count(*) FROM ordering.debezium_offset_storage")) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    /** The source LSN from Debezium's durable JSON position, excluding JDBC row identity and timestamps. */
    public long offsetLsn(CatalogShards.Shard shard) throws SQLException {
        try (var connection = shards.superuser(shard); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COALESCE(MAX((offset_val::jsonb->>'lsn')::bigint), 0) "
                     + "FROM ordering.debezium_offset_storage")) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    public void deleteOffsets(CatalogShards.Shard shard) throws SQLException {
        try (var connection = shards.superuser(shard); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM ordering.debezium_offset_storage");
        }
    }

    public long readerHistory(CatalogShards.Shard shard) throws SQLException {
        try (var connection = shards.superuser(shard); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT count(*) FROM ordering.cdc_reader_state")) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    public void assertHistoryImmutableToRuntime(CatalogShards.Shard shard) throws SQLException {
        try (var connection = CatalogDatabase.instance().order(shard.database()); Statement statement = connection.createStatement()) {
            SQLException update = assertThrows(SQLException.class, () ->
                    statement.executeUpdate("UPDATE ordering.cdc_reader_state SET initialized_at=clock_timestamp()"));
            assertEquals("42501", update.getSQLState());
            SQLException delete = assertThrows(SQLException.class, () ->
                    statement.executeUpdate("DELETE FROM ordering.cdc_reader_state"));
            assertEquals("42501", delete.getSQLState());
        }
    }

    public void dropSlot(CatalogShards.Shard shard) throws SQLException {
        try (var connection = shards.superuser(shard); PreparedStatement statement = connection.prepareStatement(
                "SELECT pg_drop_replication_slot(?)")) {
            statement.setString(1, slotName(shard));
            statement.execute();
        }
    }

    public JsonNode delivery(Source source) throws Exception {
        return delivery(source, DELIVERY);
    }

    public JsonNode delivery(Source source, Duration timeout) throws Exception {
        return delivery(source.envelope(), "message " + source.messageId(), timeout);
    }

    /** The next command must be the expected stored envelope, schema-valid and persistent. */
    public JsonNode delivery(JsonNode expected, String description, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        try (Connection connection = broker.connect("admin"); Channel channel = connection.createChannel()) {
            while (System.nanoTime() < deadline) {
                GetResponse response = channel.basicGet("ledger.record-order", false);
                if (response != null) {
                    JsonNode payload = command(response);
                    assertEquals(expected, payload, "Broker body is the stored business envelope");
                    channel.basicAck(response.getEnvelope().getDeliveryTag(), false);
                    return payload;
                }
                Thread.sleep(50);
            }
        }
        throw new AssertionError("No command for " + description + " within " + timeout);
    }

    /** Acknowledges and returns the message IDs of every schema-valid command that arrives within the duration. */
    public List<String> deliveries(Duration duration) throws Exception {
        List<String> messageIds = new ArrayList<>();
        long deadline = System.nanoTime() + duration.toNanos();
        try (Connection connection = broker.connect("admin"); Channel channel = connection.createChannel()) {
            while (System.nanoTime() < deadline) {
                GetResponse response = channel.basicGet("ledger.record-order", false);
                if (response == null) {
                    Thread.sleep(50);
                    continue;
                }
                messageIds.add(command(response).path("messageId").asText());
                channel.basicAck(response.getEnvelope().getDeliveryTag(), false);
            }
        }
        return messageIds;
    }

    private static JsonNode command(GetResponse response) {
        JsonNode payload = Json.parse(response.getBody());
        assertEquals(List.of(), Contracts.RECORD_ORDER.errors("#", payload));
        assertEquals(2, response.getProps().getDeliveryMode(), "Command is persistent");
        return payload;
    }

    /** Removes the only route of the command exchange, so a mandatory publication returns. */
    public void unbindCommands() throws Exception {
        try (Connection connection = broker.connect("admin"); Channel channel = connection.createChannel()) {
            channel.queueUnbind("ledger.record-order", "ledger.commands", "ledger.record-order");
        }
    }

    public void bindCommands() throws Exception {
        try (Connection connection = broker.connect("admin"); Channel channel = connection.createChannel()) {
            channel.queueBind("ledger.record-order", "ledger.commands", "ledger.record-order");
        }
    }

    public record Slot(boolean exists, boolean active, String walStatus, String invalidationReason,
                       long confirmedFlushLsn, long restartLsn) {
    }

    public Slot slot(CatalogShards.Shard shard) throws SQLException {
        try (var connection = shards.superuser(shard); PreparedStatement statement = connection.prepareStatement(
                "SELECT active, wal_status, invalidation_reason, pg_wal_lsn_diff(confirmed_flush_lsn, '0/0')::bigint, "
                        + "pg_wal_lsn_diff(restart_lsn, '0/0')::bigint FROM pg_replication_slots WHERE slot_name = ?")) {
            statement.setString(1, slotName(shard));
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return new Slot(false, false, null, null, 0, 0);
                return new Slot(true, rows.getBoolean(1), rows.getString(2), rows.getString(3), rows.getLong(4),
                        rows.getLong(5));
            }
        }
    }

    /** Writes about 30 MB of WAL in the shard database without an outbox change and returns the WAL end. */
    public long churnWal(CatalogShards.Shard shard) throws SQLException {
        try (var connection = shards.superuser(shard); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS public.cdc_wal_churn (x text)");
            for (int round = 0; round < 2; round++) {
                statement.execute("INSERT INTO public.cdc_wal_churn SELECT md5(random()::text) || md5(random()::text) "
                        + "FROM generate_series(1, 150000)");
                statement.execute("SELECT pg_switch_wal()");
            }
            try (ResultSet rows = statement.executeQuery("SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), '0/0')::bigint")) {
                assertTrue(rows.next());
                return rows.getLong(1);
            }
        }
    }

    /** A checkpoint logs running transactions, from which a confirmed slot can move its restart position. */
    public void checkpoint(CatalogShards.Shard shard) throws SQLException {
        try (var connection = shards.superuser(shard); Statement statement = connection.createStatement()) {
            statement.execute("CHECKPOINT");
        }
    }

    /** Invalidates the inactive slot through lost WAL, then restores the server's retention setting. */
    public void invalidateSlot(CatalogShards.Shard shard) throws SQLException {
        try (var connection = shards.superuser(shard); Statement statement = connection.createStatement()) {
            statement.execute("ALTER SYSTEM SET max_slot_wal_keep_size = '1MB'");
            statement.execute("SELECT pg_reload_conf()");
            try {
                statement.execute("CREATE TABLE IF NOT EXISTS public.cdc_wal_churn (x text)");
                for (int round = 0; round < 5 && !"lost".equals(slot(shard).walStatus()); round++) {
                    statement.execute("INSERT INTO public.cdc_wal_churn SELECT md5(random()::text) || md5(random()::text) "
                            + "FROM generate_series(1, 250000)");
                    statement.execute("SELECT pg_switch_wal()");
                    statement.execute("CHECKPOINT");
                }
            } finally {
                statement.execute("ALTER SYSTEM RESET max_slot_wal_keep_size");
                statement.execute("SELECT pg_reload_conf()");
            }
        }
        assertEquals("lost", slot(shard).walStatus(), "The slot lost its required WAL");
    }

    /** Ends the active walsender of the shard reader, as a primary restart or a network failure does. */
    public void terminateWalSender(CatalogShards.Shard shard) throws SQLException {
        try (var connection = shards.superuser(shard); PreparedStatement statement = connection.prepareStatement(
                "SELECT pg_terminate_backend(active_pid) FROM pg_replication_slots WHERE slot_name = ? AND active")) {
            statement.setString(1, slotName(shard));
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next() && rows.getBoolean(1), "The reader had an active walsender");
            }
        }
    }

    /** Moves the inactive slot past newer WAL, as an operator or a recreated slot would. */
    public void advanceSlot(CatalogShards.Shard shard) throws SQLException {
        try (var connection = shards.superuser(shard); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS public.cdc_wal_churn (x text)");
            statement.execute("INSERT INTO public.cdc_wal_churn VALUES ('advance')");
            try (PreparedStatement advance = connection.prepareStatement(
                    "SELECT pg_replication_slot_advance(?, pg_current_wal_lsn())")) {
                advance.setString(1, slotName(shard));
                advance.execute();
            }
        }
    }

    private static String slotName(CatalogShards.Shard shard) {
        return "shardshop_order_" + shard.name().replace('-', '_');
    }

    public void assertNoDelivery(Duration duration) throws Exception {
        long deadline = System.nanoTime() + duration.toNanos();
        try (Connection connection = broker.connect("admin"); Channel channel = connection.createChannel()) {
            while (System.nanoTime() < deadline) {
                GetResponse response = channel.basicGet("ledger.record-order", false);
                if (response != null) {
                    channel.basicNack(response.getEnvelope().getDeliveryTag(), false, true);
                    fail("Unexpected CDC delivery: " + new String(response.getBody(), StandardCharsets.UTF_8));
                }
                Thread.sleep(50);
            }
        }
    }

    private long buyerId(CatalogShards.Shard shard, long candidate) {
        int index = shards.all().indexOf(shard);
        // The search can pass the next base, so each buyer ID is used once per scenario.
        while (CatalogRouting.sellerShard(Long.toString(candidate), shards.all().size()) != index
                || !buyers.add(candidate)) {
            candidate++;
        }
        return candidate;
    }

    private static void execute(java.sql.Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            statement.executeUpdate();
        }
    }
}
