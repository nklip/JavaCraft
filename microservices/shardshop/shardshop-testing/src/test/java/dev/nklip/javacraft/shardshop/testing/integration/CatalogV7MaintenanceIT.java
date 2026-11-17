package dev.nklip.javacraft.shardshop.testing.integration;

import dev.nklip.javacraft.shardshop.testing.integration.world.CatalogShards;
import dev.nklip.javacraft.shardshop.testing.support.CatalogDatabase;
import dev.nklip.javacraft.shardshop.testing.support.ShardShopFiles;
import dev.nklip.javacraft.shardshop.testing.support.SqlRows;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.callback.BaseCallback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the shipped maintenance configuration against retained V6 data, without a large-history benchmark. */
@Timeout(55)
class CatalogV7MaintenanceIT {
    private static final Map<String, String> PLACEMENT =
            Map.of("shardRegion", "US", "shardIndex", "0", "shardCount", "1");
    @TempDir Path temporary;
    private CatalogDatabase postgres;
    private String database;
    private Path stream;

    @BeforeEach
    void retainedCatalog() throws Exception {
        postgres = CatalogDatabase.instance();
        database = postgres.createDatabase();
        stream = ShardShopFiles.resolve("database/shard/catalog");
        Path retained = CatalogShards.streamWith(temporary, "V1__catalog.sql", "V2__catalog_creation.sql",
                "V3__stock_coordination.sql", "V4__profit_posting.sql", "V5__product_browser.sql",
                "V6__seller_country_currency_policy.sql");
        postgres.migrate(database, retained, PLACEMENT);
        try (Connection connection = postgres.superuser(database); var statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO catalog.sellers(seller_id,name) VALUES(1,'Retained company');
                    INSERT INTO catalog.products(product_id,seller_id,name,price,currency,initial_stock,stock)
                    VALUES(11,1,'Signed total',1.00,'USD',10,10),(12,1,'Zero total',1.00,'USD',10,10);
                    INSERT INTO catalog.profit_credits(order_id,product_id,seller_id,currency,amount)
                    VALUES(101,11,1,'USD',0.10),(102,11,1,'USD',-7.45),(103,12,1,'USD',0.00);
                    """);
        }
    }

    @AfterEach
    void cleanup() throws SQLException {
        if (database != null) postgres.dropDatabase(database);
    }

    @Test
    @Timeout(180)
    void pinnedCliLoadsTheMaintenanceFileBesideTomlAndRerunsWithoutChangingHistory() throws Exception {
        Path cliStream = Files.createDirectories(temporary.resolve("cli-stream"));
        try (var files = Files.list(stream)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Files.copy(file, cliStream.resolve(file.getFileName()));
            }
        }
        // Test-only probes leave all shipped migration bytes and both configuration files intact.
        Files.writeString(cliStream.resolve("beforeEachMigrate__maintenance_settings.sql"), """
                SELECT 1 / CASE WHEN current_setting('statement_timeout')='2min'
                    AND current_setting('lock_timeout')='5s'
                    AND current_user='catalog_owner' THEN 1 ELSE 0 END;
                """);
        Files.writeString(cliStream.resolve("V8__must_remain_above_maintenance_target.sql"), "SELECT 1/0;\n");

        var missing = postgres.migrateCatalogCli(database, cliStream, "/flyway/sql/missing-maintenance.conf");
        assertTrue(missing.exitCode() != 0, missing.output());
        assertTrue(missing.output().contains("missing-maintenance.conf"), missing.output());
        assertEquals("6", query("SELECT max(version::int) FROM catalog.flyway_schema_history"),
                "A miswired configuration argument must fail before migrating the retained database");

        var applied = postgres.migrateCatalogCli(database, cliStream, "/flyway/sql/flyway-v7-maintenance.conf");
        assertEquals(0, applied.exitCode(), applied.output());
        assertEquals("1;2;3;4;5;6;7", query("SELECT version FROM catalog.flyway_schema_history "
                + "WHERE version IS NOT NULL AND success ORDER BY installed_rank"),
                "The maintenance target must exclude the failing V8 sentinel");
        assertTotalsAndFence();
        String history = query("SELECT installed_rank,version,description,checksum,success "
                + "FROM catalog.flyway_schema_history ORDER BY installed_rank");

        var rerun = postgres.migrateCatalogCli(database, cliStream, "/flyway/sql/flyway-v7-maintenance.conf");
        assertEquals(0, rerun.exitCode(), rerun.output());
        assertEquals(history, query("SELECT installed_rank,version,description,checksum,success "
                + "FROM catalog.flyway_schema_history ORDER BY installed_rank"),
                "The CLI rerun must validate the original checksums and add no history");
        assertTotalsAndFence();
    }

    @Test
    void maintenanceAllowsAStatementBeyondNormalBudgetAndNormalMigrationsStayBounded() throws Exception {
        AtomicBoolean delayed = new AtomicBoolean();
        Flyway maintenance = maintenance(new BaseCallback() {
            @Override
            public boolean supports(Event event, Context context) {
                return event == Event.BEFORE_EACH_MIGRATE && isV7(context);
            }

            @Override
            public void handle(Event event, Context context) {
                try (var statement = context.getConnection().createStatement()) {
                    assertEquals("2min", SqlRows.single(statement.executeQuery("SHOW statement_timeout")));
                    assertEquals("5s", SqlRows.single(statement.executeQuery("SHOW lock_timeout")));
                    // Controlled SQL demonstrates the actual migration connection survives the ordinary 15-second limit.
                    statement.execute("SELECT pg_sleep(16)");
                    delayed.set(true);
                } catch (SQLException failure) {
                    throw new FlywayException("Maintenance statement failed", failure);
                }
            }
        });
        assertEquals("7", maintenance.getConfiguration().getTarget().toString());
        assertEquals("150", maintenance.getConfiguration().getJdbcProperties().get("socketTimeout"));
        assertEquals(2, maintenance.migrate().migrationsExecuted);
        assertTrue(delayed.get(), "The delay must run on the V7 migration connection");
        assertTotalsAndFence();
        String checksum = query("SELECT checksum FROM catalog.flyway_schema_history WHERE version='7'");
        assertEquals(0, postgres.migrate(database, stream, PLACEMENT).migrationsExecuted,
                "The normal stream must validate the original V7 checksum and perform no work");
        assertEquals(checksum, query("SELECT checksum FROM catalog.flyway_schema_history WHERE version='7'"));
        assertTotalsAndFence();

        Path probe = temporary.resolve("normal-settings");
        Files.createDirectories(probe);
        Files.writeString(probe.resolve("beforeMigrate__normal_settings.sql"), """
                SELECT 1 / CASE WHEN current_setting('statement_timeout')='15s'
                    AND current_setting('lock_timeout')='5s' THEN 1 ELSE 0 END;
                """);
        try (var files = Files.list(stream)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".sql")).toList()) {
                Files.copy(file, probe.resolve(file.getFileName()));
            }
        }
        assertEquals(0, postgres.migrate(database, probe, PLACEMENT).migrationsExecuted,
                "A later normal session must retain its ordinary statement and lock bounds");
    }

    @Test
    void failedMaintenanceRollsBackTheBackfillFenceAndHistoryBeforeRetry() throws Exception {
        Flyway failing = maintenance(new BaseCallback() {
            @Override
            public boolean supports(Event event, Context context) {
                return event == Event.AFTER_EACH_MIGRATE && isV7(context);
            }

            @Override
            public void handle(Event event, Context context) {
                try (var statement = context.getConnection().createStatement()) {
                    statement.execute("SELECT 1/0");
                } catch (SQLException failure) {
                    throw new FlywayException("Controlled failure before V7 commit", failure);
                }
            }
        });
        assertThrows(FlywayException.class, failing::migrate);
        assertEquals("null", query("SELECT to_regclass('catalog.product_profits')"));
        assertEquals("0", query("SELECT count(*) FROM information_schema.columns "
                + "WHERE table_schema='catalog' AND table_name='profit_credits' AND column_name='product_total_applied'"));
        assertEquals("0", query("SELECT count(*) FROM catalog.flyway_schema_history WHERE version='7'"));
        assertEquals("3", query("SELECT count(*) FROM catalog.profit_credits"));
        assertEquals(2, maintenance().migrate().migrationsExecuted);
        assertTotalsAndFence();
    }

    private Flyway maintenance(BaseCallback... callbacks) throws IOException {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(stream.resolve("flyway-v7-maintenance.conf"))) {
            properties.load(reader);
        }
        return postgres.catalogConfiguration(database, stream, properties).callbacks(callbacks).load();
    }

    private static boolean isV7(Context context) {
        return context.getMigrationInfo() != null && context.getMigrationInfo().getVersion() != null
                && context.getMigrationInfo().getVersion().toString().equals("7");
    }

    private void assertTotalsAndFence() throws SQLException {
        assertEquals("11,USD,-7.35;12,USD,0.00",
                query("SELECT product_id,currency,amount FROM catalog.product_profits ORDER BY product_id"));
        assertEquals("3,t", query("SELECT count(*),bool_and(product_total_applied) FROM catalog.profit_credits"));
        try (Connection connection = postgres.order(database); var statement = connection.createStatement()) {
            SQLException failure = assertThrows(SQLException.class, () -> statement.execute("""
                    INSERT INTO catalog.profit_credits(order_id,product_id,seller_id,currency,amount)
                    VALUES(104,11,1,'USD',1.00)
                    """));
            assertEquals("23514", failure.getSQLState());
        }
    }

    private String query(String sql) throws SQLException {
        try (Connection connection = postgres.superuser(database); var statement = connection.createStatement()) {
            return SqlRows.render(statement.executeQuery(sql));
        }
    }
}
