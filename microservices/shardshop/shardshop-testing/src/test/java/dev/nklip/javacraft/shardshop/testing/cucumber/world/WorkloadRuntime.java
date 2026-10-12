package dev.nklip.javacraft.shardshop.testing.cucumber.world;

import dev.nklip.javacraft.shardshop.testing.cucumber.config.PackagedApplication;
import dev.nklip.javacraft.shardshop.testing.cucumber.config.ShardShopFiles;
import org.picocontainer.Disposable;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The packaged seeder and reader of one scenario. They get only the product URL, as their pods on the lab do. Each
 * seeder run is a new JVM, like a new pod of the seeder Job.
 */
public final class WorkloadRuntime implements Disposable {
    private final ProductRuntime product;
    private final Path directory;
    private PackagedApplication seeder;
    private int seederExitCode;
    private PackagedApplication reader;
    private String seededDigest;
    private Map<String, StoredCatalog.Row> seededCatalog;

    public WorkloadRuntime(ProductRuntime product) throws IOException {
        this.product = product;
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
                "-Dshardshop.reader.report-interval=5s"));
        command.addAll(options);
        reader = PackagedApplication.start("shardshop-workload/shardshop-product-reader", directory, command);
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
            if (reader != null && reader.isAlive()) {
                reader.stop();
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        }
    }
}
