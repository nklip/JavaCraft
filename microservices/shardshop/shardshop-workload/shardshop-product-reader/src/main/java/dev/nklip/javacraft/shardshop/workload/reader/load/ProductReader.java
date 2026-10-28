package dev.nklip.javacraft.shardshop.workload.reader.load;

import dev.nklip.javacraft.shardshop.common.Sha256;
import dev.nklip.javacraft.shardshop.workload.control.WorkloadControl;
import dev.nklip.javacraft.shardshop.workload.control.RatePacer;
import dev.nklip.javacraft.shardshop.workload.reader.catalog.ReaderDatasets;
import dev.nklip.javacraft.shardshop.workload.reader.catalog.ReaderDatasets.ProductDescriptor;
import dev.nklip.javacraft.shardshop.workload.reader.catalog.ReaderDatasets.SellerDescriptor;
import dev.nklip.javacraft.shardshop.workload.reader.http.ProductRequests;
import dev.nklip.javacraft.shardshop.workload.reader.http.Read;
import dev.nklip.javacraft.shardshop.workload.reader.http.ReadResult;
import dev.nklip.javacraft.shardshop.workload.reader.http.ReadResult.Outcome;
import dev.nklip.javacraft.shardshop.workload.reader.metrics.ReaderMetrics;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;

/**
 * Resolves the catalog through product lookups by creation key. Then a fixed number of workers read randomly
 * selected products by their issued IDs at one total rate until {@link #stop()}. The live control sets that rate and
 * can pause new reads. Each report interval logs the request, latency, error and pool metrics: INFO when all reads
 * succeeded, otherwise WARN.
 */
public final class ProductReader {
    private static final Logger LOG = Logger.getLogger(ProductReader.class);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(10);
    /** The traffic category of all reads and their repeated attempts. */
    public static final String READS = "reads";
    /** Outcomes that a later resolution can change: a missing entity, or an unavailable, slow or absent product. */
    private static final Set<Outcome> NOT_READY = EnumSet.of(Outcome.NOT_FOUND, Outcome.UNAVAILABLE,
            Outcome.REPLICA_UNAVAILABLE, Outcome.DEADLINE, Outcome.TRANSPORT);

    private final ReaderDatasets datasets;
    private final ProductRequests requests;
    private final WorkloadControl control;
    private final ReaderMetrics metrics;
    private final int workers;
    private final Duration reportInterval;
    private final LongSupplier nanoTime;
    private final RandomGenerator random;
    private final AtomicBoolean failed = new AtomicBoolean();
    private ExecutorService executor;
    private ScheduledExecutorService scheduler;
    private Catalog startedCatalog;
    private boolean started;
    private boolean stopped;

    public ProductReader(ReaderDatasets datasets, ProductRequests requests, WorkloadControl control,
                         ReaderMetrics metrics, int workers, Duration reportInterval, LongSupplier nanoTime,
                         RandomGenerator random) {
        this.datasets = Objects.requireNonNull(datasets);
        this.requests = Objects.requireNonNull(requests);
        this.control = Objects.requireNonNull(control);
        this.metrics = Objects.requireNonNull(metrics);
        if (workers < requests.maxConnections() || workers > 256 || reportInterval.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException("Invalid reader worker configuration");
        }
        this.workers = workers;
        this.reportInterval = reportInterval;
        this.nanoTime = Objects.requireNonNull(nanoTime);
        this.random = Objects.requireNonNull(random);
    }

    /**
     * Resolves every seller and product with concurrent lookups, which also open the connections. A missing entity
     * or an unavailable product throws {@link CatalogNotReadyException}; the caller can then resolve again. Other
     * outcomes throw {@link ReaderException}.
     */
    public Catalog resolve() throws ReaderException, InterruptedException {
        ExecutorService lookups;
        synchronized (this) {
            if (started) {
                throw new IllegalStateException("The product reader resolves the catalog only before it starts");
            }
            if (stopped) {
                throw new InterruptedException("Catalog resolution was stopped");
            }
            if (executor == null) {
                executor = Executors.newFixedThreadPool(workers,
                        Thread.ofPlatform().name("product-reader-", 1).factory());
            }
            lookups = executor;
        }
        return resolveCatalog(lookups);
    }

    /**
     * Starts the workers on a catalog that {@link #resolve()} of this reader returned. A worker that fails
     * unexpectedly marks the reader as failed and runs {@code onFailure}. After {@link #stop()}, no load starts.
     */
    public void start(Catalog catalog, Runnable onFailure) {
        Objects.requireNonNull(catalog);
        Objects.requireNonNull(onFailure);
        if (catalog.owner != this) {
            throw new IllegalArgumentException("The catalog belongs to a different product reader");
        }
        synchronized (this) {
            if (started) {
                throw new IllegalStateException("The product reader starts only once");
            }
            started = true;
            if (stopped) {
                return;
            }
            startedCatalog = catalog;
            control.phase(WorkloadControl.Phase.ACTIVE);
            metrics.begin();
            scheduler = Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().name("product-reader-report").daemon().factory());
            long interval = reportInterval.toMillis();
            scheduler.scheduleAtFixedRate(() -> report(catalog), interval, interval, TimeUnit.MILLISECONDS);
            for (int worker = 0; worker < workers; worker++) {
                executor.submit(() -> read(catalog.targets, onFailure));
            }
        }
        LOG.info("Reading at " + control.status().targetRate().toPlainString() + " requests/s with " + workers + " workers and "
                + requests.maxConnections() + " connections");
    }

    /**
     * Stops the workers and the reports, logs the totals since start as the last report, and closes the
     * connections. The deadline of each read limits the wait for the workers. Later calls do nothing.
     */
    public void stop() {
        List<ExecutorService> services = new ArrayList<>(2);
        Catalog catalog;
        synchronized (this) {
            if (stopped) {
                return;
            }
            stopped = true;
            catalog = startedCatalog;
            if (scheduler != null) {
                services.add(scheduler);
            }
            if (executor != null) {
                services.add(executor);
            }
        }
        for (ExecutorService service : services) {
            for (Runnable queued : service.shutdownNow()) {
                // All tasks are submitted as futures. A queued one never runs; cancelling it lets a resolution
                // that waits for it return.
                ((Future<?>) queued).cancel(false);
            }
        }
        try {
            for (ExecutorService service : services) {
                if (!service.awaitTermination(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    LOG.warn("Product reader threads did not stop within " + STOP_TIMEOUT.toSeconds() + " seconds");
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (catalog != null) {
            log(metrics.total(requests.poolStats()), catalog);
        }
        requests.close();
    }

    public boolean failed() {
        return failed.get();
    }

    private Catalog resolveCatalog(ExecutorService lookups) throws ReaderException, InterruptedException {
        List<SellerDescriptor> sellers = lookUpAll(lookups, datasets.sellers(),
                seller -> lookUp("Look up seller " + seller.key(), datasets.lookupSeller(seller)));
        Map<String, SellerDescriptor> sellersByKey = new HashMap<>();
        sellers.forEach(seller -> sellersByKey.put(seller.definition().key(), seller));
        List<ProductDescriptor> products = lookUpAll(lookups, datasets.products(), product -> lookUp(
                "Look up product " + product.key(),
                datasets.lookupProduct(product, sellersByKey.get(product.sellerKey()))));
        String digest = mappingDigest(sellers, products);
        LOG.info("Resolved " + datasets.catalogVersion() + " " + regions(sellers, products)
                + ", key-to-ID SHA-256 " + digest);
        List<Target> targets = new ArrayList<>(products.size());
        for (ProductDescriptor product : products) {
            targets.add(new Target(sellersByKey.get(product.definition().sellerKey()).definition().region(),
                    datasets.readProduct(product)));
        }
        return new Catalog(this, List.copyOf(targets), datasets.catalogVersion(), digest);
    }

    /** Runs the lookups concurrently in dataset order. After the first failure, the remaining lookups do nothing. */
    private <D, T> List<T> lookUpAll(ExecutorService lookups, List<D> definitions, Lookup<D, T> lookup)
            throws ReaderException, InterruptedException {
        AtomicReference<ReaderException> failure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        List<Callable<T>> tasks = new ArrayList<>(definitions.size());
        for (D definition : definitions) {
            tasks.add(() -> {
                if (failure.get() != null) {
                    return null;
                }
                try {
                    return lookup.apply(definition);
                } catch (ReaderException failed) {
                    failure.compareAndSet(null, failed);
                } catch (InterruptedException stopping) {
                    interrupted.set(true);
                }
                return null;
            });
        }
        List<T> results = new ArrayList<>(definitions.size());
        try {
            for (Future<T> future : lookups.invokeAll(tasks)) {
                results.add(future.get());
            }
        } catch (CancellationException stopping) {
            interrupted.set(true);
        } catch (ExecutionException unexpected) {
            throw new IllegalStateException("A catalog lookup failed unexpectedly", unexpected.getCause());
        }
        if (interrupted.get()) {
            throw new InterruptedException("Catalog resolution was stopped");
        }
        if (failure.get() != null) {
            throw failure.get();
        }
        return results;
    }

    private <T> T lookUp(String operation, Read<T> read) throws ReaderException, InterruptedException {
        ReadResult<T> result = requests.get(read);
        if (result.outcome() == Outcome.OK) {
            return result.value();
        }
        String message = operation + " failed with " + result.outcome();
        throw NOT_READY.contains(result.outcome()) ? new CatalogNotReadyException(message) : new ReaderException(message);
    }

    private void read(List<Target> targets, Runnable onFailure) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                RatePacer.StartTiming timing = null;
                if (metrics.diagnosticsEnabled()) {
                    timing = control.awaitStartTiming();
                } else {
                    control.awaitStart();
                }
                control.started();
                Target target = targets.get(random.nextInt(targets.size()));
                long started = nanoTime.getAsLong();
                ReadResult<ProductDescriptor> result;
                long dispatched = 0;
                try (WorkloadControl.Operation ignored = control.begin()) {
                    control.traffic().sent(READS, false);
                    if (timing != null) {
                        dispatched = nanoTime.getAsLong();
                    }
                    result = requests.get(target.read());
                }
                if (result.outcome() != Outcome.OK) {
                    control.traffic().failed(READS);
                }
                long latency = nanoTime.getAsLong() - started;
                if (timing == null) {
                    metrics.record(target.region(), result, latency);
                } else {
                    metrics.record(target.region(), result, latency, timing, dispatched);
                }
            }
        } catch (InterruptedException stopping) {
            // stop() interrupts the workers.
        } catch (RuntimeException failure) {
            if (failed.compareAndSet(false, true)) {
                control.phase(WorkloadControl.Phase.FAILED);
                LOG.error("A product reader worker failed", failure);
                onFailure.run();
            }
        }
    }

    private void report(Catalog catalog) {
        log(metrics.nextInterval(requests.poolStats()), catalog);
    }

    private static void log(ReaderMetrics.Report report, Catalog catalog) {
        String summary = report.summary() + ", " + catalog.catalogVersion + " key-to-ID SHA-256 " + catalog.digest
                + report.schedulingSummary();
        if (report.failed()) {
            LOG.warn(summary);
        } else {
            LOG.info(summary);
        }
    }

    private static String regions(List<SellerDescriptor> sellers, List<ProductDescriptor> products) {
        Map<String, String> regionsBySellerKey = new HashMap<>();
        Map<String, int[]> counts = new LinkedHashMap<>();
        for (SellerDescriptor seller : sellers) {
            regionsBySellerKey.put(seller.definition().key(), seller.definition().region());
            counts.computeIfAbsent(seller.definition().region(), region -> new int[2])[0]++;
        }
        for (ProductDescriptor product : products) {
            counts.get(regionsBySellerKey.get(product.definition().sellerKey()))[1]++;
        }
        StringJoiner text = new StringJoiner("; ", "[", "]");
        counts.forEach((region, count) -> text.add(region + " " + count[0] + " sellers, " + count[1] + " products"));
        return text.toString();
    }

    /**
     * Lowercase hex SHA-256 of one "key=sellerId" line per seller and one "key=sellerId/productId" line per product,
     * each ended by LF, in dataset order. The seeder logs the same digest for the IDs that it verified.
     */
    static String mappingDigest(List<SellerDescriptor> sellers, List<ProductDescriptor> products) {
        StringBuilder mapping = new StringBuilder();
        for (SellerDescriptor seller : sellers) {
            mapping.append(seller.definition().key()).append('=').append(seller.sellerId()).append('\n');
        }
        for (ProductDescriptor product : products) {
            mapping.append(product.definition().key()).append('=').append(product.sellerId()).append('/')
                    .append(product.productId()).append('\n');
        }
        return String.format("%064x", Sha256.unsignedDigest(mapping.toString()));
    }

    /** The issued IDs of the complete dataset, which {@link #resolve()} of one reader returned. */
    public static final class Catalog {
        private final ProductReader owner;
        private final List<Target> targets;
        private final String catalogVersion;
        private final String digest;

        private Catalog(ProductReader owner, List<Target> targets, String catalogVersion, String digest) {
            this.owner = owner;
            this.targets = targets;
            this.catalogVersion = catalogVersion;
            this.digest = digest;
        }
    }

    private record Target(String region, Read<ProductDescriptor> read) { }

    @FunctionalInterface
    private interface Lookup<D, T> {
        T apply(D definition) throws ReaderException, InterruptedException;
    }
}
