package dev.nklip.javacraft.shardshop.testing.cucumber.world;

import dev.nklip.javacraft.shardshop.testing.cucumber.config.CatalogDatabase;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.PackagedApplication;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.RabbitMqBroker;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.ShardShopFiles;
import org.picocontainer.Disposable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;

/** Packaged ledger runtime, using only the ledger_app SQL and shardshop-ledger RabbitMQ logins. */
public final class LedgerRuntime implements Disposable {
    private final CatalogDatabase database = CatalogDatabase.instance();
    private final Path directory;
    private PackagedApplication application;
    private String name;
    private RabbitMqBroker broker;
    private boolean cleanupEnabled;

    public LedgerRuntime() throws IOException {
        directory = ShardShopFiles.newWorkDirectory("ledger-");
    }

    public void start(String databaseName, RabbitMqBroker queue, String allowedCurrencies) throws Exception {
        start(databaseName, queue, allowedCurrencies, false);
    }

    public void startWithPublisher(String databaseName, RabbitMqBroker queue, String allowedCurrencies) throws Exception {
        start(databaseName, queue, allowedCurrencies, true);
    }

    private void start(String databaseName, RabbitMqBroker queue, String allowedCurrencies, boolean publisherEnabled)
            throws Exception {
        name = databaseName;
        broker = queue;
        Path configuration = configuration(databaseName, queue, allowedCurrencies, publisherEnabled);
        application = PackagedApplication.start("shardshop-ledger", directory,
                List.of("-Dquarkus.config.locations=" + configuration.toUri()));
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (!application.isAlive()) {
                throw new AssertionError("Ledger exited during startup:\n" + application.tail());
            }
            if (application.lines().stream().anyMatch(line -> line.contains("started in"))) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Ledger startup exceeded 30 seconds:\n" + application.tail());
    }

    /** The one-shot startup probe must exit without consuming an already queued command. */
    public void checkStartup(String databaseName, RabbitMqBroker queue, String allowedCurrencies) throws Exception {
        checkStartup(databaseName, queue, allowedCurrencies, false);
    }

    public void checkStartupWithPublisher(String databaseName, RabbitMqBroker queue, String allowedCurrencies)
            throws Exception {
        checkStartup(databaseName, queue, allowedCurrencies, true);
    }

    private void checkStartup(String databaseName, RabbitMqBroker queue, String allowedCurrencies,
                              boolean publisherEnabled) throws Exception {
        Path configuration = configuration(databaseName, queue, allowedCurrencies, publisherEnabled);
        PackagedApplication check = PackagedApplication.start("shardshop-ledger", directory,
                List.of("-Dquarkus.config.locations=" + configuration.toUri()), List.of("--check-startup"));
        if (check.awaitExit(Duration.ofSeconds(20)) != 0) {
            throw new AssertionError("Ledger startup check failed:\n" + check.tail());
        }
    }

    private Path configuration(String databaseName, RabbitMqBroker queue, String allowedCurrencies,
                               boolean publisherEnabled) throws IOException {
        Path configuration = Files.createTempFile(directory, "ledger-", ".properties",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(configuration, String.join("\n", List.of(
                "shardshop.ledger.enabled=true",
                "shardshop.ledger.database.url=jdbc:postgresql://localhost:" + database.port() + "/" + databaseName,
                "shardshop.ledger.database.username=ledger_app",
                "shardshop.ledger.database.password=" + database.ledgerPassword(),
                "shardshop.ledger.database.root-certificate=" + database.trustedCa(),
                "shardshop.ledger.max-pool-size=4",
                "shardshop.ledger.allowed-currencies=" + allowedCurrencies,
                "shardshop.ledger.publisher.enabled=" + publisherEnabled,
                "shardshop.ledger.publisher.batch-size=10",
                "shardshop.ledger.publisher.poll-interval=100ms",
                "shardshop.ledger.cleanup.enabled=" + cleanupEnabled,
                "shardshop.ledger.cleanup.retention=PT1H",
                "shardshop.ledger.cleanup.poll-interval=100ms",
                "shardshop.ledger.cleanup.batch-size=8",
                "shardshop.ledger.broker.host=127.0.0.1",
                "shardshop.ledger.broker.port=" + queue.port(),
                "shardshop.ledger.broker.virtual-host=shardshop",
                "shardshop.ledger.broker.username=shardshop-ledger",
                "shardshop.ledger.broker.password=" + queue.ledgerPassword(),
                "shardshop.ledger.broker.timeout=5s")) + "\n");
        return configuration;
    }

    public void restart(String allowedCurrencies) throws Exception {
        stop();
        start(name, broker, allowedCurrencies);
    }

    /** Enables the production transport cleanup worker on subsequent starts. */
    public void enableCleanup() {
        cleanupEnabled = true;
    }

    public void restartWithPublisher(String allowedCurrencies) throws Exception {
        stop();
        startWithPublisher(name, broker, allowedCurrencies);
    }

    public void stop() throws InterruptedException {
        if (application != null && application.isAlive()) {
            application.stop();
        }
    }

    public boolean isAlive() {
        return application != null && application.isAlive();
    }

    public String tail() {
        return application == null ? "" : application.tail();
    }

    public List<String> lines() {
        return application == null ? List.of() : application.lines();
    }

    @Override
    public void dispose() {
        try {
            stop();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
