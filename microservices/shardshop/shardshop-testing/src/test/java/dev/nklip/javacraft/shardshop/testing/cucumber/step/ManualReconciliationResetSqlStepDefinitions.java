package dev.nklip.javacraft.shardshop.testing.cucumber.step;

import dev.nklip.javacraft.shardshop.testing.cucumber.config.CatalogDatabase;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.CatalogRouting;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.ShardShopFiles;
import io.cucumber.java.After;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import org.testcontainers.containers.Container.ExecResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** SQL and role acceptance on the pinned PostgreSQL container, independent of packaged workers. */
public final class ManualReconciliationResetSqlStepDefinitions {
    private static final Path ORDERING = ShardShopFiles.resolve("database/shard/ordering");
    private static final Map<String, String> PLACEMENT = Map.of("shardRegion", "US", "shardIndex", "0",
            "shardCount", "1");
    private final CatalogDatabase postgres = CatalogDatabase.instance();
    private String database;
    private String ledgerDatabase;

    @Given("a migrated ordering shard for manual reconciliation SQL")
    public void migratedShard() throws Exception {
        database = postgres.createDatabase();
        postgres.migrate(database, ORDERING, PLACEMENT, "ordering");
    }

    @After("@manual-reconciliation-reset-sql")
    public void cleanup() throws Exception {
        if (ledgerDatabase != null) postgres.dropDatabase(ledgerDatabase);
        if (database != null) postgres.dropDatabase(database);
    }

    @Then("the manual reset changes only the selected pending saga retry coordinates")
    public void selectedPair() throws Exception {
        seed(100, 101, "PENDING_LEDGER", "PENDING_LEDGER");
        seed(200, 201, "PENDING_LEDGER", "PENDING_LEDGER");
        Map<String, String> before = snapshotMaskingSelectedSaga();
        String other = row("ordering.order_sagas", "saga_id", 201);
        String immutable = selectedImmutable(101);
        Instant oldDue = due(101);
        Instant beforeReset = databaseTime();

        assertTrue(postgres.resetReconciliation(database, 100, 101).startsWith("RESET|100|101|5|"));
        assertEquals(0, attempts(101));
        assertTrue(due(101).isBefore(oldDue));
        assertFalse(due(101).isBefore(beforeReset));
        assertFalse(due(101).isAfter(databaseTime()));
        assertEquals(immutable, selectedImmutable(101));
        assertEquals(other, row("ordering.order_sagas", "saga_id", 201));
        assertEquals(before, snapshotMaskingSelectedSaga());

        assertTrue(postgres.resetReconciliation(database, 100, 101).startsWith("RESET|100|101|0|"));
        assertEquals(0, attempts(101));
        assertEquals(immutable, selectedImmutable(101));
        assertEquals(other, row("ordering.order_sagas", "saga_id", 201));
        assertEquals(before, snapshotMaskingSelectedSaga());
    }

    @Then("the manual reset refuses missing mismatched and terminal pairs")
    public void noOps() throws Exception {
        seed(100, 101, "PENDING_LEDGER", "PENDING_LEDGER");
        seed(200, 201, "CONFIRMED", "CONFIRMED");
        seed(300, 301, "PENDING_LEDGER", "CONFIRMED");
        seed(400, 401, "CONFIRMED", "PENDING_LEDGER");
        Map<String, String> before = snapshot();
        for (long[] pair : new long[][] {{999, 999}, {100, 201}, {200, 201}, {300, 301}, {400, 401}}) {
            assertEquals("NOOP|" + pair[0] + "|" + pair[1],
                    postgres.resetReconciliation(database, pair[0], pair[1]));
            assertEquals(before, snapshot(), "No-op must not change any retained row");
        }
    }

    @Then("the manual reset rejects invalid identifiers without writes")
    public void invalidIds() throws Exception {
        seed(100, 101, "PENDING_LEDGER", "PENDING_LEDGER");
        Map<String, String> before = snapshot();
        for (String[] pair : new String[][] {
                {"", "101"}, {"0", "101"}, {"+100", "101"}, {"0100", "101"}, {"100\n", "101"},
                {"9223372036854775808", "101"},
                {"100", "-101"}, {"100", "101;UPDATE ordering.order_sagas SET status='CONFIRMED'"},
                {"100'::bigint OR true --", "101"}
        }) {
            ExecResult result = postgres.resetReconciliation(database, pair[0], pair[1]);
            assertNotEquals(0, result.getExitCode(), "Invalid identifier must fail: " + pair[0] + ", " + pair[1]);
            assertFalse(result.getStdout().contains("RESET|"));
            assertEquals(before, snapshot(), "Invalid identifier wrote retained data");
        }
    }

    @Then("the manual reset rechecks status after waiting for the order lock")
    public void advisoryLock() throws Exception {
        seed(100, 101, "PENDING_LEDGER", "PENDING_LEDGER");
        try (Connection holder = postgres.superuser(database)) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                lock.setLong(1, CatalogRouting.creationLock("order", "", "100"));
                lock.execute();
            }
            CompletableFuture<String> reset = CompletableFuture.supplyAsync(() -> reset(100, 101));
            try {
                awaitLock("advisory");
                try (Connection changing = postgres.superuser(database); Statement sql = changing.createStatement()) {
                    sql.executeUpdate("UPDATE ordering.orders SET status='CONFIRMED' WHERE order_id=100");
                }
                holder.commit();
                assertEquals("NOOP|100|101", reset.get(10, TimeUnit.SECONDS));
                assertEquals(5, attempts(101));
            } finally {
                holder.rollback();
                reset.cancel(true);
            }
        }
    }

    @Then("the manual reset times out at a locked saga without writes")
    public void sagaRowLock() throws Exception {
        seed(100, 101, "PENDING_LEDGER", "PENDING_LEDGER");
        Map<String, String> before = snapshot();
        try (Connection holder = postgres.superuser(database)) {
            holder.setAutoCommit(false);
            try (Statement sql = holder.createStatement()) {
                sql.executeQuery("SELECT saga_id FROM ordering.order_sagas WHERE saga_id=101 FOR UPDATE").close();
            }
            CompletableFuture<ExecResult> reset = CompletableFuture.supplyAsync(() -> rawReset("100", "101"));
            try {
                awaitRowLock();
                ExecResult result = reset.get(10, TimeUnit.SECONDS);
                assertNotEquals(0, result.getExitCode());
                assertTrue(result.getStderr().contains("lock timeout"), result.getStderr());
                assertFalse(result.getStdout().contains("RESET|"));
            } finally {
                holder.rollback();
                reset.cancel(true);
            }
        }
        assertEquals(before, snapshot(), "Timed-out transaction must roll back");
    }

    @Then("the manual operator has only the reconciliation privilege matrix")
    public void privileges() throws Exception {
        seed(100, 101, "PENDING_LEDGER", "PENDING_LEDGER");
        postgres.migrate(database, ShardShopFiles.resolve("database/shard/catalog"), PLACEMENT, "catalog");
        ledgerDatabase = postgres.createLedgerDatabase();
        postgres.migrateLedger(ledgerDatabase, ShardShopFiles.resolve("database/ledger/ledger"));
        Map<String, String> before = snapshot();
        try (Connection operator = postgres.orderingOperator(database)) {
            assertEquals("ordering_operator", scalar(operator, "SELECT current_user"));
            assertEquals("PENDING_LEDGER", scalar(operator,
                    "SELECT status FROM ordering.orders WHERE order_id=100"));
            assertEquals("5", scalar(operator,
                    "SELECT reconciliation_attempts FROM ordering.order_sagas WHERE saga_id=101"));
            denied(operator, "CREATE TABLE ordering.forbidden (id bigint)");
            denied(operator, "INSERT INTO ordering.orders(order_id) VALUES (900)");
            denied(operator, "DELETE FROM ordering.order_sagas WHERE saga_id=101");
            denied(operator, "UPDATE ordering.order_sagas SET status='CONFIRMED' WHERE saga_id=101");
            denied(operator, "UPDATE ordering.orders SET status='CONFIRMED' WHERE order_id=100");
            denied(operator, "SELECT buyer_id FROM ordering.orders WHERE order_id=100");
            denied(operator, "SELECT email FROM ordering.buyers WHERE buyer_id=1");
            denied(operator, "SELECT ledger_result FROM ordering.order_sagas WHERE saga_id=101");
            denied(operator, "SELECT envelope FROM ordering.order_outbox");
            denied(operator, "SELECT received_envelope FROM ordering.order_result_ids");
            denied(operator, "SELECT name FROM catalog.sellers");
            denied(operator, "SET ROLE ordering_owner");
        }
        try (Connection operator = postgres.orderingOperator(ledgerDatabase)) {
            denied(operator, "SELECT * FROM ledger.ledger_operations");
        }
        try (Connection order = postgres.order(database)) {
            denied(order, "SET ROLE ordering_reconciler");
        }
        try (Connection product = runtime(database, "product_app", postgres.productPassword());
             Connection cdc = runtime(database, "ordering_cdc", postgres.cdcPassword());
             Connection ledger = runtime(ledgerDatabase, "ledger_app", postgres.ledgerPassword())) {
            denied(product, "SET ROLE ordering_reconciler");
            denied(cdc, "SET ROLE ordering_reconciler");
            denied(ledger, "SET ROLE ordering_reconciler");
        }
        assertEquals(before, snapshot());
        try (Connection admin = postgres.superuser(database)) {
            assertEquals("f", scalar(admin, "SELECT pg_has_role('ordering_operator', 'ordering_owner', 'MEMBER')"));
            for (String runtime : new String[] {"order_app", "product_app", "ledger_app", "ordering_cdc"}) {
                assertEquals("f", scalar(admin, "SELECT pg_has_role('" + runtime
                        + "', 'ordering_reconciler', 'MEMBER')"), runtime);
            }
            assertEquals("f", scalar(admin, "SELECT has_table_privilege('ordering_operator', 'ordering.orders', 'UPDATE')"));
            assertEquals("t", scalar(admin, "SELECT has_column_privilege('ordering_operator', "
                    + "'ordering.order_sagas', 'reconciliation_attempts', 'UPDATE')"));
            assertEquals("t", scalar(admin, "SELECT has_column_privilege('ordering_operator', "
                    + "'ordering.order_sagas', 'next_reconciliation_at', 'UPDATE')"));
            assertEquals("next_reconciliation_at,reconciliation_attempts", scalar(admin,
                    "SELECT string_agg(attname, ',' ORDER BY attname) FROM pg_attribute "
                            + "WHERE attrelid='ordering.order_sagas'::regclass AND attnum>0 AND NOT attisdropped "
                            + "AND has_column_privilege('ordering_operator', attrelid, attnum, 'UPDATE')"));
        }
    }

    @Then("ordering grants remove stale operator privileges without changing rows")
    public void repeatableGrants() throws Exception {
        seed(100, 101, "PENDING_LEDGER", "PENDING_LEDGER");
        Map<String, String> before = snapshot();
        try (Connection admin = postgres.superuser(database); Statement sql = admin.createStatement()) {
            sql.execute("SET ROLE ordering_owner");
            sql.execute("GRANT UPDATE (status) ON ordering.orders TO ordering_reconciler");
            sql.execute("GRANT SELECT (ledger_result) ON ordering.order_sagas TO ordering_reconciler");
            sql.execute("RESET ROLE");
            assertEquals("t", scalar(admin, "SELECT has_column_privilege('ordering_operator', "
                    + "'ordering.orders', 'status', 'UPDATE')"));
            assertEquals("t", scalar(admin, "SELECT has_column_privilege('ordering_operator', "
                    + "'ordering.order_sagas', 'ledger_result', 'SELECT')"));
        }
        Path retained = ShardShopFiles.newWorkDirectory("retained-reconciliation-grants-");
        try (var sqlFiles = Files.list(ORDERING)) {
            for (Path file : sqlFiles.filter(p -> p.getFileName().toString().endsWith(".sql")).toList()) {
                Path copy = retained.resolve(file.getFileName());
                Files.copy(file, copy);
                if (file.getFileName().toString().equals("R__ordering_grants.sql")) {
                    Files.writeString(copy, "\n-- Reapply privileges to an existing retained schema.\n",
                            java.nio.file.StandardOpenOption.APPEND);
                }
            }
        }
        assertEquals(1, postgres.migrate(database, retained, PLACEMENT, "ordering").migrationsExecuted);
        try (Connection admin = postgres.superuser(database)) {
            assertEquals("f", scalar(admin, "SELECT has_column_privilege('ordering_operator', "
                    + "'ordering.orders', 'status', 'UPDATE')"));
            assertEquals("f", scalar(admin, "SELECT has_column_privilege('ordering_operator', "
                    + "'ordering.order_sagas', 'ledger_result', 'SELECT')"));
        }
        assertEquals(before, snapshot());
        assertEquals(0, postgres.migrate(database, retained, PLACEMENT, "ordering").migrationsExecuted);
        assertEquals(before, snapshot());
    }

    private void seed(long orderId, long sagaId, String orderStatus, String sagaStatus) throws Exception {
        try (Connection connection = postgres.superuser(database); Statement sql = connection.createStatement()) {
            sql.executeUpdate("INSERT INTO ordering.buyers(buyer_id,name) VALUES (1,'SQL test buyer') ON CONFLICT DO NOTHING");
            sql.executeUpdate("INSERT INTO ordering.order_allocations(order_id,buyer_id,run_name,request_ordinal) "
                    + "VALUES (" + orderId + ",1,'manual-reset'," + orderId + ")");
            sql.executeUpdate("INSERT INTO ordering.orders(order_id,buyer_id,request_fingerprint,currency,total_amount,status) "
                    + "VALUES (" + orderId + ",1,'retained','USD',1,'" + orderStatus + "')");
            sql.executeUpdate("INSERT INTO ordering.order_sagas(saga_id,order_id,command_id,status,"
                    + "reconciliation_attempts,next_reconciliation_at,ledger_result) VALUES (" + sagaId + ","
                    + orderId + "," + (sagaId + 1000) + ",'" + sagaStatus + "',5,"
                    + "clock_timestamp() + interval '1 day','{\"retained\":true}')");
        }
    }

    private int attempts(long sagaId) throws Exception {
        try (Connection connection = postgres.superuser(database)) {
            return Integer.parseInt(scalar(connection,
                    "SELECT reconciliation_attempts FROM ordering.order_sagas WHERE saga_id=" + sagaId));
        }
    }

    private Instant due(long sagaId) throws Exception {
        try (Connection connection = postgres.superuser(database)) {
            return Instant.parse(scalar(connection, "SELECT to_char(next_reconciliation_at AT TIME ZONE 'UTC', "
                    + "'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"') FROM ordering.order_sagas WHERE saga_id=" + sagaId));
        }
    }

    private Instant databaseTime() throws Exception {
        try (Connection connection = postgres.superuser(database);
             Statement sql = connection.createStatement();
             ResultSet rows = sql.executeQuery("SELECT clock_timestamp()")) {
            assertTrue(rows.next());
            return rows.getTimestamp(1).toInstant();
        }
    }

    private String selectedImmutable(long sagaId) throws Exception {
        try (Connection connection = postgres.superuser(database)) {
            return scalar(connection, "SELECT (to_jsonb(s) - 'reconciliation_attempts' - 'next_reconciliation_at')::text "
                    + "FROM ordering.order_sagas s WHERE saga_id=" + sagaId);
        }
    }

    private String row(String table, String key, long id) throws Exception {
        try (Connection connection = postgres.superuser(database)) {
            return scalar(connection, "SELECT to_jsonb(t)::text FROM " + table + " t WHERE " + key + "=" + id);
        }
    }

    private Map<String, String> snapshot() throws Exception {
        Map<String, String> rows = new LinkedHashMap<>();
        try (Connection connection = postgres.superuser(database); Statement sql = connection.createStatement();
             ResultSet tables = sql.executeQuery("SELECT tablename FROM pg_tables WHERE schemaname='ordering' "
                     + "AND tablename <> 'flyway_schema_history' ORDER BY tablename")) {
            while (tables.next()) {
                String table = tables.getString(1);
                rows.put(table, scalar(connection, "SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY "
                        + "to_jsonb(t)::text)::text, '[]') FROM ordering." + table + " t"));
            }
        }
        return rows;
    }

    private Map<String, String> snapshotMaskingSelectedSaga() throws Exception {
        Map<String, String> rows = snapshot();
        try (Connection connection = postgres.superuser(database)) {
            rows.put("order_sagas", scalar(connection,
                    "SELECT jsonb_agg(CASE WHEN saga_id=101 THEN "
                            + "to_jsonb(s) - 'reconciliation_attempts' - 'next_reconciliation_at' "
                            + "ELSE to_jsonb(s) END ORDER BY saga_id)::text FROM ordering.order_sagas s"));
        }
        return rows;
    }

    private void awaitLock(String event) throws Exception {
        awaitWait("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() "
                + "AND usename='ordering_operator' AND wait_event_type='Lock' AND wait_event='" + event + "'");
    }

    private void awaitRowLock() throws Exception {
        awaitWait("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() "
                + "AND usename='ordering_operator' AND wait_event_type='Lock' "
                + "AND wait_event IN ('transactionid','tuple')");
    }

    private void awaitWait(String query) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        try (Connection admin = postgres.superuser(database)) {
            while (System.nanoTime() < deadline) {
                if (Integer.parseInt(scalar(admin, query)) > 0) return;
                Thread.sleep(20);
            }
        }
        fail("Operator never reached expected PostgreSQL lock wait: " + query);
    }

    private Connection runtime(String targetDatabase, String role, String password) throws SQLException {
        return DriverManager.getConnection("jdbc:postgresql://localhost:" + postgres.port() + "/" + targetDatabase
                        + "?sslmode=verify-full&sslrootcert=" + postgres.trustedCa()
                        + "&connectTimeout=5&socketTimeout=10", role, password);
    }

    private String reset(long orderId, long sagaId) {
        try {
            return postgres.resetReconciliation(database, orderId, sagaId);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private ExecResult rawReset(String orderId, String sagaId) {
        try {
            return postgres.resetReconciliation(database, orderId, sagaId);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String scalar(Connection connection, String query) throws SQLException {
        try (Statement sql = connection.createStatement(); ResultSet rows = sql.executeQuery(query)) {
            assertTrue(rows.next(), query);
            return rows.getString(1);
        }
    }

    private static void denied(Connection connection, String query) throws SQLException {
        try (Statement sql = connection.createStatement()) {
            sql.execute(query);
            fail("Operator could execute: " + query);
        } catch (SQLException failure) {
            assertEquals("42501", failure.getSQLState(), query + ": " + failure.getMessage());
        }
    }
}
