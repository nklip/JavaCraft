package dev.nklip.javacraft.shardshop.order.ordering;

import dev.nklip.javacraft.shardshop.common.Sha256;
import dev.nklip.javacraft.shardshop.common.CurrencyPolicy;
import dev.nklip.javacraft.shardshop.idgen.IdGenerationUnavailableException;
import dev.nklip.javacraft.shardshop.idgen.IdGenerator;
import dev.nklip.javacraft.shardshop.sharding.Shard;
import dev.nklip.javacraft.shardshop.sharding.ShardRouter;
import dev.nklip.javacraft.shardshop.sharding.ShardTopology;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static dev.nklip.javacraft.shardshop.order.ordering.OrderFailure.Reason.*;
import static dev.nklip.javacraft.shardshop.order.ordering.OrderModel.*;

/** Coordinates two short local transactions around independent, bounded primary catalog reads. */
public final class OrderService {
    private final OrderStore store;
    private final IdGenerator generator;
    private final ShardRouter router;
    private final ShardTopology topology;
    private final int maxCandidates;
    private final int catalogConcurrency;
    private final long catalogDeadlineNanos;

    /** Checked service settings; the wiring creates them before any bean reserves a generator ID. */
    public record Settings(int maxIdCandidates, int catalogConcurrency, Duration catalogDeadline) {
        public Settings {
            if (maxIdCandidates < 1 || maxIdCandidates > 65536) {
                throw new IllegalArgumentException("Order ID candidate budget must be in 1..65536");
            }
            if (catalogConcurrency < 1 || catalogConcurrency > 64) {
                throw new IllegalArgumentException("Catalog concurrency must be in 1..64");
            }
            if (catalogDeadline == null || catalogDeadline.isNegative() || catalogDeadline.isZero()
                    || catalogDeadline.compareTo(Duration.ofMinutes(1)) > 0) {
                throw new IllegalArgumentException("Catalog deadline must be positive and at most one minute");
            }
        }
    }

    public OrderService(OrderStore store, IdGenerator generator, ShardRouter router, ShardTopology topology,
                        Settings settings) {
        this.store = store;
        this.generator = generator;
        this.router = router;
        this.topology = topology;
        this.maxCandidates = settings.maxIdCandidates();
        this.catalogConcurrency = settings.catalogConcurrency();
        this.catalogDeadlineNanos = settings.catalogDeadline().toNanos();
    }

    public Creation<Buyer> createBuyer(String key, BuyerCreation creation) {
        if (key == null || !key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new OrderFailure(INVALID_REQUEST);
        }
        Shard home = buyerKeyShard(creation.region(), key);
        return store.write(home, session -> {
            session.lock(lockKey("buyer", creation.region(), key));
            Optional<Buyer> existing = session.buyerByKey(creation.region(), key);
            if (existing.isPresent()) {
                Buyer buyer = existing.orElseThrow();
                if (!buyer.creation().equals(creation)) {
                    throw new OrderFailure(BUYER_IDEMPOTENCY_CONFLICT);
                }
                return new Creation<>(buyer, false);
            }
            long buyerId = buyerId(home);
            session.insertBuyer(buyerId, key, creation);
            return new Creation<>(new Buyer(buyerId, creation), true);
        });
    }

    public Buyer buyer(long buyerId) {
        positive(buyerId);
        return store.read(router.route(buyerId), session -> session.buyer(buyerId)
                .orElseThrow(() -> new OrderFailure(BUYER_NOT_FOUND)));
    }

    public Creation<Allocation> allocateOrder(long buyerId, AllocationRequest request) {
        positive(buyerId);
        if (request.requestOrdinal() < 0 || request.runName() == null || request.runName().isBlank()) {
            throw new OrderFailure(INVALID_REQUEST);
        }
        return store.write(router.route(buyerId), session -> {
            session.lock(lockKey("allocation", Long.toString(buyerId),
                    request.runName() + "\0" + request.requestOrdinal()));
            var existing = session.allocation(buyerId, request.runName(), request.requestOrdinal());
            if (existing.isPresent()) {
                return new Creation<>(existing.orElseThrow(), false);
            }
            if (session.buyer(buyerId).isEmpty()) {
                throw new OrderFailure(MISSING_BUYER);
            }
            Allocation allocation = new Allocation(buyerId, nextId(), request.runName(), request.requestOrdinal());
            session.insertAllocation(allocation);
            return new Creation<>(allocation, true);
        });
    }

    public Order putOrder(long buyerId, long orderId, OrderCreation creation) {
        return putOrderWithOutcome(buyerId, orderId, creation).entity();
    }

    /** Reports durable replay without a second status read or a race with the saga. */
    public Creation<Order> putOrderWithOutcome(long buyerId, long orderId, OrderCreation creation) {
        validate(buyerId, orderId, creation);
        String fingerprint = fingerprint(buyerId, orderId, creation);
        Shard home = router.route(buyerId);
        Optional<Order> previous = store.write(home, session -> checkedOrder(session, buyerId, orderId, fingerprint));
        if (previous.isPresent()) {
            return new Creation<>(previous.orElseThrow(), false);
        }

        CatalogOutcome outcome = catalog(creation);
        return store.write(home, session -> {
            Optional<Order> concurrent = checkedOrder(session, buyerId, orderId, fingerprint);
            if (concurrent.isPresent()) {
                return new Creation<>(concurrent.orElseThrow(), false);
            }
            if (outcome.failure() != null) {
                throw new OrderFailure(outcome.failure());
            }
            List<ItemSnapshot> items = snapshots(creation, outcome.products());
            BigDecimal total = total(items);
            long sagaId = nextId();
            long commandId = nextId();
            Order order = new Order(buyerId, orderId, fingerprint, creation.currency(), total, "PENDING_STOCK", null);
            session.insertOrder(order, items, sagaId, commandId);
            return new Creation<>(order, true);
        });
    }

    public Order order(long buyerId, long orderId) {
        positive(buyerId);
        positive(orderId);
        return store.read(router.route(buyerId), session -> session.order(buyerId, orderId)
                .orElseThrow(() -> new OrderFailure(ORDER_NOT_FOUND)));
    }

    private Optional<Order> checkedOrder(OrderStore.Session session, long buyerId, long orderId, String fingerprint) {
        session.lock(orderLock(orderId));
        if (session.quarantined(orderId)) {
            throw new OrderFailure(ORDER_RECONCILIATION_REQUIRED);
        }
        if (!session.allocatedTo(orderId, buyerId)) {
            throw new OrderFailure(INVALID_REQUEST);
        }
        Optional<Order> existing = session.order(buyerId, orderId);
        if (existing.isPresent() && !existing.orElseThrow().fingerprint().equals(fingerprint)) {
            throw new OrderFailure(ORDER_ID_CONFLICT);
        }
        return existing;
    }

    private CatalogOutcome catalog(OrderCreation creation) {
        // The usual single-item request needs no executor, futures or extra virtual threads.
        // The store already enforces the acquisition/query deadline on its bounded database worker.
        if (creation.items().size() == 1) {
            OrderItem item = creation.items().getFirst();
            long started = System.nanoTime();
            Optional<Product> product;
            try {
                product = store.product(router.route(item.sellerId()), item.sellerId(), item.productId(),
                        Duration.ofNanos(catalogDeadlineNanos));
            } catch (RuntimeException unavailable) {
                return new CatalogOutcome(List.of(), CATALOG_UNAVAILABLE);
            }
            if (System.nanoTime() - started >= catalogDeadlineNanos) {
                return new CatalogOutcome(List.of(), CATALOG_UNAVAILABLE);
            }
            return product.isEmpty() ? new CatalogOutcome(List.of(), PRODUCT_NOT_FOUND)
                    : validateCatalog(creation, List.of(product.orElseThrow()));
        }
        long deadline = System.nanoTime() + catalogDeadlineNanos;
        // shutdownNow is deliberate: executor.close() would wait past the request deadline for stuck IO.
        var executor = Executors.newFixedThreadPool(catalogConcurrency, Thread.ofVirtual().factory());
        List<Future<Optional<Product>>> futures = new ArrayList<>();
        List<Product> products = new ArrayList<>();
        boolean unavailable = false;
        boolean missing = false;
        try {
            for (OrderItem item : creation.items()) {
                futures.add(executor.submit(() -> {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        throw new OrderFailure(CATALOG_UNAVAILABLE);
                    }
                    Optional<Product> result = store.product(router.route(item.sellerId()), item.sellerId(), item.productId(),
                            Duration.ofNanos(remaining));
                    if (deadline - System.nanoTime() <= 0) {
                        throw new OrderFailure(CATALOG_UNAVAILABLE);
                    }
                    return result;
                }));
            }
            for (Future<Optional<Product>> future : futures) {
                try {
                    long remaining = deadline - System.nanoTime();
                    if (!future.isDone() && remaining <= 0) {
                        unavailable = true;
                        continue;
                    }
                    Optional<Product> product = future.get(Math.max(0, remaining), TimeUnit.NANOSECONDS);
                    if (product.isEmpty()) {
                        missing = true;
                    } else {
                        products.add(product.orElseThrow());
                    }
                } catch (ExecutionException | TimeoutException failure) {
                    unavailable = true;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    unavailable = true;
                }
            }
        } finally {
            futures.forEach(future -> future.cancel(true));
            executor.shutdownNow();
        }
        if (unavailable) {
            return new CatalogOutcome(List.of(), CATALOG_UNAVAILABLE);
        }
        if (missing) {
            return new CatalogOutcome(List.of(), PRODUCT_NOT_FOUND);
        }
        return validateCatalog(creation, products);
    }

    private CatalogOutcome validateCatalog(OrderCreation creation, List<Product> products) {
        if (products.stream().map(Product::currency).distinct().count() > 1) {
            return new CatalogOutcome(List.of(), MIXED_CURRENCIES);
        }
        if (!products.getFirst().currency().equals(creation.currency())) {
            return new CatalogOutcome(List.of(), CURRENCY_MISMATCH);
        }
        if (products.stream().anyMatch(product -> !CurrencyPolicy.permits(
                product.sellerRegion(), product.sellerCountryCode(), product.currency())
                || !CurrencyPolicy.validAmount(product.price(), product.currency())
                || product.price().signum() < 0 || product.unitCost() == null
                || !CurrencyPolicy.validAmount(product.unitCost(), product.currency())
                || product.unitCost().signum() < 0)) {
            return new CatalogOutcome(List.of(), PRODUCT_CURRENCY_POLICY);
        }
        return new CatalogOutcome(List.copyOf(products), null);
    }

    private static List<ItemSnapshot> snapshots(OrderCreation creation, List<Product> products) {
        List<ItemSnapshot> snapshots = new ArrayList<>();
        for (int position = 0; position < creation.items().size(); position++) {
            OrderItem item = creation.items().get(position);
            Product product = products.get(position);
            snapshots.add(new ItemSnapshot(position, item.sellerId(), item.productId(), item.quantity(),
                    product.name(), product.price(), product.unitCost(), product.currency()));
        }
        return List.copyOf(snapshots);
    }

    private static BigDecimal total(List<ItemSnapshot> items) {
        BigDecimal total = BigDecimal.ZERO;
        for (ItemSnapshot item : items) {
            total = total.add(item.unitPrice().multiply(BigDecimal.valueOf(item.quantity())));
        }
        try {
            total = total.setScale(2, RoundingMode.UNNECESSARY);
            if (total.signum() < 0 || total.precision() > 19) {
                throw new OrderFailure(INVALID_REQUEST);
            }
            return total;
        } catch (ArithmeticException unrepresentable) {
            throw new OrderFailure(INVALID_REQUEST);
        }
    }

    private Shard buyerKeyShard(String region, String key) {
        List<Shard> eligible = topology.shards().stream().filter(shard -> shard.region().equals(region)).toList();
        if (eligible.isEmpty()) {
            throw new OrderFailure(INVALID_REQUEST);
        }
        int index = Sha256.unsignedDigest("buyer\0" + region + "\0" + key)
                .mod(BigInteger.valueOf(eligible.size())).intValue();
        return eligible.get(index);
    }

    private long buyerId(Shard target) {
        for (int attempt = 0; attempt < maxCandidates; attempt++) {
            long id = nextId();
            if (router.route(id).equals(target)) {
                return id;
            }
        }
        throw new OrderFailure(ID_GENERATION_UNAVAILABLE);
    }

    private long nextId() {
        try {
            return generator.nextId();
        } catch (IdGenerationUnavailableException failure) {
            throw new OrderFailure(ID_GENERATION_UNAVAILABLE);
        }
    }

    /** The transaction lock that serializes creation, result application and orphan quarantine of one order ID. */
    public static long orderLock(long orderId) {
        return lockKey("order", "", Long.toString(orderId));
    }

    private static long lockKey(String collection, String scope, String key) {
        return Sha256.unsignedDigest(collection + "\0" + scope + "\0" + key).longValue();
    }

    static String fingerprint(long buyerId, long orderId, OrderCreation creation) {
        StringBuilder json = new StringBuilder("{\"buyerId\":\"").append(buyerId)
                .append("\",\"orderId\":\"").append(orderId).append("\",\"currency\":\"")
                .append(creation.currency()).append("\",\"items\":[");
        for (int index = 0; index < creation.items().size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            OrderItem item = creation.items().get(index);
            json.append("{\"sellerId\":\"").append(item.sellerId()).append("\",\"productId\":\"")
                    .append(item.productId()).append("\",\"quantity\":").append(item.quantity()).append('}');
        }
        return String.format("%064x", Sha256.unsignedDigest(json.append("]}").toString()));
    }

    private static void validate(long buyerId, long orderId, OrderCreation creation) {
        positive(buyerId);
        positive(orderId);
        if (creation == null || creation.currency() == null || !creation.currency().matches("[A-Z]{3}")
                || creation.items().isEmpty() || creation.items().size() > 1000) {
            throw new OrderFailure(INVALID_REQUEST);
        }
        HashSet<String> pairs = new HashSet<>();
        for (OrderItem item : creation.items()) {
            positive(item.sellerId());
            positive(item.productId());
            if (item.quantity() < 1 || !pairs.add(item.sellerId() + ":" + item.productId())) {
                throw new OrderFailure(INVALID_REQUEST);
            }
        }
    }

    private static void positive(long id) {
        if (id <= 0) {
            throw new OrderFailure(INVALID_REQUEST);
        }
    }

    private record CatalogOutcome(List<Product> products, OrderFailure.Reason failure) {
    }
}
