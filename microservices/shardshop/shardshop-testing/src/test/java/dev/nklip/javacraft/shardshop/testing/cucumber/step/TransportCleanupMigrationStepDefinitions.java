package dev.nklip.javacraft.shardshop.testing.cucumber.step;

import dev.nklip.javacraft.shardshop.testing.cucumber.config.CatalogDatabase;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.ShardShopFiles;
import io.cucumber.java.After;
import io.cucumber.java.en.Then;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Retained streams upgrade through their real migration owners without rewriting business or transport rows. */
public final class TransportCleanupMigrationStepDefinitions {
    private final CatalogDatabase postgres = CatalogDatabase.instance();
    private String database;

    @After("@transport-cleanup-migration")
    public void cleanup() throws Exception {
        if (database != null) postgres.dropDatabase(database);
    }

    @Then("ordering cleanup indexes preserve the retained V7 rows and rerun without changes")
    public void orderingUpgrade() throws Exception {
        database = postgres.createDatabase();
        Path source = ShardShopFiles.resolve("database/shard/ordering");
        String migration = "V8__transport_cleanup_indexes.sql";
        Path retained = without(source, migration);
        Map<String, String> placement = Map.of("shardRegion", "US", "shardIndex", "0", "shardCount", "1");
        postgres.migrate(database, retained, placement, "ordering");
        List<String> tables = List.of("ordering.buyers", "ordering.order_allocations", "ordering.orders",
                "ordering.order_sagas", "ordering.order_result_ids", "ordering.order_inbox", "ordering.order_outbox");
        Map<String, String> before;
        try (Connection connection = postgres.superuser(database); Statement sql = connection.createStatement()) {
            sql.executeUpdate("INSERT INTO ordering.buyers(buyer_id,name) VALUES (1,'Retained buyer')");
            sql.executeUpdate("INSERT INTO ordering.order_allocations(order_id,buyer_id,run_name,request_ordinal) "
                    + "VALUES (2,1,'retained',0)");
            sql.executeUpdate("INSERT INTO ordering.orders(order_id,buyer_id,request_fingerprint,currency,total_amount,status) "
                    + "VALUES (2,1,'retained','USD',1,'PENDING_LEDGER')");
            sql.executeUpdate("INSERT INTO ordering.order_sagas(saga_id,order_id,command_id,status,reconciliation_attempts) "
                    + "VALUES (3,2,4,'PENDING_LEDGER',5)");
            sql.executeUpdate("INSERT INTO ordering.order_result_ids(result_message_id,message_id,saga_id,received_envelope) "
                    + "VALUES (6,5,3,'{\"retained\":true}')");
            sql.executeUpdate("INSERT INTO ordering.order_outbox(message_id,result_message_id,saga_id,envelope) "
                    + "VALUES (5,6,3,'{\"retained\":true}')");
            sql.executeUpdate("UPDATE ordering.order_outbox SET published_at=clock_timestamp()");
            sql.executeUpdate("INSERT INTO ordering.order_inbox(message_id,saga_id,envelope) "
                    + "VALUES (6,3,'{\"retained\":true}')");
            before = snapshot(connection, tables);
        }

        Files.copy(source.resolve(migration), retained.resolve(migration));
        assertEquals(1, postgres.migrate(database, retained, placement, "ordering").migrationsExecuted);
        assertEquals(0, postgres.migrate(database, retained, placement, "ordering").migrationsExecuted);
        try (Connection connection = postgres.superuser(database)) {
            assertEquals(before, snapshot(connection, tables));
            assertIndexes(connection, "ordering", "order_inbox_retention_idx", "order_outbox_retention_idx");
        }
    }

    @Then("ledger cleanup indexes preserve the retained V2 rows and rerun without changes")
    public void ledgerUpgrade() throws Exception {
        database = postgres.createLedgerDatabase();
        Path source = ShardShopFiles.resolve("database/ledger/ledger");
        String migration = "V3__transport_cleanup_indexes.sql";
        Path retained = without(source, migration);
        postgres.migrateLedger(database, retained);
        List<String> tables = List.of("ledger.ledger_operations", "ledger.ledger_result_ids",
                "ledger.ledger_inbox", "ledger.ledger_outbox");
        Map<String, String> before;
        try (Connection connection = postgres.superuser(database); Statement sql = connection.createStatement()) {
            sql.executeUpdate("INSERT INTO ledger.ledger_operations(order_id,buyer_id,saga_id,command_id,"
                    + "request_fingerprint,snapshot,amount,currency,outcome,rejection_reason,result_schema_version,result_data) "
                    + "VALUES (2,1,3,4,'retained','{}',1,'USD','REJECTED','UNSUPPORTED_CURRENCY',1,'{}')");
            sql.executeUpdate("INSERT INTO ledger.ledger_result_ids(result_message_id,message_id,order_id,envelope,"
                    + "command_envelope,publication_attempt) VALUES (6,5,2,'{\"retained\":true}','{\"retained\":true}',3)");
            sql.executeUpdate("INSERT INTO ledger.ledger_inbox(message_id,result_message_id,envelope) "
                    + "VALUES (5,6,'{\"retained\":true}')");
            sql.executeUpdate("INSERT INTO ledger.ledger_outbox(message_id,publication_attempt) VALUES (6,3)");
            sql.executeUpdate("UPDATE ledger.ledger_outbox SET published_at=clock_timestamp()");
            before = snapshot(connection, tables);
        }

        Files.copy(source.resolve(migration), retained.resolve(migration));
        assertEquals(1, postgres.migrateLedger(database, retained).migrationsExecuted);
        assertEquals(0, postgres.migrateLedger(database, retained).migrationsExecuted);
        try (Connection connection = postgres.superuser(database)) {
            assertEquals(before, snapshot(connection, tables));
            assertIndexes(connection, "ledger", "ledger_inbox_retention_idx", "ledger_outbox_retention_idx");
        }
    }

    private static Path without(Path source, String excluded) throws Exception {
        Path retained = ShardShopFiles.newWorkDirectory("retained-cleanup-migrations-");
        try (var paths = Files.list(source)) {
            for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".sql")).toList()) {
                if (!path.getFileName().toString().equals(excluded)) {
                    Files.copy(path, retained.resolve(path.getFileName()));
                }
            }
        }
        return retained;
    }

    private static Map<String, String> snapshot(Connection connection, List<String> tables) throws Exception {
        Map<String, String> snapshot = new LinkedHashMap<>();
        try (Statement sql = connection.createStatement()) {
            for (String table : tables) {
                try (ResultSet rows = sql.executeQuery("SELECT jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text)::text FROM "
                        + table + " AS t")) {
                    assertTrue(rows.next());
                    snapshot.put(table, rows.getString(1));
                }
            }
        }
        return snapshot;
    }

    private static void assertIndexes(Connection connection, String schema, String inbox, String outbox) throws Exception {
        try (var sql = connection.prepareStatement("SELECT indexname, indexdef FROM pg_indexes "
                + "WHERE schemaname=? AND indexname IN (?,?) ORDER BY indexname")) {
            sql.setString(1, schema);
            sql.setString(2, inbox);
            sql.setString(3, outbox);
            try (ResultSet rows = sql.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(inbox, rows.getString(1));
                assertTrue(rows.getString(2).contains("(received_at, message_id)"));
                assertTrue(rows.next());
                assertEquals(outbox, rows.getString(1));
                assertTrue(rows.getString(2).contains("(published_at, message_id) WHERE (published_at IS NOT NULL)"));
            }
        }
    }
}
