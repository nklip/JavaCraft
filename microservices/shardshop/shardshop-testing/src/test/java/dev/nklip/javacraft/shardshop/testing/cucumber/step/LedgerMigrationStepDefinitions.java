package dev.nklip.javacraft.shardshop.testing.cucumber.step;

import dev.nklip.javacraft.shardshop.testing.cucumber.config.CatalogDatabase;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.Json;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.ShardShopFiles;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.Contracts;
import io.cucumber.java.After;
import io.cucumber.java.en.Then;
import io.vertx.core.json.JsonObject;
import org.flywaydb.core.api.FlywayException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/** V1 installations may upgrade only when every permanent binding can recover its original command. */
public final class LedgerMigrationStepDefinitions {
    private final CatalogDatabase postgres = CatalogDatabase.instance();
    private String database;

    @After("@ledger-migration")
    public void cleanup() throws Exception {
        if (database != null) postgres.dropDatabase(database);
    }

    @Then("a V1 binding with a retained inbox command keeps that command after V2")
    public void backfill() throws Exception {
        database = postgres.createLedgerDatabase();
        Path migrations = v1Stream();
        postgres.migrateLedger(database, migrations);
        JsonObject command = command();
        insertLegacyBinding(command, true);
        addV2(migrations);
        assertEquals(1, postgres.migrateLedger(database, migrations).migrationsExecuted);
        try (var connection = postgres.superuser(database);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT command_envelope::text FROM ledger.ledger_result_ids WHERE message_id=?")) {
            statement.setLong(1, Long.parseLong(command.getString("messageId")));
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(Json.object(command.encode()), Json.object(rows.getString(1)));
            }
        }
    }

    @Then("V2 fails for a V1 binding without an inbox command and leaves the V1 schema")
    public void missingCommand() throws Exception {
        database = postgres.createLedgerDatabase();
        Path migrations = v1Stream();
        postgres.migrateLedger(database, migrations);
        insertLegacyBinding(command(), false);
        addV2(migrations);
        FlywayException failure = assertThrows(FlywayException.class, () -> postgres.migrateLedger(database, migrations));
        assertTrue(failure.getMessage().contains("command_envelope"), failure.getMessage());
        try (var connection = postgres.superuser(database); Statement statement = connection.createStatement()) {
            try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM information_schema.columns "
                    + "WHERE table_schema = 'ledger' AND table_name = 'ledger_result_ids' AND column_name = 'command_envelope'")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1), "The failed V2 must roll back completely");
            }
            try (ResultSet rows = statement.executeQuery("SELECT string_agg(coalesce(version, 'R'), ',' ORDER BY installed_rank) "
                    + "FROM ledger.flyway_schema_history WHERE success")) {
                assertTrue(rows.next());
                assertEquals("1,R", rows.getString(1));
            }
        }
    }

    private static Path v1Stream() throws Exception {
        Path directory = ShardShopFiles.newWorkDirectory("ledger-migrations-");
        Path source = ShardShopFiles.resolve("database/ledger/ledger");
        Files.copy(source.resolve("V1__ledger.sql"), directory.resolve("V1__ledger.sql"));
        Files.copy(source.resolve("R__ledger_grants.sql"), directory.resolve("R__ledger_grants.sql"));
        return directory;
    }

    private static void addV2(Path directory) throws Exception {
        Files.copy(ShardShopFiles.resolve("database/ledger/ledger/V2__permanent_command_envelopes.sql"),
                directory.resolve("V2__permanent_command_envelopes.sql"));
    }

    private static JsonObject command() {
        return Contracts.message("shardshop-order", "record-order.json");
    }

    private void insertLegacyBinding(JsonObject command, boolean retainInbox) throws Exception {
        JsonObject result = Contracts.message("shardshop-ledger", "ledger-recorded.json");
        try (var connection = postgres.superuser(database)) {
            try (PreparedStatement operation = connection.prepareStatement("INSERT INTO ledger.ledger_operations "
                    + "(order_id,buyer_id,saga_id,command_id,request_fingerprint,snapshot,amount,currency,outcome,"
                    + "result_schema_version,result_data) VALUES (?,?,?,?,?,?::jsonb,39.90,'EUR','RECORDED',1,'{}'::jsonb)")) {
                operation.setLong(1, Long.parseLong(command.getString("orderId")));
                operation.setLong(2, Long.parseLong(command.getString("buyerId")));
                operation.setLong(3, Long.parseLong(command.getString("sagaId")));
                operation.setLong(4, Long.parseLong(command.getString("commandId")));
                operation.setString(5, command.getString("requestFingerprint"));
                operation.setString(6, command.getJsonObject("snapshot").encode());
                operation.executeUpdate();
            }
            try (PreparedStatement binding = connection.prepareStatement("INSERT INTO ledger.ledger_result_ids "
                    + "(result_message_id,message_id,order_id,envelope) VALUES (?,?,?,?::jsonb)")) {
                binding.setLong(1, Long.parseLong(command.getString("resultMessageId")));
                binding.setLong(2, Long.parseLong(command.getString("messageId")));
                binding.setLong(3, Long.parseLong(command.getString("orderId")));
                binding.setString(4, result.encode());
                binding.executeUpdate();
            }
            if (retainInbox) {
                try (PreparedStatement inbox = connection.prepareStatement("INSERT INTO ledger.ledger_inbox "
                        + "(message_id,result_message_id,envelope) VALUES (?,?,?::jsonb)")) {
                    inbox.setLong(1, Long.parseLong(command.getString("messageId")));
                    inbox.setLong(2, Long.parseLong(command.getString("resultMessageId")));
                    inbox.setString(3, command.encode());
                    inbox.executeUpdate();
                }
            }
        }
    }
}
