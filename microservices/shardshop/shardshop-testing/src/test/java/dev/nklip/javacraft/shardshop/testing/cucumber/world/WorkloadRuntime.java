package dev.nklip.javacraft.shardshop.testing.cucumber.world;

import dev.nklip.javacraft.shardshop.testing.cucumber.config.CatalogDatabase;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.PackagedApplication;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.ShardShopFiles;
import org.picocontainer.Disposable;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The packaged workload applications of one scenario. They get only the provider URLs, as their pods on the lab do. Each
 * seeder run is a new JVM, like a new pod of the seeder Job. The reader, the producer and the interactive seeder serve
 * their control pages on loopback ports of their own, which stay the same across restarts.
 */
public final class WorkloadRuntime implements Disposable {
    private final ProductRuntime product;
    private final OrderRuntime order;
    private final Path directory;
    private final int readerPort = CatalogDatabase.freePort();
    private final int producerPort = CatalogDatabase.freePort();
    private final int seederControlPort = CatalogDatabase.freePort();
    private PackagedApplication seeder;
    private PackagedApplication seederControl;
    private int seederExitCode;
    private PackagedApplication reader;
    private PackagedApplication producer;
    private final List<PackagedApplication> producers = new ArrayList<>();
    private String producerOrderUrl;
    private String seededDigest;
    private Map<String, StoredCatalog.Row> seededCatalog;

    public WorkloadRuntime(ProductRuntime product, OrderRuntime order) throws IOException {
        this.product = product;
        this.order = order;
        this.directory = ShardShopFiles.newWorkDirectory("workloads-");
    }

    public void runSeeder(List<String> options) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("-Dshardshop.seeder.product-url=" + productUrl()));
        command.addAll(options);
        seeder = PackagedApplication.start("shardshop-workload/shardshop-product-seeder", directory, command);
        seederExitCode = seeder.awaitExit(Duration.ofSeconds(180));
    }

    public void startReader(List<String> options) throws IOException {
        List<String> command = new ArrayList<>(List.of("-Dshardshop.reader.product-url=" + productUrl(),
                "-Dshardshop.reader.report-interval=5s", "-Dquarkus.http.host=127.0.0.1",
                "-Dquarkus.http.port=" + readerPort));
        command.addAll(options);
        reader = PackagedApplication.start("shardshop-workload/shardshop-product-reader", directory, command);
    }

    public void startProducer(String runName, int rejectedPercent) throws IOException {
        startProducer(runName, rejectedPercent, List.of("-Dshardshop.producer.order-rate=10"));
    }

    /** Starts the producer with additional JVM options, for example its initial order rate. */
    public void startProducer(String runName, int rejectedPercent, List<String> options) throws IOException {
        if (producer != null && producer.isAlive()) {
            throw new IllegalStateException("The order producer runs already");
        }
        List<String> command = new ArrayList<>(List.of(
                "-Dshardshop.producer.product-url=" + productUrl(),
                "-Dshardshop.producer.order-url=" + (producerOrderUrl == null
                        ? "http://127.0.0.1:" + order.port() : producerOrderUrl),
                "-Dshardshop.producer.run-name=" + runName,
                "-Dshardshop.producer.rejected-order-percent=" + rejectedPercent,
                "-Dshardshop.producer.startup-deadline=5m",
                "-Dshardshop.producer.order-deadline=30s",
                "-Dshardshop.producer.poll-interval=100ms",
                "-Dquarkus.http.host=127.0.0.1", "-Dquarkus.http.port=" + producerPort));
        command.addAll(options);
        producer = PackagedApplication.start("shardshop-workload/shardshop-order-producer", directory, command);
        producers.add(producer);
    }

    /** Starts the interactive seeder, which seeds only when a run is requested through its control API. */
    public void startSeederControl(List<String> options) throws IOException {
        if (seederControl != null && seederControl.isAlive()) {
            throw new IllegalStateException("The interactive seeder runs already");
        }
        List<String> command = new ArrayList<>(List.of("-Dshardshop.seeder.product-url=" + productUrl(),
                "-Dshardshop.seeder.interactive=true", "-Dquarkus.http.host=127.0.0.1",
                "-Dquarkus.http.port=" + seederControlPort));
        command.addAll(options);
        seederControl = PackagedApplication.start("shardshop-workload/shardshop-product-seeder", directory, command);
    }

    public PackagedApplication seederControl() {
        if (seederControl == null) {
            throw new IllegalStateException("The interactive seeder did not start in this scenario");
        }
        return seederControl;
    }

    /** The control API of a workload: order-producer, product-reader or product-seeder. */
    public URI controlUri(String workload) {
        int port = switch (workload) {
            case "order-producer" -> producerPort;
            case "product-reader" -> readerPort;
            case "product-seeder" -> seederControlPort;
            default -> throw new IllegalArgumentException("Unknown workload " + workload);
        };
        return URI.create("http://127.0.0.1:" + port + "/api/v1/" + workload + "/control");
    }

    /** Runs {@code --check-startup} of the packaged producer, which sends no HTTP request. */
    public PackagedApplication checkProducer(List<String> options) throws IOException {
        List<String> command = new ArrayList<>(List.of("-Dshardshop.producer.product-url=http://127.0.0.1:1",
                "-Dshardshop.producer.order-url=http://127.0.0.1:1", "-Dquarkus.http.host=127.0.0.1",
                "-Dquarkus.http.port=0"));
        command.addAll(options);
        return PackagedApplication.start("shardshop-workload/shardshop-order-producer", directory, command,
                List.of("--check-startup"));
    }

    public void producerOrderUrl(String url) {
        this.producerOrderUrl = url;
    }

    public PackagedApplication producer() {
        if (producer == null) {
            throw new IllegalStateException("The order producer did not start in this scenario");
        }
        return producer;
    }

    public boolean producerStarted() {
        return producer != null;
    }

    public int stopProducer() throws InterruptedException {
        return producer().stop();
    }

    public List<String> producerProblems() {
        return producers.stream().flatMap(application -> application.problems().stream()).toList();
    }

    private String productUrl() {
        return "http://127.0.0.1:" + product.port();
    }

    public PackagedApplication seeder() {
        if (seeder == null) {
            throw new IllegalStateException("The seeder did not run in this scenario");
        }
        return seeder;
    }

    public int seederExitCode() {
        return seederExitCode;
    }

    public PackagedApplication reader() {
        if (reader == null) {
            throw new IllegalStateException("The reader did not start in this scenario");
        }
        return reader;
    }

    /** Keeps the digest and the stored rows of the last complete seeder run. */
    public void seeded(String digest, Map<String, StoredCatalog.Row> catalog) {
        this.seededDigest = digest;
        this.seededCatalog = Map.copyOf(catalog);
    }

    public String seededDigest() {
        if (seededDigest == null) {
            throw new IllegalStateException("No seeder run completed in this scenario");
        }
        return seededDigest;
    }

    public Map<String, StoredCatalog.Row> seededCatalog() {
        if (seededCatalog == null) {
            throw new IllegalStateException("No seeder run completed in this scenario");
        }
        return seededCatalog;
    }

    @Override
    public void dispose() {
        try {
            if (producer != null && producer.isAlive()) {
                producer.stop();
            }
            if (reader != null && reader.isAlive()) {
                reader.stop();
            }
            if (seederControl != null && seederControl.isAlive()) {
                seederControl.stop();
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        }
    }
}
