package dev.nklip.javacraft.shardshop.testing.kind;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** A newly created disposable restore server. No API can adopt an existing container or database. */
public final class OwnedRestoreTarget implements AutoCloseable {
    private static final String OWNER_LABEL = "shardshop.javacraft.restore-owner";
    private static final String POSTGRES_IMAGE = "docker.io/library/postgres@sha256:979bccfde35445db618c65107d27d08c6c21339d8dc3ad4c830c6633947be0e4";
    static final Set<String> ROLES = Set.of("shardshop_owner", "catalog_owner", "catalog_reader",
            "catalog_writer", "catalog_reserver", "ordering_owner", "ordering_writer", "ordering_reconciler",
            "ordering_cdc_reader", "ledger_owner", "ledger_schema_owner", "ledger_writer", "catalog_migrator",
            "ordering_migrator", "product_app", "order_app", "ordering_operator", "ordering_cdc", "ledger_migrator", "ledger_app");
    private static final Map<String, String> DATABASES = Map.of("shard-a", "shard_a", "shard-b", "shard_b",
            "shard-c", "shard_c", "ledger-db", "ledger_db");
    private final PostgreSQLContainer container;
    private final String owner = UUID.randomUUID().toString();
    private final String password = UUID.randomUUID().toString();
    private final Map<String, String> passwords = new HashMap<>();
    private final Set<String> restored = new HashSet<>();
    private final Map<String, Map<Path, String>> orderingDumps = new HashMap<>();
    private final Path certificate;
    private final Path work;
    private String containerId;
    private JsonNode installedRoles;
    private boolean closed;

    private OwnedRestoreTarget(String targetName, Path workDirectory) throws IOException {
        validateTargetName(targetName);
        work = privateDirectory(workDirectory);
        certificate = work.resolve("restore-ca.crt");
        if (Files.exists(certificate, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Restore certificate path already exists");
        }
        String image = System.getProperty("tc.image.postgresql");
        container = new PostgreSQLContainer(qualifiedImage(image))
                .withUsername("postgres").withPassword(password).withDatabaseName("postgres")
                .withCommand("postgres", "-c", "wal_level=logical", "-c", "max_replication_slots=16", "-c", "max_wal_senders=16")
                .withStartupTimeout(Duration.ofMinutes(2))
                .withLabel(OWNER_LABEL, owner)
                .withCreateContainerCmdModifier(command -> {
                    command.withName(targetName);
                    Objects.requireNonNull(command.getHostConfig()).withPortBindings(new PortBinding(
                            Ports.Binding.bindIp("127.0.0.1"), ExposedPort.tcp(PostgreSQLContainer.POSTGRESQL_PORT)));
                });
    }

    static DockerImageName qualifiedImage(String image) {
        if (!POSTGRES_IMAGE.equals(image)) throw new IllegalArgumentException("Restore requires the qualified tc.image.postgresql digest");
        return DockerImageName.parse(image).asCompatibleSubstituteFor("postgres");
    }

    public static OwnedRestoreTarget create(String targetName, Path workDirectory) throws IOException, InterruptedException, SQLException {
        OwnedRestoreTarget target = new OwnedRestoreTarget(targetName, workDirectory);
        try {
            // Docker's exclusive name allocation refuses an existing target, including stopped containers.
            target.container.start();
            target.containerId = target.container.getContainerId();
            target.requireOwned();
            target.configureTls();
            try (Connection connection = target.admin("postgres"); Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(30);
                for (String database : DATABASES.values()) statement.execute("CREATE DATABASE " + database);
            }
            return target;
        } catch (IOException | InterruptedException | SQLException | RuntimeException failure) {
            try { target.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    static void validateTargetName(String targetName) {
        if (targetName == null || targetName.length() > 63
                || !targetName.matches("shardshop-restore-[a-z0-9]([a-z0-9-]*[a-z0-9])?")) {
            throw new IllegalArgumentException("Restore target requires a unique shardshop-restore- DNS label");
        }
    }

    static Path privateDirectory(Path directory) throws IOException {
        if (directory == null || !directory.isAbsolute() || Files.isSymbolicLink(directory)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Restore work requires an existing absolute private directory");
        }
        Path real = directory.toRealPath();
        if (!Files.getPosixFilePermissions(real).equals(PosixFilePermissions.fromString("rwx------"))) {
            throw new IllegalArgumentException("Restore work directory requires mode 700");
        }
        return real;
    }

    public static String databaseName(String sourceDatabaseName) {
        String database = sourceDatabaseName == null ? null : DATABASES.get(sourceDatabaseName);
        if (database == null) throw new IllegalArgumentException("Restore source must be one of the four inventory database clusters");
        return database;
    }

    public int port() { return container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT); }
    public Path rootCertificate() { return certificate; }
    public String postgresPassword() { return password; }
    public String containerId() { return containerId; }

    public String rolePassword(String role) {
        String value = passwords.get(role);
        if (value == null) throw new IllegalArgumentException("No restored login password for this role");
        return value;
    }

    public String jdbcUrl(String sourceDatabaseName) {
        return url(databaseName(sourceDatabaseName));
    }

    private String url(String database) {
        return "jdbc:postgresql://localhost:" + port() + "/" + database
                + "?sslmode=verify-full&sslrootcert=" + java.net.URLEncoder.encode(certificate.toString(), java.nio.charset.StandardCharsets.UTF_8)
                + "&connectTimeout=5&socketTimeout=30"
                + "&options=-c%20statement_timeout=30000%20-c%20lock_timeout=5000";
    }

    public Connection connection(String sourceDatabaseName) throws SQLException, IOException {
        requireOwned();
        return DriverManager.getConnection(jdbcUrl(sourceDatabaseName), "postgres", password);
    }

    private Connection admin(String database) throws SQLException {
        return DriverManager.getConnection("jdbc:postgresql://localhost:" + port() + "/" + database
                + "?sslmode=disable&connectTimeout=5&socketTimeout=30&options=-c%20statement_timeout=30000%20-c%20lock_timeout=5000",
                "postgres", password);
    }

    public String systemIdentifier() throws SQLException, IOException {
        requireOwned();
        try (Connection connection = admin("postgres"); Statement statement = connection.createStatement();
             var result = statement.executeQuery("SELECT system_identifier FROM pg_control_system()")) {
            result.next();
            return result.getString(1);
        }
    }

    public void requireDifferentSystemIdentifier(String sourceIdentifier) throws SQLException, IOException {
        if (sourceIdentifier == null || !sourceIdentifier.matches("[0-9]+") || sourceIdentifier.equals(systemIdentifier())) {
            throw new IllegalArgumentException("Restore source and target must have distinct verified database identities");
        }
    }

    /** Full custom-format dump with owners/ACLs; roles are validated before any target SQL. */
    public synchronized void restore(String source, Path dump, JsonNode roles) throws IOException, InterruptedException, SQLException {
        String database = databaseName(source);
        List<Role> validated = validateRoles(roles);
        if (dump == null || !dump.isAbsolute() || Files.isSymbolicLink(dump) || !Files.isRegularFile(dump, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Restore dump must be an absolute regular file without a symlink");
        }
        if (restored.contains(source)) throw new IllegalStateException("This owned database was already restored");
        requireOwned();
        if (installedRoles == null) {
            installRoles(validated);
            installedRoles = roles.deepCopy();
        } else if (!installedRoles.equals(roles)) {
            throw new IllegalArgumentException("All four restores must use the same captured role metadata");
        }
        restoreInput(database, dump);
        try (Connection connection = admin("postgres"); Statement statement = connection.createStatement()) {
            statement.execute("ALTER DATABASE " + database + " OWNER TO " + (source.equals("ledger-db") ? "ledger_owner" : "shardshop_owner"));
        }
        restored.add(source);
    }

    private void installRoles(List<Role> roles) throws SQLException {
        try (Connection connection = admin("postgres"); Statement statement = connection.createStatement()) {
            for (Role role : roles) {
                String rolePassword = UUID.randomUUID().toString();
                if (role.login()) passwords.put(role.name(), rolePassword);
                statement.execute("CREATE ROLE " + role.name() + (role.login() ? " LOGIN PASSWORD '" + rolePassword + "'" : " NOLOGIN")
                        + (role.inherit() ? " INHERIT" : " NOINHERIT") + (role.replication() ? " REPLICATION" : " NOREPLICATION")
                        + " NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS CONNECTION LIMIT " + role.connectionLimit());
            }
            for (Role role : roles) for (String membership : role.memberships()) {
                statement.execute("GRANT " + membership + " TO " + role.name());
            }
        }
    }

    record Role(String name, boolean login, boolean inherit, boolean replication, int connectionLimit, List<String> memberships) { }

    static List<Role> validateRoles(JsonNode roles) {
        if (roles == null || !roles.isArray() || roles.isEmpty()) throw new IllegalArgumentException("Captured restore roles must be a nonempty array");
        Set<String> names = new HashSet<>();
        List<Role> result = new ArrayList<>();
        for (JsonNode role : roles) {
            String name = role.path("name").asText();
            if (!role.isObject() || !ROLES.contains(name) || !names.add(name)) throw new IllegalArgumentException("Unexpected or duplicate restore role");
            for (String flag : List.of("login", "inherit", "replication", "superuser", "createdb", "createrole", "bypassrls")) {
                if (!role.path(flag).isBoolean()) throw new IllegalArgumentException("Restore role requires explicit privilege flags");
            }
            for (String flag : List.of("superuser", "createdb", "createrole", "bypassrls")) {
                if (role.path(flag).asBoolean()) throw new IllegalArgumentException("Restore rejects elevated source roles");
            }
            if (role.path("replication").asBoolean() && !name.equals("ordering_cdc")) throw new IllegalArgumentException("Only the CDC login can replicate");
            if (!role.path("connectionLimit").isInt() || role.path("connectionLimit").asInt() < -1) throw new IllegalArgumentException("Invalid restore role connection limit");
            if (!role.path("memberships").isArray()) throw new IllegalArgumentException("Restore role memberships must be explicit");
            List<String> memberships = new ArrayList<>();
            for (JsonNode membership : role.path("memberships")) {
                if (!membership.isTextual() || !ROLES.contains(membership.asText()) || name.equals(membership.asText())
                        || memberships.contains(membership.asText())) throw new IllegalArgumentException("Invalid restore role membership");
                memberships.add(membership.asText());
            }
            result.add(new Role(name, role.path("login").asBoolean(), role.path("inherit").asBoolean(),
                    role.path("replication").asBoolean(), role.path("connectionLimit").asInt(), List.copyOf(memberships)));
        }
        for (Role role : result) if (!names.containsAll(role.memberships())) throw new IllegalArgumentException("Restore role membership names an absent role");
        return List.copyOf(result);
    }

    /** Database ACLs are outside a pg_dump without --create and therefore travel explicitly. */
    public static void validateDatabaseSecurity(JsonNode security) {
        if (security == null || !security.isObject() || !security.path("owner").isTextual()
                || !ROLES.contains(security.path("owner").asText()) || !security.path("privileges").isArray()) {
            throw new IllegalArgumentException("Restore database security metadata is invalid");
        }
        Set<String> entries = new HashSet<>();
        for (JsonNode privilege : security.path("privileges")) {
            String grantee = privilege.path("grantee").asText();
            String name = privilege.path("privilege").asText();
            if (!privilege.isObject() || !privilege.path("grantee").isTextual()
                    || !(ROLES.contains(grantee) || grantee.equals("postgres") || grantee.equals("PUBLIC"))
                    || !Set.of("CONNECT", "CREATE", "TEMPORARY").contains(name)
                    || !privilege.path("grantable").isBoolean()
                    || !entries.add(grantee + ":" + name)) {
                throw new IllegalArgumentException("Restore database privilege metadata is invalid or duplicated");
            }
            if (grantee.equals("PUBLIC") && privilege.path("grantable").asBoolean()) {
                throw new IllegalArgumentException("PUBLIC cannot receive database grant options");
            }
        }
    }

    public synchronized void applyDatabaseSecurity(String source, JsonNode security) throws IOException, SQLException {
        validateDatabaseSecurity(security);
        String database = databaseName(source);
        requireOwned();
        try (Connection connection = admin("postgres"); Statement statement = connection.createStatement()) {
            statement.execute("ALTER DATABASE " + database + " OWNER TO " + security.path("owner").asText());
            // Revoke inherited initdb defaults as well as the target owner's implicit ACL entry.
            Set<String> grantees = new HashSet<>(ROLES);
            grantees.add("PUBLIC");
            grantees.add("postgres");
            Set<String> existingRoles = new HashSet<>();
            try (var rows = statement.executeQuery("SELECT rolname FROM pg_roles")) {
                while (rows.next()) existingRoles.add(rows.getString(1));
            }
            for (String grantee : grantees) {
                if (grantee.equals("PUBLIC") || existingRoles.contains(grantee)) {
                    statement.execute("REVOKE ALL PRIVILEGES ON DATABASE " + database + " FROM " + grantee);
                }
            }
            for (JsonNode privilege : security.path("privileges")) {
                statement.execute("GRANT " + privilege.path("privilege").asText() + " ON DATABASE " + database
                        + " TO " + privilege.path("grantee").asText()
                        + (privilege.path("grantable").asBoolean() ? " WITH GRANT OPTION" : ""));
            }
        }
        verifyDatabaseSecurity(source, security);
    }

    public void verifyDatabaseSecurity(String source, JsonNode security) throws IOException, SQLException {
        validateDatabaseSecurity(security);
        String database = databaseName(source);
        requireOwned();
        Set<String> expected = securityPrivileges(security);
        Set<String> actual = new HashSet<>();
        try (Connection connection = admin("postgres");
             var statement = connection.prepareStatement("SELECT pg_get_userbyid(d.datdba), "
                     + "CASE WHEN a.grantee=0 THEN 'PUBLIC' ELSE pg_get_userbyid(a.grantee) END, a.privilege_type, a.is_grantable "
                     + "FROM pg_database d LEFT JOIN LATERAL aclexplode(coalesce(d.datacl,acldefault('d',d.datdba))) a ON true WHERE d.datname=?")) {
            statement.setString(1, database);
            boolean found = false;
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    found = true;
                    if (!security.path("owner").asText().equals(rows.getString(1))) throw new IOException("Restored database owner differs");
                    if (rows.getString(2) != null) actual.add(rows.getString(2) + ":" + rows.getString(3) + ":" + rows.getBoolean(4));
                }
            }
            if (!found || !actual.equals(expected)) throw new IOException("Restored database privileges differ");
        }
    }

    static Set<String> securityPrivileges(JsonNode security) {
        validateDatabaseSecurity(security);
        Set<String> result = new HashSet<>();
        for (JsonNode privilege : security.path("privileges")) result.add(privilege.path("grantee").asText() + ":"
                + privilege.path("privilege").asText() + ":" + privilege.path("grantable").asBoolean());
        return Set.copyOf(result);
    }

    private void configureTls() throws IOException, InterruptedException, SQLException {
        exec("mkdir", "-p", "/var/lib/postgresql/restore-tls");
        exec("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-sha256", "-days", "2",
                "-keyout", "/var/lib/postgresql/restore-tls/ca.key", "-out", "/var/lib/postgresql/restore-tls/ca.crt",
                "-subj", "/CN=ShardShop isolated restore CA", "-addext", "basicConstraints=critical,CA:TRUE");
        exec("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-sha256", "-days", "2",
                "-CA", "/var/lib/postgresql/restore-tls/ca.crt", "-CAkey", "/var/lib/postgresql/restore-tls/ca.key",
                "-keyout", "/var/lib/postgresql/restore-tls/server.key", "-out", "/var/lib/postgresql/restore-tls/server.crt",
                "-subj", "/CN=localhost", "-addext", "subjectAltName=DNS:localhost", "-addext", "extendedKeyUsage=serverAuth",
                "-addext", "basicConstraints=critical,CA:FALSE", "-addext", "keyUsage=digitalSignature,keyEncipherment");
        exec("chown", "postgres:postgres", "/var/lib/postgresql/restore-tls/server.crt", "/var/lib/postgresql/restore-tls/server.key");
        exec("chmod", "600", "/var/lib/postgresql/restore-tls/server.key");
        container.copyFileFromContainer("/var/lib/postgresql/restore-tls/ca.crt", certificate.toString());
        Files.setPosixFilePermissions(certificate, PosixFilePermissions.fromString("rw-------"));
        try (Connection connection = admin("postgres"); Statement statement = connection.createStatement()) {
            statement.execute("ALTER SYSTEM SET ssl = on");
            statement.execute("ALTER SYSTEM SET ssl_cert_file = '/var/lib/postgresql/restore-tls/server.crt'");
            statement.execute("ALTER SYSTEM SET ssl_key_file = '/var/lib/postgresql/restore-tls/server.key'");
            statement.execute("SELECT pg_reload_conf()");
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (true) {
                try (var result = statement.executeQuery("SHOW ssl")) {
                    if (result.next() && result.getString(1).equals("on")) break;
                }
                if (System.nanoTime() >= deadline) throw new IOException("Restore server did not enable TLS within five seconds");
                Thread.sleep(50);
            }
        }
        // An actual verify-full handshake qualifies the restored runtime's trust configuration.
        try (Connection connection = DriverManager.getConnection(url("postgres"), "postgres", password);
             Statement statement = connection.createStatement();
             var result = statement.executeQuery("SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()")) {
            if (!result.next() || !result.getBoolean(1)) throw new IOException("Restore target TLS handshake was not encrypted");
        }
    }

    private void exec(String... arguments) throws IOException, InterruptedException {
        requireOwned();
        // openssl emits key-generation progress on stderr even on success; suppress it inside the
        // owned container, while the exit status still passes through the bounded process helper.
        List<String> command = new ArrayList<>(List.of("docker", "exec", containerId, "sh", "-c", "exec \"$@\" 2>/dev/null", "sh"));
        command.addAll(List.of(arguments));
        BackupProcesses.transfer(new ProcessBuilder(command), null, null, Duration.ofSeconds(30));
    }

    private void restoreInput(String database, Path dump) throws IOException, InterruptedException {
        BackupProcesses.transfer(new ProcessBuilder("docker", "exec", "-i", containerId, "pg_restore", "--exit-on-error", "--username=postgres",
                "--dbname=" + database), dump, null, Duration.ofMinutes(3));
    }

    /** Exports an operator recovery output; the caller separately records its metadata and completeness. */
    public synchronized void dumpDatabase(String source, Path dump) throws IOException, InterruptedException {
        String database = databaseName(source);
        requireOwned();
        if (!restored.contains(source)) throw new IOException("Database export requires a completed isolated restore");
        if (dump == null || !dump.isAbsolute() || !dump.equals(dump.normalize()) || dump.getParent() == null) {
            throw new IllegalArgumentException("Owned database dump requires an absolute normalized file");
        }
        privateDirectory(dump.getParent());
        Files.createFile(dump, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        BackupProcesses.transfer(new ProcessBuilder("docker", "exec", containerId, "pg_dump", "--format=custom",
                "--username=postgres", "--dbname=" + database), null, dump, Duration.ofMinutes(3));
    }

    /** Qualification-only earlier ordering state, exported from this owned server. */
    public synchronized void dumpOrdering(String source, Path dump) throws IOException, InterruptedException, SQLException {
        requireOrderingSource(source);
        if (dump == null || !dump.isAbsolute() || !dump.equals(dump.normalize()) || dump.getParent() == null) {
            throw new IllegalArgumentException("Owned ordering dump requires an absolute normalized file");
        }
        privateDirectory(dump.getParent());
        requireOrderingIdle(source);
        Files.createFile(dump, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        BackupProcesses.transfer(new ProcessBuilder("docker", "exec", containerId, "pg_dump", "--format=custom",
                "--username=postgres", "--schema=ordering", "--dbname=" + databaseName(source)), null, dump, Duration.ofMinutes(3));
        orderingDumps.computeIfAbsent(source, ignored -> new HashMap<>()).put(dump, orderingDigest(dump));
    }

    /** Replaces only ordering with this target's own unchanged custom dump; catalog and ledger remain untouched. */
    public synchronized void restoreOrdering(String source, Path dump) throws IOException, InterruptedException, SQLException {
        requireOrderingSource(source);
        String captured = orderingDumps.getOrDefault(source, Map.of()).get(dump);
        if (captured == null) throw new IllegalArgumentException("Ordering restore requires this target's captured dump");
        BackupArtifacts.requirePrivateFile(dump);
        if (!captured.equals(orderingDigest(dump))) throw new IOException("Captured ordering dump changed");
        requireOrderingIdle(source);
        List<String> publications = new ArrayList<>();
        try (Connection connection = connection(source); Statement statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT pubname FROM pg_publication_tables WHERE schemaname='ordering' AND tablename='order_outbox'")) {
            while (rows.next()) {
                String publication = rows.getString(1);
                if (!publication.matches("[a-z_][a-z0-9_]*")) throw new IOException("Unexpected ordering publication name");
                publications.add(publication);
            }
        }
        try (Connection connection = connection(source); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA ordering CASCADE");
        }
        // This target captured and pinned an ordering-only dump. A pg_restore schema filter
        // omits the namespace's own CREATE/OWNER/ACL entries after DROP SCHEMA.
        BackupProcesses.transfer(new ProcessBuilder("docker", "exec", "-i", containerId, "pg_restore", "--exit-on-error",
                "--username=postgres", "--dbname=" + databaseName(source)), dump, null, Duration.ofMinutes(3));
        try (Connection connection = connection(source); Statement statement = connection.createStatement()) {
            for (String publication : publications) {
                try (var query = connection.prepareStatement("SELECT count(*) FROM pg_publication_tables WHERE pubname=? AND schemaname='ordering' AND tablename='order_outbox'")) {
                    query.setString(1,publication);
                    try (var rows = query.executeQuery()) {
                        rows.next();
                        if (rows.getLong(1) == 0) statement.execute("ALTER PUBLICATION " + publication + " ADD TABLE ONLY ordering.order_outbox");
                    }
                }
            }
        }
    }

    static void validateOrderingSource(String source) {
        if (source == null || !List.of("shard-a", "shard-b", "shard-c").contains(source)) throw new IllegalArgumentException("Ordering loss is restricted to buyer shards");
    }

    private static String orderingDigest(Path file) throws IOException {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            try (var input = new java.security.DigestInputStream(Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS), digest)) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private void requireOrderingSource(String source) throws IOException {
        validateOrderingSource(source);
        requireOwned();
        if (!restored.contains(source)) throw new IOException("Ordering qualification requires a completed isolated restore");
    }

    void requireOrderingIdle(String source) throws IOException, SQLException {
        requireOrderingSource(source);
        try (Connection connection = connection(source); Statement statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT "
                     + "(SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid()),"
                     + "(SELECT count(*) FROM pg_replication_slots WHERE database=current_database())")) {
            if (!rows.next() || rows.getLong(1) != 0 || rows.getLong(2) != 0) throw new IOException("Ordering dump/restore requires stopped target workers and no logical slots");
        }
    }

    private void requireOwned() throws IOException {
        if (closed || containerId == null || !containerId.equals(container.getContainerId())) throw new IOException("Restore target is unavailable");
        try {
            var inspected = DockerClientFactory.instance().client().inspectContainerCmd(containerId).exec();
            if (inspected.getConfig() == null || inspected.getConfig().getLabels() == null
                    || !owner.equals(inspected.getConfig().getLabels().get(OWNER_LABEL))) throw new IOException("Restore target ownership does not match");
        } catch (RuntimeException failure) {
            throw new IOException("Cannot verify owned restore target", failure);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        // Failed start may have created our container before it returned an ID.
        if (containerId == null) containerId = container.getContainerId();
        if (containerId != null) {
            requireOwned();
            try { container.stop(); }
            catch (RuntimeException failure) { throw new IOException("Cannot remove owned restore target", failure); }
        }
        closed = true;
    }
}
