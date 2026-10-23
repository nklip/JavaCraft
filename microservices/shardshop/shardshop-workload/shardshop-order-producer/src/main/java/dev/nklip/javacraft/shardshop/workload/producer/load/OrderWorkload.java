package dev.nklip.javacraft.shardshop.workload.producer.load;

import com.fasterxml.jackson.core.JsonProcessingException;
import dev.nklip.javacraft.shardshop.common.HttpResponseException;
import dev.nklip.javacraft.shardshop.workload.producer.ProducerDatasets;
import dev.nklip.javacraft.shardshop.workload.producer.ProducerDatasets.CreatedBuyer;
import dev.nklip.javacraft.shardshop.workload.producer.ProducerDatasets.ProductDescriptor;
import dev.nklip.javacraft.shardshop.workload.producer.ProducerDatasets.SellerDescriptor;
import dev.nklip.javacraft.shardshop.workload.producer.http.OrderRequests;
import dev.nklip.javacraft.shardshop.workload.producer.http.OrderStatus;
import dev.nklip.javacraft.shardshop.workload.producer.plan.OrderPlanner;
import dev.nklip.javacraft.shardshop.workload.producer.plan.PlannedOrder;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.DoubleSupplier;

/** HTTP-only phases. The caller supplies an interruptible deadline for each complete phase. */
public final class OrderWorkload {
    /** Catalog lookups and buyer creation. */
    public static final String STARTUP = "startup";
    public static final String ALLOCATION = "allocation";
    public static final String SUBMISSION = "submission";
    public static final String POLLING = "polling";
    private static final Logger LOG = Logger.getLogger(OrderWorkload.class);
    private final ProducerDatasets datasets;
    private final OrderRequests orders;
    private final RetryPolicy retry;
    private final RetryPolicy.Pause pause;
    private final DoubleSupplier random;
    private final Duration pollInterval;

    public OrderWorkload(ProducerDatasets datasets, OrderRequests orders, RetryPolicy retry,
                         RetryPolicy.Pause pause, DoubleSupplier random, Duration pollInterval) {
        this.datasets = Objects.requireNonNull(datasets);
        this.orders = Objects.requireNonNull(orders);
        this.retry = Objects.requireNonNull(retry);
        this.pause = Objects.requireNonNull(pause);
        this.random = Objects.requireNonNull(random);
        this.pollInterval = Objects.requireNonNull(pollInterval);
    }

    public List<ProductDescriptor> resolveCatalog() throws ProducerException, InterruptedException {
        Duration backoff = Duration.ofSeconds(1);
        while (true) {
            try {
                return catalog();
            } catch (HttpResponseException failure) {
                if (failure.statusCode() != 404 && failure.statusCode() != 503) {
                    throw RetryPolicy.failed("Catalog lookup", failure);
                }
            } catch (JsonProcessingException | CharacterCodingException failure) {
                throw RetryPolicy.failed("Catalog validation", failure);
            } catch (IOException ignored) {
                // A missing response cannot prove that the catalog is incomplete or invalid.
            } catch (IllegalArgumentException failure) {
                throw RetryPolicy.failed("Catalog validation", failure);
            }
            LOG.info("Order producer waiting for the complete catalog");
            long ceiling = Math.min(Duration.ofSeconds(15).toNanos(), backoff.toNanos() * 2);
            pause.sleep(Duration.ofNanos(backoff.toNanos()
                    + (long) ((ceiling - backoff.toNanos()) * random.getAsDouble())));
            backoff = Duration.ofNanos(ceiling);
        }
    }

    private List<ProductDescriptor> catalog() throws IOException, InterruptedException {
        Map<String, SellerDescriptor> sellers = new HashMap<>();
        var sellerIds = new HashSet<String>();
        var productIds = new HashSet<String>();
        for (var definition : datasets.sellers()) {
            SellerDescriptor seller = retry.once(STARTUP, () -> datasets.lookupSeller(definition));
            if (!sellerIds.add(seller.sellerId())) {
                throw new IllegalArgumentException("Catalog seller IDs are not unique");
            }
            sellers.put(definition.key(), seller);
        }
        List<ProductDescriptor> products = new ArrayList<>();
        for (var definition : datasets.products()) {
            SellerDescriptor seller = sellers.get(definition.sellerKey());
            ProductDescriptor product = retry.once(STARTUP, () -> datasets.lookupProduct(definition, seller));
            if (!productIds.add(product.productId())) {
                throw new IllegalArgumentException("Catalog product IDs are not unique");
            }
            products.add(product);
        }
        return List.copyOf(products);
    }

    public List<CreatedBuyer> createBuyers() throws ProducerException, InterruptedException {
        List<CreatedBuyer> buyers = new ArrayList<>();
        var ids = new HashSet<String>();
        for (var definition : datasets.buyers()) {
            CreatedBuyer buyer = retry.call(STARTUP, "Buyer creation", () -> datasets.createBuyer(definition));
            if (!ids.add(buyer.buyerId())) {
                throw new ProducerException("Buyer response IDs are not unique", null);
            }
            buyers.add(buyer);
        }
        return List.copyOf(buyers);
    }

    /** A planner over the configured dataset versions that resolved these descriptors. */
    public OrderPlanner planner(String runName, int rejectedOrderPercent, List<CreatedBuyer> buyers,
                                List<ProductDescriptor> products) {
        return new OrderPlanner(runName, rejectedOrderPercent, buyers, products, datasets.catalog(),
                datasets.buyerDataset());
    }

    public Completion write(OrderPlanner planner, String runName, long ordinal, Progress progress)
            throws ProducerException, InterruptedException {
        String buyerId = planner.buyer(ordinal).buyerId();
        progress.buyer(buyerId);
        String orderId = retry.call(ALLOCATION, "Order allocation",
                () -> orders.allocate(buyerId, planner.allocationRunName(), ordinal));
        progress.order(orderId);
        PlannedOrder order = planner.plan(ordinal, orderId);
        // Construct once. Every retry sends the same IDs, currency, item order and quantities.
        OrderRequests.Submission submission = retry.call(SUBMISSION, "Order submission", () -> orders.submitWithReplay(order));
        OrderStatus status = submission.status();
        boolean recovered = submission.replayed();
        progress.status(status.status());
        if (!recovered) {
            progress.accept();
        }
        return new Completion(status, recovered);
    }

    /** One status read with the existing retry budget; scheduling and the complete deadline belong to the caller. */
    public OrderStatus observe(OrderStatus previous, Progress progress) throws ProducerException, InterruptedException {
        OrderStatus status = retry.call(POLLING, "Order status",
                () -> orders.status(previous.buyerId(), previous.orderId()));
        progress.status(status.status());
        return status;
    }

    public Duration pollInterval() {
        return pollInterval;
    }

    /** Latest status after PUT; recovered means the service reports an idempotent replay, terminal or pending. */
    public record Completion(OrderStatus status, boolean recovered) {
        public Completion {
            Objects.requireNonNull(status);
        }
    }

    /**
     * The last known state of the ordinal in progress, for the failure line. One phase thread writes it.
     * {@code accepted} runs when order accepts the ordinal as a new order of this process.
     */
    public static final class Progress {
        private final Runnable accepted;
        private volatile String buyerId;
        private volatile String orderId;
        private volatile String status;

        public Progress() {
            this(() -> { });
        }

        public Progress(Runnable accepted) {
            this.accepted = Objects.requireNonNull(accepted);
        }

        void accept() {
            accepted.run();
        }

        void buyer(String value) {
            buyerId = value;
        }

        void order(String value) {
            orderId = value;
        }

        void status(String value) {
            status = value;
        }

        /** IDs and statuses only: no buyer profile or HTTP body. */
        public String describe(long ordinal) {
            String order = orderId;
            String last = status;
            return "ordinal " + ordinal + (buyerId == null ? "" : ", buyer " + buyerId)
                    + (order == null ? ", no allocated order" : ", order " + order)
                    + (last == null ? "" : ", last status " + last);
        }
    }
}
