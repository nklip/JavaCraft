package dev.nklip.javacraft.shardshop.testing.cucumber.world;

import dev.nklip.javacraft.shardshop.testing.cucumber.config.CatalogDatabase;
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

/** Packaged order HTTP provider, with runtime credentials and bounded primary pools. */
public final class OrderRuntime implements Disposable {
    private static final AtomicInteger GENERATORS = new AtomicInteger(500);
    private final CatalogDatabase database = CatalogDatabase.instance();
    private final CatalogShards shards;
    private final Path directory;
    private final int port = CatalogDatabase.freePort();
    private final int refusedPort = CatalogDatabase.freePort();
    private final List<PackagedApplication> applications = new ArrayList<>();
    private PackagedApplication application;
    private boolean stockEnabled;
    private RabbitMqBroker cdcBroker;

    public OrderRuntime(CatalogShards shards) throws IOException {
        this.shards = shards;
        directory = ShardShopFiles.newWorkDirectory("order-");
    }

    public void start(Set<String> unavailableShards) throws IOException, InterruptedException {
        Path configuration = Files.createTempFile(directory, "routing-", ".properties",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        StringJoiner values = new StringJoiner("\n", "", "\n");
        values.add("shardshop.routing.shards=" + shards.all().stream().map(CatalogShards.Shard::name)
                .collect(Collectors.joining(",")));
        values.add("shardshop.routing.regions=" + String.join(",", shards.regions()));
        values.add("shardshop.order.max-pool-size=1");
        values.add("shardshop.order.catalog-deadline=PT3S");
        // The step 4.1 scenarios inspect atomic acceptance before any saga worker runs.
        values.add("shardshop.order.stock.enabled=" + stockEnabled);
        values.add("shardshop.order.cdc.enabled=" + (cdcBroker != null));
        if (cdcBroker != null) {
            values.add("shardshop.order.cdc.broker.host=127.0.0.1");
            values.add("shardshop.order.cdc.broker.port=" + cdcBroker.port());
            values.add("shardshop.order.cdc.broker.virtual-host=shardshop");
            values.add("shardshop.order.cdc.broker.username=shardshop-order");
            values.add("shardshop.order.cdc.broker.password=" + cdcBroker.orderPassword());
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

    /** Enables the production reservation coordinator on subsequent starts, including restarts. */
    public void enableStock() {
        stockEnabled = true;
    }

    /** Enables the production WAL relay on subsequent starts, including restarts. */
    public void enableCdc(RabbitMqBroker broker) {
        cdcBroker = broker;
    }

    public int port() {
        return port;
    }

    public void stop() throws InterruptedException {
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
