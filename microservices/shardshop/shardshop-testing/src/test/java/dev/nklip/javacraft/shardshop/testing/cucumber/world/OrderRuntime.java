package dev.nklip.javacraft.shardshop.testing.cucumber.world;

import dev.nklip.javacraft.shardshop.testing.cucumber.config.CatalogDatabase;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.CrashBreakpoint;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.PackagedApplication;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.RawHttp;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.RabbitMqBroker;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.ShardShopFiles;
import org.picocontainer.Disposable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/** Packaged HTTP provider and, when enabled, a separate saga JVM with independent generator identity and pools. */
public final class OrderRuntime implements Disposable {
    private static final AtomicInteger GENERATORS = new AtomicInteger(500);
    private final CatalogDatabase database = CatalogDatabase.instance();
    private final CatalogShards shards;
    private final Path directory;
    private final int port = CatalogDatabase.freePort();
    private final int refusedPort = CatalogDatabase.freePort();
    private final List<PackagedApplication> applications = new ArrayList<>();
    private PackagedApplication application;
    private PackagedApplication saga;
    private Path configuration;
    private boolean stockEnabled;
    private int stockShardConcurrency = 3;
    private int maxPoolSize = 1;
    private boolean stockMeasurement;
    private boolean profitMeasurement;
    private int profitShardConcurrency = 3;
    private RabbitMqBroker cdcBroker;
    private RabbitMqBroker resultsBroker;
    private boolean profitEnabled;
    private boolean reconciliationEnabled;
    private boolean cleanupEnabled;
    private CrashBreakpoint sagaCrash;

    public OrderRuntime(CatalogShards shards) throws IOException {
        this.shards = shards;
        directory = ShardShopFiles.newWorkDirectory("order-");
    }

    public void start(Set<String> unavailableShards) throws IOException, InterruptedException {
        configuration = Files.createTempFile(directory, "routing-", ".properties",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        StringJoiner values = new StringJoiner("\n", "", "\n");
        values.add("shardshop.routing.shards=" + shards.all().stream().map(CatalogShards.Shard::name)
                .collect(Collectors.joining(",")));
        values.add("shardshop.routing.regions=" + String.join(",", shards.regions()));
        values.add("shardshop.order.max-pool-size=" + maxPoolSize);
        if (stockMeasurement) {
            values.add("shardshop.order.stock.shard-concurrency=" + stockShardConcurrency);
            values.add("shardshop.order.stock.batch-size=8");
            values.add("shardshop.order.stock.poll-interval=250ms");
        }
        values.add("shardshop.order.catalog-deadline=PT3S");
        // The step 4.1 scenarios inspect atomic acceptance before any saga worker runs.
        values.add("shardshop.order.stock.enabled=" + stockEnabled);
        values.add("shardshop.order.cdc.enabled=" + (cdcBroker != null));
        values.add("shardshop.order.results.enabled=" + (resultsBroker != null));
        values.add("shardshop.order.profit.enabled=" + profitEnabled);
        values.add("shardshop.order.profit.poll-interval=" + (profitMeasurement ? "100ms" : "PT0.25S"));
        values.add("shardshop.order.profit.batch-size=" + (profitMeasurement ? 32 : 8));
        if (profitMeasurement) {
            values.add("shardshop.order.profit.shard-concurrency=" + profitShardConcurrency);
        }
        values.add("shardshop.order.reconciliation.enabled=" + reconciliationEnabled);
        values.add("shardshop.order.reconciliation.poll-interval=100ms");
        values.add("shardshop.order.reconciliation.batch-size=8");
        values.add("shardshop.order.cleanup.enabled=" + cleanupEnabled);
        if (cleanupEnabled) {
            values.add("shardshop.order.cleanup.retention=PT1H");
            values.add("shardshop.order.cleanup.poll-interval=100ms");
            values.add("shardshop.order.cleanup.batch-size=8");
        }
        RabbitMqBroker configuredBroker = cdcBroker != null ? cdcBroker : resultsBroker;
        if (configuredBroker != null) {
            values.add("shardshop.order.cdc.broker.host=127.0.0.1");
            values.add("shardshop.order.cdc.broker.port=" + configuredBroker.port());
            values.add("shardshop.order.cdc.broker.virtual-host=shardshop");
            values.add("shardshop.order.cdc.broker.username=shardshop-order");
            values.add("shardshop.order.cdc.broker.password=" + configuredBroker.orderPassword());
        }
        for (var shard : shards.all()) {
            String prefix = "shardshop.order.connections." + shard.name() + ".";
            int endpoint = unavailableShards.contains(shard.name()) ? refusedPort : database.port();
            values.add(prefix + "primary-url=jdbc:postgresql://localhost:" + endpoint + "/" + shard.database());
            values.add(prefix + "root-certificate=" + database.trustedCa());
            values.add(prefix + "username=order_app");
            values.add(prefix + "password=" + database.orderPassword());
            if (cdcBroker != null) {
                values.add("shardshop.order.cdc.connections." + shard.name() + ".username=ordering_cdc");
                values.add("shardshop.order.cdc.connections." + shard.name() + ".password=" + database.cdcPassword());
            }
        }
        Files.writeString(configuration, values.toString());
        application = PackagedApplication.start("shardshop-order", directory, List.of(
                "-Dshardshop.routing.config=" + configuration.toUri(),
                "-Dshardshop.order.process=http",
                "-Dshardshop.id.generator-id=" + GENERATORS.incrementAndGet(),
                "-Dquarkus.http.host=127.0.0.1", "-Dquarkus.http.port=" + port));
        applications.add(application);
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (!application.isAlive()) {
                throw new AssertionError("Order exited during startup:\n" + application.tail());
            }
            try {
                if (RawHttp.exchange(port, "GET", "/api/v1/buyers/0", List.of(), new byte[0],
                        RawHttp.Framing.NONE).status() == 400) {
                    startSaga();
                    return;
                }
            } catch (IOException notReady) {
                // The packaged provider has not opened its port yet.
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Order startup exceeded 30 seconds:\n" + application.tail());
    }

    public void restart(Set<String> unavailableShards) throws IOException, InterruptedException {
        stop();
        start(unavailableShards);
    }

    /** Configures the isolated stock-stage comparison before the next start. */
    public void configureStockMeasurement(int concurrency, int poolSize) {
        stockMeasurement = true;
        stockShardConcurrency = concurrency;
        maxPoolSize = poolSize;
    }

    /** Configures the isolated profit comparison with the lab's 100ms/32 scan profile. */
    public void configureProfitMeasurement(int concurrency, int poolSize) {
        profitMeasurement = true;
        profitShardConcurrency = concurrency;
        maxPoolSize = poolSize;
    }

    /** Sets the per-shard pool capacity before the next start without changing worker settings. */
    public void configurePoolSize(int poolSize) {
        maxPoolSize = poolSize;
    }

    /** Log artifacts survive disposable database cleanup, including failed comparisons. */
    public List<Path> applicationLogs() {
        return applications.stream().map(PackagedApplication::log).toList();
    }

    /** Enables the production reservation coordinator on subsequent starts, including restarts. */
    public void enableStock() {
        stockEnabled = true;
    }

    /** Enables the production WAL relay on subsequent starts, including restarts. */
    public void enableCdc(RabbitMqBroker broker) {
        cdcBroker = broker;
    }

    /** Enables result consumption independently of the WAL relay. */
    public void enableResults(RabbitMqBroker broker) {
        resultsBroker = broker;
    }

    /** Enables asynchronous confirmed-profit crediting on subsequent starts. */
    public void enableProfit() {
        profitEnabled = true;
    }

    /** Enables the production overdue-saga scanner on subsequent starts. */
    public void enableReconciliation() {
        reconciliationEnabled = true;
    }

    /** Enables the production transport cleanup worker on subsequent saga starts. */
    public void enableCleanup() {
        cleanupEnabled = true;
    }

    public int port() {
        return port;
    }

    /** Restarts only the saga; HTTP writes remain available throughout. */
    public void restartSaga() throws IOException, InterruptedException {
        stopSaga();
        startSaga();
    }

    public void stopSaga() throws InterruptedException {
        if (saga != null && saga.isAlive()) saga.stop();
    }

    public void killSaga() throws InterruptedException {
        if (saga != null && saga.isAlive()) saga.kill();
    }

    public void crashSagaAt(CrashBreakpoint breakpoint) { sagaCrash = breakpoint; }

    public void killSagaAt(CrashBreakpoint breakpoint, Duration timeout) throws Exception {
        breakpoint.killAtHit(saga, timeout);
    }

    public void awaitSagaCrash(CrashBreakpoint breakpoint, Duration timeout) throws Exception {
        breakpoint.awaitHit(saga, timeout);
    }

    public String sagaTail() {
        return saga == null ? "" : saga.tail();
    }

    private void startSaga() throws IOException, InterruptedException {
        if (!stockEnabled && cdcBroker == null && resultsBroker == null && !profitEnabled
                && !reconciliationEnabled && !cleanupEnabled) return;
        CrashBreakpoint pendingCrash = sagaCrash;
        sagaCrash = null;
        var options = new ArrayList<>(List.of(
                "-Dshardshop.routing.config=" + configuration.toUri(),
                "-Dshardshop.order.process=saga",
                "-Dshardshop.id.generator-id=" + GENERATORS.incrementAndGet(),
                "-Dquarkus.http.host=127.0.0.1", "-Dquarkus.http.port=0"));
        if (pendingCrash != null) options.addAll(pendingCrash.options());
        saga = PackagedApplication.start("shardshop-order", directory, options);
        if (pendingCrash != null) {
            try { pendingCrash.attach(); }
            catch (Exception failed) {
                pendingCrash.close();
                saga.kill();
                throw new IOException("Could not attach to packaged saga crash breakpoint", failed);
            }
        }
        applications.add(saga);
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (!saga.isAlive()) throw new AssertionError("Saga exited during startup:\n" + saga.tail());
            if (saga.lines().stream().anyMatch(line -> line.contains("ShardShop order process ready: saga"))) return;
            Thread.sleep(50);
        }
        throw new AssertionError("Saga startup exceeded 30 seconds:\n" + saga.tail());
    }

    public void stop() throws InterruptedException {
        stopSaga();
        if (application != null && application.isAlive()) {
            application.stop();
        }
    }

    public List<String> problems() {
        return applications.stream().flatMap(app -> app.problems().stream()).toList();
    }

    @Override
    public void dispose() {
        try {
            stop();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        }
    }
}
