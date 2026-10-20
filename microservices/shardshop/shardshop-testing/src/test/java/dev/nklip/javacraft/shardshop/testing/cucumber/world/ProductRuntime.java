package dev.nklip.javacraft.shardshop.testing.cucumber.world;

import dev.nklip.javacraft.shardshop.testing.cucumber.config.CatalogDatabase;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.PackagedApplication;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.RawHttp;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.ShardShopFiles;
import org.picocontainer.Disposable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * The packaged product of one scenario. Each start gets a new fixed test generator ID, or it reserves a new ID in the
 * Kubernetes API of the scenario, as product does on the lab. The HTTP port stays the same across restarts, thus a
 * running workload keeps its product URL.
 */
public final class ProductRuntime implements Disposable {
    private static final AtomicInteger GENERATOR_IDS = new AtomicInteger();
    private static final Duration STARTUP = Duration.ofSeconds(30);

    private final CatalogDatabase database = CatalogDatabase.instance();
    private final CatalogShards shards;
    private final Path directory;
    private final int port = CatalogDatabase.freePort();
    private final int refusedPort = CatalogDatabase.freePort();
    private final List<PackagedApplication> started = new ArrayList<>();
    private PackagedApplication application;
    private List<String> registryOptions;

    /** The database endpoints of one product start. Writes always use the primaries. */
    public enum Endpoints {
        /** The lab defaults: primary reads over verify-full TLS with the trusted CA. */
        PRIMARIES,
        /** Primary endpoints that refuse connections. */
        REFUSED_PRIMARIES,
        /** Only the second US primary refuses connections; the first US shard remains readable. */
        REFUSED_SECOND_US_PRIMARY,
        /** The replica read profile with replica endpoints that refuse connections; there is no primary fallback. */
        REFUSED_REPLICAS,
        /** The root certificate of an unrelated CA. */
        UNRELATED_CA,
        /** The host 127.0.0.1, which the server certificate does not name. */
        IP_ADDRESS_HOST
    }

    public ProductRuntime(CatalogShards shards) throws IOException {
        this.shards = shards;
        this.directory = ShardShopFiles.newWorkDirectory("product-");
    }

    public void start(Endpoints endpoints) throws IOException, InterruptedException {
        if (application != null && application.isAlive()) {
            throw new IllegalStateException("Product runs already");
        }
        Path routing = Files.createTempFile(directory, "routing-", ".properties",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(routing, configuration(endpoints), StandardCharsets.UTF_8);
        List<String> options = new ArrayList<>(List.of("-Dshardshop.routing.config=" + routing.toUri(),
                "-Dquarkus.http.host=127.0.0.1", "-Dquarkus.http.port=" + port));
        options.addAll(registryOptions == null
                ? List.of("-Dshardshop.id.generator-id=" + GENERATOR_IDS.incrementAndGet()) : registryOptions);
        application = PackagedApplication.start("shardshop-product", directory, options);
        started.add(application);
        awaitReady();
    }

    /** The next starts reserve their generator IDs with these JVM options instead of a fixed test ID. */
    public void reserveGeneratorIds(List<String> options) {
        registryOptions = List.copyOf(options);
    }

    public void restart(Endpoints endpoints) throws IOException, InterruptedException {
        stop();
        start(endpoints);
    }

    public void stop() throws InterruptedException {
        if (application != null && application.isAlive()) {
            application.stop();
        }
    }

    private String configuration(Endpoints endpoints) {
        StringJoiner properties = new StringJoiner("\n", "", "\n");
        properties.add("shardshop.routing.shards=" + shards.all().stream().map(CatalogShards.Shard::name)
                .collect(Collectors.joining(",")));
        properties.add("shardshop.routing.regions=" + String.join(",", shards.regions()));
        properties.add("shardshop.catalog.max-pool-size=2");
        properties.add("shardshop.catalog.read-profile=" + (endpoints == Endpoints.REFUSED_REPLICAS ? "replica" : "primary"));
        String host = endpoints == Endpoints.IP_ADDRESS_HOST ? "127.0.0.1" : "localhost";
        int primaryPort = endpoints == Endpoints.REFUSED_PRIMARIES ? refusedPort : database.port();
        Path certificate = endpoints == Endpoints.UNRELATED_CA ? database.unrelatedCa() : database.trustedCa();
        for (CatalogShards.Shard shard : shards.all()) {
            String prefix = "shardshop.catalog.connections." + shard.name() + ".";
            int shardPrimaryPort = endpoints == Endpoints.REFUSED_SECOND_US_PRIMARY
                    && shard.name().equals("shard-d") ? refusedPort : primaryPort;
            // Bare URLs: product adds sslmode=verify-full and the root certificate, as with the lab defaults.
            properties.add(prefix + "primary-url=jdbc:postgresql://" + host + ":" + shardPrimaryPort + "/" + shard.database());
            properties.add(prefix + "replica-url=jdbc:postgresql://localhost:" + refusedPort + "/" + shard.database());
            properties.add(prefix + "root-certificate=" + certificate);
            properties.add(prefix + "username=product_app");
            properties.add(prefix + "password=" + database.productPassword());
        }
        return properties.toString();
    }

    /** Product opens its port after the routing, generator and catalog checks; an invalid ID then returns 400. */
    private void awaitReady() throws InterruptedException {
        long deadline = System.nanoTime() + STARTUP.toNanos();
        while (System.nanoTime() < deadline) {
            if (!application.isAlive()) {
                throw new AssertionError("Product exited during startup:\n" + application.tail());
            }
            try {
                RawHttp.Response response = RawHttp.exchange(port, "GET", "/api/v1/sellers/0", List.of(), new byte[0],
                        RawHttp.Framing.NONE);
                if (response.status() == 400) {
                    return;
                }
            } catch (IOException notReady) {
                // Product has not opened its HTTP port yet.
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Product did not become ready within " + STARTUP + ":\n" + application.tail());
    }

    public int port() {
        return port;
    }

    public PackagedApplication application() {
        if (application == null) {
            throw new IllegalStateException("Product did not start in this scenario");
        }
        return application;
    }

    /** The WARN and ERROR lines of all product JVMs of the scenario, each with the name of its log file. */
    public List<String> problems() {
        return started.stream()
                .flatMap(jvm -> jvm.problems().stream().map(line -> jvm.log().getFileName() + ": " + line))
                .toList();
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
