package dev.nklip.javacraft.shardshop.testing.cucumber.config;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One PostgreSQL container for the complete Cucumber run. It has the catalog roles and memberships of the lab
 * (infra/helm/shardshop/templates/databases.yaml) and serves TLS with a certificate from a test CA for the host name
 * localhost. Each scenario creates its own databases. The container keeps its host port also after a restart. Without
 * the Ryuk reaper, the shutdown hook of Testcontainers removes it when the Cucumber JVM exits.
 */
public final class CatalogDatabase {
    private static final String TLS_DIRECTORY = "/var/lib/postgresql/tls";
    private static final AtomicInteger DATABASES = new AtomicInteger();

    private final PostgreSQLContainer container;
    private final int port;
    private final Path trustedCa;
    private final Path unrelatedCa;
    private final String migratorPassword = UUID.randomUUID().toString();
    private final String productPassword = UUID.randomUUID().toString();
    private final String orderPassword = UUID.randomUUID().toString();
    private final String ledgerPassword = UUID.randomUUID().toString();
    private final String cdcPassword = UUID.randomUUID().toString();

    private CatalogDatabase() throws IOException, InterruptedException, SQLException {
        String image = System.getProperty("tc.image.postgresql");
        if (image == null || image.isBlank()) {
            throw new IllegalStateException("Run the scenarios with Maven Failsafe, which sets tc.image.postgresql");
        }
        port = freePort();
        container = createContainer(image, port);
        container.start();
        Path certificates = ShardShopFiles.newWorkDirectory("tls-");
        trustedCa = certificates.resolve("ca.crt");
        unrelatedCa = certificates.resolve("unrelated-ca.crt");
        createCertificates();
        enableTls();
        createRoles();
    }

    public static CatalogDatabase instance() {
        return Holder.INSTANCE;
    }

    @SuppressWarnings("resource")
    private static PostgreSQLContainer createContainer(String image, int port) {
        // The container stays up for the full Cucumber JVM. A fixed loopback port keeps the product endpoints valid
        // after a restart of the container.
        return new PostgreSQLContainer(DockerImageName.parse(image).asCompatibleSubstituteFor("postgres"))
                .withCommand("postgres", "-c", "wal_level=logical", "-c", "max_replication_slots=16",
                        "-c", "max_wal_senders=16")
                .withCreateContainerCmdModifier(command -> Objects.requireNonNull(command.getHostConfig())
                        .withPortBindings(new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", port),
                                ExposedPort.tcp(PostgreSQLContainer.POSTGRESQL_PORT))));
    }

    private void createCertificates() throws IOException, InterruptedException {
        exec("mkdir", "-p", TLS_DIRECTORY);
        exec("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-sha256", "-days", "2",
                "-keyout", TLS_DIRECTORY + "/ca.key", "-out", TLS_DIRECTORY + "/ca.crt",
                "-subj", "/CN=ShardShop temporary test CA", "-addext", "basicConstraints=critical,CA:TRUE");
        // No IP address in the server certificate: localhost verifies, and 127.0.0.1 must fail.
        exec("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-sha256", "-days", "2",
                "-CA", TLS_DIRECTORY + "/ca.crt", "-CAkey", TLS_DIRECTORY + "/ca.key",
                "-keyout", TLS_DIRECTORY + "/server.key", "-out", TLS_DIRECTORY + "/server.crt", "-subj", "/CN=localhost",
                "-addext", "subjectAltName=DNS:localhost", "-addext", "extendedKeyUsage=serverAuth",
                "-addext", "basicConstraints=critical,CA:FALSE", "-addext", "keyUsage=digitalSignature,keyEncipherment");
        exec("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-sha256", "-days", "2",
                "-keyout", TLS_DIRECTORY + "/unrelated-ca.key", "-out", TLS_DIRECTORY + "/unrelated-ca.crt",
                "-subj", "/CN=ShardShop unrelated temporary CA", "-addext", "basicConstraints=critical,CA:TRUE");
        exec("chown", "postgres:postgres", TLS_DIRECTORY + "/server.crt", TLS_DIRECTORY + "/server.key");
        exec("chmod", "600", TLS_DIRECTORY + "/server.key");
        container.copyFileFromContainer(TLS_DIRECTORY + "/ca.crt", trustedCa.toString());
        container.copyFileFromContainer(TLS_DIRECTORY + "/unrelated-ca.crt", unrelatedCa.toString());
    }

    private void exec(String... command) throws IOException, InterruptedException {
        ExecResult result = container.execInContainer(command);
        if (result.getExitCode() != 0) {
            throw new IllegalStateException(command[0] + " failed in the PostgreSQL container: " + result.getStderr());
        }
    }

    private void enableTls() throws SQLException, InterruptedException {
        try (Connection connection = superuser("postgres"); Statement statement = connection.createStatement()) {
            // ALTER SYSTEM cannot run in a transaction block, thus each statement runs alone.
            statement.execute("ALTER SYSTEM SET ssl = on");
            statement.execute("ALTER SYSTEM SET ssl_cert_file = '" + TLS_DIRECTORY + "/server.crt'");
            statement.execute("ALTER SYSTEM SET ssl_key_file = '" + TLS_DIRECTORY + "/server.key'");
            statement.execute("SELECT pg_reload_conf()");
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (!"on".equals(SqlRows.single(statement.executeQuery("SHOW ssl")))) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("PostgreSQL did not enable TLS after the configuration reload");
                }
                Thread.sleep(50);
            }
        }
    }

    private void createRoles() throws SQLException {
        try (Connection connection = superuser("postgres"); Statement statement = connection.createStatement()) {
            for (String group : List.of("shardshop_owner", "catalog_owner", "catalog_reader", "catalog_writer",
                    "catalog_reserver", "ordering_owner", "ordering_writer", "ordering_cdc_reader",
                    "ledger_owner", "ledger_schema_owner", "ledger_writer")) {
                statement.execute("CREATE ROLE " + group + " NOLOGIN");
            }
            statement.execute("CREATE ROLE catalog_migrator LOGIN NOINHERIT PASSWORD '" + migratorPassword
                    + "' IN ROLE catalog_owner");
            statement.execute("CREATE ROLE ordering_migrator LOGIN NOINHERIT PASSWORD '" + migratorPassword
                    + "' IN ROLE ordering_owner");
            statement.execute("CREATE ROLE product_app LOGIN INHERIT PASSWORD '" + productPassword
                    + "' IN ROLE catalog_writer");
            statement.execute("CREATE ROLE order_app LOGIN INHERIT PASSWORD '" + orderPassword
                    + "' IN ROLE catalog_reader, catalog_reserver, ordering_writer");
            statement.execute("CREATE ROLE ordering_cdc LOGIN INHERIT REPLICATION PASSWORD '" + cdcPassword
                    + "' IN ROLE ordering_cdc_reader");
            statement.execute("CREATE ROLE ledger_migrator LOGIN NOINHERIT PASSWORD '" + migratorPassword
                    + "' IN ROLE ledger_schema_owner");
            statement.execute("CREATE ROLE ledger_app LOGIN INHERIT PASSWORD '" + ledgerPassword
                    + "' IN ROLE ledger_writer");
        }
    }

    /** A database like the lab's database shardshop: the bootstrap hardening and the schema catalog of catalog_owner. */
    public String createDatabase() throws SQLException {
        String name = "shardshop_" + DATABASES.incrementAndGet();
        try (Connection connection = superuser("postgres"); Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name + " OWNER shardshop_owner");
        }
        try (Connection connection = superuser(name); Statement statement = connection.createStatement()) {
            statement.execute("REVOKE TEMPORARY ON DATABASE " + name + " FROM PUBLIC");
            statement.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
            statement.execute("CREATE SCHEMA catalog AUTHORIZATION catalog_owner");
            statement.execute("CREATE SCHEMA ordering AUTHORIZATION ordering_owner");
        }
        return name;
    }

    /** Separate disposable ledger database with the production bootstrap ownership boundary. */
    public String createLedgerDatabase() throws SQLException {
        String name = "shardshop_ledger_" + DATABASES.incrementAndGet();
        try (Connection connection = superuser("postgres"); Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name + " OWNER ledger_owner");
        }
        try (Connection connection = superuser(name); Statement statement = connection.createStatement()) {
            statement.execute("REVOKE TEMPORARY ON DATABASE " + name + " FROM PUBLIC");
            statement.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
            statement.execute("CREATE SCHEMA ledger AUTHORIZATION ledger_schema_owner");
        }
        return name;
    }

    /** Migrates the complete ledger stream as its dedicated migrator, never the runtime login. */
    public MigrateResult migrateLedger(String database, Path location) {
        return Flyway.configure()
                .dataSource(catalogUrl(database), "ledger_migrator", migratorPassword)
                .locations("filesystem:" + location)
                .schemas("ledger")
                .defaultSchema("ledger")
                .table("flyway_schema_history")
                .createSchemas(false)
                .validateMigrationNaming(true)
                .failOnMissingLocations(true)
                .cleanDisabled(true)
                .lockRetryCount(5)
                .connectRetries(2)
                .connectRetriesInterval(1)
                .jdbcProperties(Map.of("connectTimeout", "5", "socketTimeout", "30",
                        "options", "-c role=ledger_schema_owner -c statement_timeout=15000 -c lock_timeout=5000"))
                .load().migrate();
    }

    public void dropDatabase(String name) throws SQLException {
        try (Connection connection = superuser("postgres"); Statement statement = connection.createStatement()) {
            // Logical slots outlive their reader. Remove only this disposable scenario database's inactive slots.
            try (PreparedStatement slots = connection.prepareStatement(
                    "SELECT slot_name, active FROM pg_replication_slots WHERE database = ?")) {
                slots.setString(1, name);
                try (var rows = slots.executeQuery()) {
                    while (rows.next()) {
                        if (rows.getBoolean("active")) {
                            throw new SQLException("CDC reader still owns slot " + rows.getString("slot_name"));
                        }
                        try (PreparedStatement drop = connection.prepareStatement("SELECT pg_drop_replication_slot(?)")) {
                            drop.setString(1, rows.getString("slot_name"));
                            drop.execute();
                        }
                    }
                }
            }
            statement.execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
        }
    }

    /** Publications are provisioned by the administrator after ordering migrations, never by the runtime login. */
    public void createOrderPublication(String database) throws SQLException {
        try (Connection connection = superuser(database); Statement statement = connection.createStatement()) {
            statement.execute("CREATE PUBLICATION ordering_order_outbox FOR TABLE ordering.order_outbox "
                    + "WITH (publish = 'insert')");
        }
    }

    /**
     * Migrates as catalog_migrator with the settings of database/shard/catalog/flyway.toml. The Flyway Java API does
     * not read TOML files, thus a change of flyway.toml needs the same change here.
     */
    public MigrateResult migrate(String database, Path location, Map<String, String> placeholders) {
        return migrate(database, location, placeholders, "catalog");
    }

    /** Runs either owner-controlled stream with the same bounded Flyway settings as the lab. */
    public MigrateResult migrate(String database, Path location, Map<String, String> placeholders, String schema) {
        if (!List.of("catalog", "ordering").contains(schema)) {
            throw new IllegalArgumentException("Unknown shard schema");
        }
        return Flyway.configure()
                .dataSource(catalogUrl(database), schema + "_migrator", migratorPassword)
                .locations("filesystem:" + location)
                .schemas(schema)
                .defaultSchema(schema)
                .table("flyway_schema_history")
                .createSchemas(false)
                .validateMigrationNaming(true)
                .failOnMissingLocations(true)
                .cleanDisabled(true)
                .lockRetryCount(5)
                .connectRetries(2)
                .connectRetriesInterval(1)
                .jdbcProperties(Map.of("connectTimeout", "5", "socketTimeout", "30",
                        "options", "-c role=" + schema + "_owner -c statement_timeout=15000 -c lock_timeout=5000"))
                .placeholders(placeholders)
                .load()
                .migrate();
    }

    private String catalogUrl(String database) {
        return "jdbc:postgresql://localhost:" + port + "/" + database + "?sslmode=verify-full&sslrootcert=" + trustedCa;
    }

    /** A plaintext session of the container's superuser; the scenarios use it to arrange and inspect rows. */
    public Connection superuser(String database) throws SQLException {
        return DriverManager.getConnection("jdbc:postgresql://localhost:" + port + "/" + database
                        + "?sslmode=disable&connectTimeout=5&socketTimeout=30",
                container.getUsername(), container.getPassword());
    }

    /** The actual runtime login, for permission checks that must exercise inherited catalog grants. */
    public Connection order(String database) throws SQLException {
        return DriverManager.getConnection(catalogUrl(database) + "&connectTimeout=5&socketTimeout=10",
                "order_app", orderPassword);
    }

    public Connection ledger(String database) throws SQLException {
        return DriverManager.getConnection(catalogUrl(database) + "&connectTimeout=5&socketTimeout=10",
                "ledger_app", ledgerPassword);
    }

    public String ledgerPassword() {
        return ledgerPassword;
    }

    /** Stops PostgreSQL like an outage. The container, its data and its host port stay. */
    public void stop() {
        DockerClientFactory.instance().client().stopContainerCmd(container.getContainerId()).withTimeout(10).exec();
    }

    public void start() throws InterruptedException {
        DockerClientFactory.instance().client().startContainerCmd(container.getContainerId()).exec();
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (true) {
            try (Connection _ = superuser("postgres")) {
                return;
            } catch (SQLException notReady) {
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("PostgreSQL did not start within 30 seconds", notReady);
                }
                Thread.sleep(200);
            }
        }
    }

    public int port() {
        return port;
    }

    public Path trustedCa() {
        return trustedCa;
    }

    public Path unrelatedCa() {
        return unrelatedCa;
    }

    public String productPassword() {
        return productPassword;
    }

    public String orderPassword() {
        return orderPassword;
    }

    public String cdcPassword() {
        return cdcPassword;
    }

    /** A local port that refuses connections. */
    public static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException failure) {
            throw new IllegalStateException("No free local port", failure);
        }
    }

    private static final class Holder {
        private static final CatalogDatabase INSTANCE = start();

        private static CatalogDatabase start() {
            try {
                return new CatalogDatabase();
            } catch (IOException | SQLException failure) {
                throw new IllegalStateException("The PostgreSQL test container did not start", failure);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("The PostgreSQL test container start was interrupted", failure);
            }
        }
    }
}
