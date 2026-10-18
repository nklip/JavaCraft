package dev.nklip.javacraft.shardshop.order.cdc;

import dev.nklip.javacraft.shardshop.common.jdbc.Endpoint;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class CdcConfigurationTest {
    @Test
    void eachShardHasStableDistinctIdentityAndSeparateCredentials() {
        Endpoint app = new Endpoint("jdbc:postgresql://database:5432/shardshop?sslmode=verify-full"
                + "&sslrootcert=%2Fcert%2Fca.crt", "order_app", "app-secret");
        Endpoint replication = new Endpoint(app.jdbcUrl(), "order_cdc", "cdc-secret");

        Properties config = CdcConfiguration.properties("shard-a", app, replication);

        assertEquals("shardshop_order_shard_a", config.getProperty("name"));
        assertEquals("shardshop_order_shard_a", config.getProperty("topic.prefix"));
        assertEquals("shardshop_order_shard_a", config.getProperty("slot.name"));
        assertNotEquals(config.getProperty("slot.name"), CdcConfiguration.identity("shard-b"));
        assertEquals("order_cdc", config.getProperty("database.user"));
        assertEquals("cdc-secret", config.getProperty("database.password"));
        assertEquals("order_app", config.getProperty("offset.storage.jdbc.connection.user"));
        assertEquals("app-secret", config.getProperty("offset.storage.jdbc.connection.password"));
        assertEquals("database", config.getProperty("database.hostname"));
        assertEquals("5432", config.getProperty("database.port"));
        assertEquals("shardshop", config.getProperty("database.dbname"));
        assertEquals("verify-full", config.getProperty("database.sslmode"));
        assertEquals("/cert/ca.crt", config.getProperty("database.sslrootcert"));
        assertEquals("ordering_order_outbox", config.getProperty("publication.name"));
        assertEquals("disabled", config.getProperty("publication.autocreate.mode"));
        assertEquals("false", config.getProperty("slot.drop.on.stop"));
        assertEquals("true", config.getProperty("slot.failover"));
        assertEquals("initial", config.getProperty("snapshot.mode"));
        assertEquals("64", config.getProperty("snapshot.fetch.size"));
        assertEquals("1", config.getProperty("slot.max.retries"));
        assertEquals("1000", config.getProperty("slot.retry.delay.ms"));
        assertEquals("trust_offset", config.getProperty("offset.mismatch.strategy"));
        assertEquals("ordering.debezium_offset_storage", config.getProperty("offset.storage.jdbc.table.name"));
        assertEquals("SELECT 1", config.getProperty("offset.storage.jdbc.table.ddl"));
        assertTrue(config.getProperty("offset.storage.jdbc.table.insert")
                .contains("VALUES ('shardshop_order_shard_a') ON CONFLICT DO NOTHING"));
        assertTrue(config.getProperty("offset.storage.jdbc.table.insert")
                .contains("INSERT INTO %s(id, offset_key, offset_val, record_insert_ts, record_insert_seq)"));
        assertEquals("3", config.getProperty("driver.connectTimeout"));
        assertEquals("30", config.getProperty("driver.socketTimeout"));
        assertEquals("5000", config.getProperty("database.query.timeout.ms"));
        assertEquals("10000", config.getProperty("task.management.timeout.ms"));
    }

    /** An empty snapshot and an idle outbox still commit positions, so a restart finds a paired offset and slot. */
    @Test
    void heartbeatsEmitTransactionalMessagesThatTheReaderCanConfirm() {
        Endpoint endpoint = new Endpoint("jdbc:postgresql://database:5432/shardshop", "order_app", "app-secret");

        Properties config = CdcConfiguration.properties("shard-a", endpoint, endpoint);

        assertEquals("10000", config.getProperty("heartbeat.interval.ms"));
        assertEquals("SELECT pg_logical_emit_message(true, 'shardshop-cdc-heartbeat', '')",
                config.getProperty("heartbeat.action.query"));
        assertEquals("shardshop-cdc-heartbeat", config.getProperty("message.prefix.include.list"));
    }

    @Test
    void malformedShardNameCannotBecomeConnectorOrSlotIdentity() {
        assertThrows(IllegalArgumentException.class, () -> CdcConfiguration.identity("shard.a"));
        assertThrows(IllegalArgumentException.class, () -> CdcConfiguration.identity("SHARD-A"));
    }
}
