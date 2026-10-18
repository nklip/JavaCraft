package dev.nklip.javacraft.shardshop.order.cdc;

import dev.nklip.javacraft.shardshop.common.jdbc.Endpoint;
import dev.nklip.javacraft.shardshop.common.jdbc.JdbcUrls;
import org.postgresql.Driver;

import java.util.Properties;

/** The per-shard connector and offset identities are stable across pod restarts. */
final class CdcConfiguration {
    static final String OFFSET_TABLE = "ordering.debezium_offset_storage";
    static final String PUBLICATION = "ordering_order_outbox";
    static final String HEARTBEAT_PREFIX = "shardshop-cdc-heartbeat";

    private CdcConfiguration() {
    }

    static String identity(String shard) {
        if (shard == null || !shard.matches("[a-z][a-z0-9-]*") || shard.length() > 46) {
            throw new IllegalArgumentException("Invalid CDC shard name");
        }
        return "shardshop_order_" + shard.replace('-', '_');
    }

    static Properties properties(String shard, Endpoint application, Endpoint replication) {
        Properties parsed = Driver.parseURL(replication.jdbcUrl(), null);
        if (parsed == null) {
            throw new IllegalArgumentException("Invalid PostgreSQL replication endpoint");
        }
        String identity = identity(shard);
        Properties result = new Properties();
        result.setProperty("name", identity);
        result.setProperty("connector.class", "io.debezium.connector.postgresql.PostgresConnector");
        result.setProperty("topic.prefix", identity);
        result.setProperty("database.hostname", parsed.getProperty("PGHOST"));
        result.setProperty("database.port", parsed.getProperty("PGPORT"));
        result.setProperty("database.dbname", parsed.getProperty("PGDBNAME"));
        result.setProperty("database.user", replication.username());
        result.setProperty("database.password", replication.password());
        copy(parsed, result, "sslmode", "database.sslmode");
        copy(parsed, result, "sslrootcert", "database.sslrootcert");
        result.setProperty("driver.connectTimeout", "3");
        result.setProperty("driver.loginTimeout", "3");
        result.setProperty("driver.socketTimeout", "30");
        result.setProperty("driver.cancelSignalTimeout", "2");
        result.setProperty("driver.tcpKeepAlive", "true");
        result.setProperty("database.query.timeout.ms", "5000");
        result.setProperty("snapshot.lock.timeout.ms", "5000");
        result.setProperty("plugin.name", "pgoutput");
        result.setProperty("publication.name", PUBLICATION);
        result.setProperty("publication.autocreate.mode", "disabled");
        result.setProperty("slot.name", identity);
        result.setProperty("slot.drop.on.stop", "false");
        result.setProperty("slot.failover", "true");
        result.setProperty("slot.max.retries", "1");
        result.setProperty("slot.retry.delay.ms", "1000");
        result.setProperty("snapshot.mode", "initial");
        result.setProperty("snapshot.fetch.size", "64");
        result.setProperty("table.include.list", "ordering.order_outbox");
        result.setProperty("offset.mismatch.strategy", "trust_offset");
        result.setProperty("lsn.flush.mode", "connector");
        result.setProperty("lsn.flush.timeout.ms", "10000");
        result.setProperty("lsn.flush.timeout.action", "fail");
        result.setProperty("skipped.operations", "none");
        result.setProperty("max.batch.size", "64");
        result.setProperty("max.queue.size", "256");
        result.setProperty("max.queue.size.in.bytes", "16777216");
        result.setProperty("poll.interval.ms", "500");
        result.setProperty("record.processing.threads", "1");
        result.setProperty("record.processing.order", "ORDERED");
        result.setProperty("record.processing.shutdown.timeout.ms", "5000");
        result.setProperty("task.management.timeout.ms", "10000");
        result.setProperty("errors.max.retries", "0");
        result.setProperty("offset.storage", "io.debezium.storage.jdbc.offset.JdbcOffsetBackingStore");
        result.setProperty("offset.storage.jdbc.connection.url", JdbcUrls.withDriverLimits(application.jdbcUrl()));
        result.setProperty("offset.storage.jdbc.connection.user", application.username());
        result.setProperty("offset.storage.jdbc.connection.password", application.password());
        result.setProperty("offset.storage.jdbc.connection.retry.max.attempts", "1");
        result.setProperty("offset.storage.jdbc.connection.wait.retry.delay.ms", "500");
        result.setProperty("offset.storage.jdbc.table.name", OFFSET_TABLE);
        // The migration owns the table. The store's metadata check does not resolve qualified names on all drivers.
        result.setProperty("offset.storage.jdbc.table.ddl", "SELECT 1");
        // Debezium binds the five offset values and commits this insert in the same transaction as its offset save.
        result.setProperty("offset.storage.jdbc.table.insert", "WITH initialized AS ("
                + "INSERT INTO ordering.cdc_reader_state(reader_name) VALUES ('" + identity + "') "
                + "ON CONFLICT DO NOTHING RETURNING reader_name) "
                + "INSERT INTO %s(id, offset_key, offset_val, record_insert_ts, record_insert_seq) "
                + "VALUES (?, ?, ?, ?, ?)");
        result.setProperty("offset.flush.interval.ms", "1000");
        result.setProperty("offset.flush.timeout.ms", "10000");
        // Without heartbeats, an empty first snapshot writes no offset, and an idle outbox never confirms WAL.
        // Each heartbeat emits a transactional logical decoding message. Its commit LSN is a position that
        // Debezium can save and confirm, so a quiet shard keeps a paired offset and slot and releases WAL.
        result.setProperty("heartbeat.interval.ms", "10000");
        result.setProperty("heartbeat.action.query",
                "SELECT pg_logical_emit_message(true, '" + HEARTBEAT_PREFIX + "', '')");
        result.setProperty("message.prefix.include.list", HEARTBEAT_PREFIX);
        result.setProperty("value.converter.schemas.enable", "false");
        return result;
    }

    private static void copy(Properties from, Properties to, String source, String destination) {
        String value = from.getProperty(source);
        if (value != null && !value.isBlank()) {
            to.setProperty(destination, value);
        }
    }
}
