package dev.nklip.javacraft.shardshop.testing.cucumber.step;

import dev.nklip.javacraft.shardshop.common.CurrencyPolicy;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.CatalogRouting;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.Placeholders;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.ShardShopFiles;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.SqlRows;
import dev.nklip.javacraft.shardshop.testing.cucumber.world.CatalogShards;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.flywaydb.core.api.output.MigrateResult;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class CatalogMigrationStepDefinitions {
    private static final Pattern ROLE = Pattern.compile("[a-z_]+");

    private final CatalogShards shards;
    // Expected legacy report reasons by shard, seller ID and product ID ("-" for a seller without products).
    private final Map<String, String> policyReasons = new TreeMap<>();
    private MigrateResult migration;

    public CatalogMigrationStepDefinitions(CatalogShards shards) {
        this.shards = shards;
    }

    @Given("a new shard database with only V1 of the catalog stream")
    public void databaseWithV1() throws SQLException, IOException {
        CatalogShards.Shard shard = shards.createSingle();
        Path v1 = CatalogShards.streamWith(ShardShopFiles.newWorkDirectory("migration-"), "V1__catalog.sql");
        assertEquals(List.of("1 catalog"), applied(shards.migrate(shard, v1)));
    }

    @Given("a new shard database with only V1 through V4 of the catalog stream")
    public void databaseWithV4() throws SQLException, IOException {
        CatalogShards.Shard shard = shards.createSingle();
        Path v4 = CatalogShards.streamWith(ShardShopFiles.newWorkDirectory("migration-"),
                "V1__catalog.sql", "V2__catalog_creation.sql", "V3__stock_coordination.sql", "V4__profit_posting.sql");
        assertEquals(List.of("1 catalog", "2 catalog creation", "3 stock coordination", "4 profit posting"),
                applied(shards.migrate(shard, v4)));
    }

    @Given("a new shard database with only V1 through V5 of the catalog stream")
    public void databaseWithV5() throws SQLException, IOException {
        CatalogShards.Shard shard = shards.createSingle();
        Path v5 = CatalogShards.streamWith(ShardShopFiles.newWorkDirectory("migration-"),
                "V1__catalog.sql", "V2__catalog_creation.sql", "V3__stock_coordination.sql", "V4__profit_posting.sql", "V5__product_browser.sql");
        shards.migrate(shard, v5);
    }

    @Given("a new shard database that Flyway migrated")
    public void migratedDatabase() throws SQLException {
        shards.migrate(shards.createSingle(), CatalogShards.CATALOG_STREAM);
    }

    @Given("regional shard databases {string} that Flyway migrated")
    public void regionalDatabases(String inventory) throws SQLException {
        shards.create(inventory);
    }

    @When("catalog_owner adds a seller for every ISO country and one without a country in each region, each with products in all supported currencies")
    public void addPolicyCases() throws SQLException {
        List<String> currencies = new ArrayList<>(CurrencyPolicy.SUPPORTED_CURRENCIES);
        currencies.add("AUD");
        List<String> countries = new ArrayList<>(Arrays.asList(Locale.getISOCountries()));
        countries.add(null);
        List<CatalogShards.Shard> all = shards.all();
        long sellerId = 0;
        long productId = 0;
        for (int index = 0; index < all.size(); index++) {
            CatalogShards.Shard shard = all.get(index);
            try (Connection connection = shards.superuser(shard)) {
                connection.setAutoCommit(false);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SET LOCAL ROLE catalog_owner");
                }
                try (PreparedStatement sellers = connection.prepareStatement(
                        "INSERT INTO catalog.sellers(seller_id, name, creation_key, country_code) VALUES (?, 'Policy case', ?, ?)");
                     PreparedStatement products = connection.prepareStatement("INSERT INTO catalog.products(product_id, seller_id, "
                             + "name, description, price, unit_cost, currency, initial_stock, stock, creation_key) "
                             + "VALUES (?, ?, 'Policy case', 'Policy case', ?::numeric, ?::numeric, ?, 1, 1, ?)")) {
                    for (String country : countries) {
                        // A seller without a country is reported also when it has no products.
                        for (boolean withProducts : country == null ? List.of(true, false) : List.of(true)) {
                            sellerId = nextSeller(sellerId, index, all.size());
                            sellers.setLong(1, sellerId);
                            sellers.setString(2, "policy." + (country == null ? "none" : country) + (withProducts ? "" : ".empty"));
                            sellers.setString(3, country);
                            sellers.addBatch();
                            if (!withProducts) {
                                policyReasons.put(shard.name() + "/" + sellerId + "/-", "UNRESOLVED_SELLER_COUNTRY");
                                continue;
                            }
                            for (String currency : currencies) {
                                for (List<String> amounts : List.of(List.of("20.00", "12.00"), List.of("19.95", "12.00"), List.of("20.00", "12.50"))) {
                                    products.setLong(1, ++productId);
                                    products.setLong(2, sellerId);
                                    products.setString(3, amounts.get(0));
                                    products.setString(4, amounts.get(1));
                                    products.setString(5, currency);
                                    products.setString(6, "policy." + productId);
                                    products.addBatch();
                                    policyReasons.put(shard.name() + "/" + sellerId + "/" + productId,
                                            policyReason(shard.region(), country, currency, amounts.get(0), amounts.get(1)));
                                }
                            }
                        }
                    }
                    sellers.executeBatch();
                    products.executeBatch();
                }
                connection.commit();
            }
        }
    }

    @Then("the legacy report gives each product and each seller without a country the reason of the shared currency policy")
    public void reportFollowsPolicy() throws SQLException {
        Map<String, String> reported = new TreeMap<>();
        for (CatalogShards.Shard shard : shards.all()) {
            try (Connection connection = shards.superuser(shard);
                 Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT seller_id, product_id, coalesce(legacy_reason, '') "
                         + "FROM catalog.currency_policy_legacy")) {
                while (rows.next()) {
                    String product = rows.getString(2);
                    reported.put(shard.name() + "/" + rows.getLong(1) + "/" + (product == null ? "-" : product), rows.getString(3));
                }
            }
        }
        List<String> differences = new ArrayList<>();
        for (String key : new TreeSet<>(policyReasons.keySet())) {
            if (!policyReasons.get(key).equals(reported.get(key))) {
                differences.add(key + " policy '" + policyReasons.get(key) + "' report '" + reported.get(key) + "'");
            }
        }
        assertEquals(List.of(), differences.subList(0, Math.min(5, differences.size())),
                differences.size() + " report rows differ from CurrencyPolicy");
        assertEquals(policyReasons.keySet(), reported.keySet(), "Rows of the legacy report");
    }

    /** The reason order of the product API: no country, a currency outside the policy, then minor-unit precision. */
    private static String policyReason(String region, String country, String currency, String price, String cost) {
        if (country == null) {
            return "UNRESOLVED_SELLER_COUNTRY";
        }
        if (!CurrencyPolicy.permits(region, country, currency)) {
            return "DISALLOWED_SELLER_CURRENCY";
        }
        if (!CurrencyPolicy.validAmount(new BigDecimal(price), currency) || !CurrencyPolicy.validAmount(new BigDecimal(cost), currency)) {
            return "INVALID_CURRENCY_PRECISION";
        }
        return "";
    }

    /** The next seller ID whose route is this shard, as the seller shard check of V1 requires. */
    private static long nextSeller(long after, int shardIndex, int shardCount) {
        long candidate = after + 1;
        while (CatalogRouting.sellerShard(Long.toString(candidate), shardCount) != shardIndex) {
            candidate++;
        }
        return candidate;
    }

    @Given("{word} adds these rows:")
    @Given("{word} gives these privileges of an earlier grants file:")
    public void commit(String role, String statements) throws SQLException {
        try (Connection connection = shards.superuser(shards.only())) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET LOCAL ROLE " + role(role));
                for (String sql : split(statements)) {
                    statement.execute(sql);
                }
            }
            connection.commit();
        }
    }

    @When("Flyway migrates the catalog stream")
    public void migrate() {
        migration = shards.migrate(shards.only(), CatalogShards.CATALOG_STREAM);
    }

    @When("Flyway migrates the catalog stream with a changed grants file")
    public void migrateChangedGrants() throws IOException {
        Path location = CatalogShards.streamWith(ShardShopFiles.newWorkDirectory("migration-"),
                "V1__catalog.sql", "V2__catalog_creation.sql", "V3__stock_coordination.sql",
                "V4__profit_posting.sql", "V5__product_browser.sql", "V6__seller_country_currency_policy.sql", "R__catalog_grants.sql");
        Files.writeString(location.resolve("R__catalog_grants.sql"), "\n-- A later review changed this file.\n",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        migration = shards.migrate(shards.only(), location);
    }

    @Then("Flyway applies the migrations {string}")
    public void applies(String migrations) {
        assertTrue(migration.success, "Flyway migration result");
        assertEquals(Arrays.asList(migrations.split(", ")), applied(migration));
    }

    @Then("a second Flyway migration validates the history and applies no migration")
    public void noPendingMigration() {
        MigrateResult again = shards.migrate(shards.only(), CatalogShards.CATALOG_STREAM);
        assertTrue(again.success, "Flyway migration result");
        assertEquals(0, again.migrationsExecuted, "Migrations of a matching rerun");
    }

    @Then("{word} reads these rows:")
    public void reads(String role, DataTable table) throws SQLException {
        for (Map<String, String> row : table.asMaps()) {
            assertEquals(row.get("rows"), query(role, List.of(row.get("query"))), row.get("query"));
        }
    }

    @Then("{word} reads these rows after these statements:")
    public void readsAfter(String role, DataTable table) throws SQLException {
        for (Map<String, String> row : table.asMaps()) {
            assertEquals(row.get("rows"), query(role, split(Placeholders.repeat(row.get("statements")))),
                    row.get("statements"));
        }
    }

    @Then("these statements fail with their SQLSTATE:")
    public void statementsFail(DataTable table) throws SQLException {
        for (Map<String, String> row : table.asMaps()) {
            String sql = Placeholders.repeat(row.get("statement"));
            try {
                query(row.get("role"), List.of(sql));
                fail("Expected SQLSTATE " + row.get("sqlstate") + " for " + row.get("role") + ": " + sql);
            } catch (SQLException failure) {
                assertEquals(row.get("sqlstate"), failure.getSQLState(), row.get("role") + ": " + sql + ": " + failure);
            }
        }
    }

    @Then("catalog_owner owns the catalog schema and all its tables and indexes")
    public void ownership() throws SQLException {
        assertEquals("catalog_owner,0", query("catalog_owner", List.of("SELECT nspowner::regrole, (SELECT count(*) "
                + "FROM pg_class WHERE relnamespace = n.oid AND relowner <> 'catalog_owner'::regrole) "
                + "FROM pg_namespace n WHERE nspname = 'catalog'")));
    }

    @Then("the catalog schema has no functions and no triggers")
    public void noFunctionsOrTriggers() throws SQLException {
        assertEquals("0,0", query("catalog_owner", List.of("SELECT (SELECT count(*) FROM pg_proc "
                + "WHERE pronamespace = 'catalog'::regnamespace), (SELECT count(*) FROM pg_trigger WHERE NOT tgisinternal "
                + "AND tgrelid IN (SELECT oid FROM pg_class WHERE relnamespace = 'catalog'::regnamespace))")));
    }

    @Then("the login roles have these catalog privileges:")
    public void privileges(DataTable table) throws SQLException {
        for (Map<String, String> row : table.asMaps()) {
            String role = role(row.get("role"));
            String column = row.get("column") == null ? "" : row.get("column");
            String check = switch (column) {
                case "" -> "has_table_privilege('" + role + "', '" + row.get("table") + "', '" + row.get("privilege") + "')";
                case "any" -> "has_any_column_privilege('" + role + "', '" + row.get("table") + "', '"
                        + row.get("privilege") + "')";
                default -> "has_column_privilege('" + role + "', '" + row.get("table") + "', '" + column + "', '"
                        + row.get("privilege") + "')";
            };
            assertEquals("yes".equals(row.get("granted")) ? "t" : "f", query("catalog_owner", List.of("SELECT " + check)),
                    row.toString());
        }
    }

    /** Runs the statements as the role in a transaction that is rolled back, and renders the last result. */
    private String query(String role, List<String> statements) throws SQLException {
        try (Connection connection = shards.superuser(shards.only())) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET LOCAL ROLE " + role(role));
                String rows = "";
                for (String sql : statements) {
                    rows = statement.execute(sql) ? SqlRows.render(statement.getResultSet()) : "";
                }
                return rows;
            } finally {
                connection.rollback();
            }
        }
    }

    private static String role(String role) {
        if (!ROLE.matcher(role).matches()) {
            throw new IllegalArgumentException("Invalid role name: " + role);
        }
        return role;
    }

    private static List<String> split(String statements) {
        List<String> parts = new ArrayList<>();
        for (String part : statements.split(";")) {
            if (!part.isBlank()) {
                parts.add(part.trim());
            }
        }
        return parts;
    }

    private static List<String> applied(MigrateResult result) {
        return result.migrations.stream()
                .map(output -> (output.version == null || output.version.isEmpty() ? "" : output.version + " ")
                        + output.description)
                .toList();
    }
}
