package dev.nklip.javacraft.shardshop.order.ordering;

import dev.nklip.javacraft.shardshop.idgen.IdGenerationUnavailableException;
import dev.nklip.javacraft.shardshop.idgen.IdGenerator;
import dev.nklip.javacraft.shardshop.sharding.Shard;
import dev.nklip.javacraft.shardshop.sharding.ShardRouter;
import dev.nklip.javacraft.shardshop.sharding.ShardTopology;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.LongStream;

import static dev.nklip.javacraft.shardshop.order.ordering.OrderFailure.Reason.*;
import static dev.nklip.javacraft.shardshop.order.ordering.OrderModel.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderServiceTest {
    private final ShardTopology topology = ShardTopology.fromNamesAndRegions("a,b,c,d", "US,EU,ASIA,US");
    private final ShardRouter router = new ShardRouter(topology);
    private final IdGenerator generator = mock(IdGenerator.class);
    private final MemoryStore store = new MemoryStore();
    private final OrderService service = service(Duration.ofSeconds(2), 2, 1024);
    private final BuyerCreation person = new BuyerCreation("First", "Last", "first.last@example.org", "+12025550123",
            new Address("1 Road", "City", "10000", "US"), "US");
    private final OrderCreation payload = new OrderCreation("USD", List.of(new OrderItem(11, 21, 3)));
    private final AtomicLong ids = new AtomicLong(100);

    @BeforeEach
    void setup() {
        when(generator.nextId()).thenAnswer(invocation -> ids.incrementAndGet());
        store.buyers.put(1L, new Buyer(1, person));
        store.allocations.put(2L, new Allocation(1, 2, "run", 0));
    }

    @Test
    void buyerCreationIsRoutedToItsPermanentKeyHomeAndRecoversAfterRestart() {
        Creation<Buyer> first = service.createBuyer("buyer.1", person);
        OrderService restarted = service(Duration.ofSeconds(2), 2, 1024);
        Creation<Buyer> retry = restarted.createBuyer("buyer.1", person);
        assertTrue(first.created());
        assertEquals(new Creation<>(first.entity(), false), retry);
        assertEquals("US", router.route(first.entity().buyerId()).region());
        assertEquals(router.route(first.entity().buyerId()), store.buyerHome);
        assertEquals(first.entity(), restarted.buyer(first.entity().buyerId()));
        clearInvocations(generator);
        BuyerCreation changed = new BuyerCreation("Changed", person.surname(), person.email(), person.phone(),
                person.address(), person.region());
        fails(BUYER_IDEMPOTENCY_CONFLICT, () -> restarted.createBuyer("buyer.1", changed));
        assertEquals(first.entity(), service.createBuyer("buyer.1", person).entity());
        verifyNoInteractions(generator);
    }

    @Test
    void buyerKeyScopesAndMultipleShardsInOneRegionRemainDistinctAndDeterministic() {
        var us = service.createBuyer("same", person).entity();
        var euPerson = new BuyerCreation(person.firstName(), person.surname(), person.email(), person.phone(),
                person.address(), "EU");
        var eu = service.createBuyer("same", euPerson).entity();
        assertNotEquals(us.buyerId(), eu.buyerId());
        assertEquals("EU", router.route(eu.buyerId()).region());
        assertEquals(us, service.createBuyer("same", person).entity());
    }

    @Test
    void wrongHomeCandidatesStopAtTheBudgetAndLeaveNoBuyerMapping() {
        long euId = LongStream.range(1, 100).filter(id -> router.route(id).region().equals("EU"))
                .findFirst().orElseThrow();
        when(generator.nextId()).thenReturn(euId);
        fails(ID_GENERATION_UNAVAILABLE, () -> service(Duration.ofSeconds(2), 2, 3).createBuyer("new", person));
        verify(generator, times(3)).nextId();
        assertTrue(store.buyerKeys.isEmpty());
    }

    @Test
    void unknownRegionAndMalformedKeyFailBeforeStoreAccess() {
        BuyerCreation unknown = new BuyerCreation("First", "Last", person.email(), person.phone(), person.address(), "AU");
        fails(INVALID_REQUEST, () -> service.createBuyer("key", unknown));
        fails(INVALID_REQUEST, () -> service.createBuyer(" key", person));
        assertEquals(0, store.writes);
        verifyNoInteractions(generator);
    }

    @Test
    void allocationRetriesReturnSameIdWithoutOrderSagaOrGeneration() {
        AllocationRequest coordinates = new AllocationRequest("new run", Long.MAX_VALUE);
        var allocated = service.allocateOrder(1, coordinates);
        assertTrue(allocated.created());
        clearInvocations(generator);
        assertEquals(new Creation<>(allocated.entity(), false), service.allocateOrder(1, coordinates));
        assertEquals(router.route(1), store.lastShard);
        assertTrue(store.orders.isEmpty());
        assertEquals(0, store.insertions);
        verifyNoInteractions(generator);
        fails(MISSING_BUYER, () -> service.allocateOrder(999, coordinates));
    }

    @Test
    void concurrentAllocationAndBuyerRetriesDoNotProduceMultipleIds() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var buyer1 = executor.submit(() -> service.createBuyer("concurrent", person));
            var buyer2 = executor.submit(() -> service.createBuyer("concurrent", person));
            assertEquals(buyer1.get(2, TimeUnit.SECONDS).entity(), buyer2.get(2, TimeUnit.SECONDS).entity());
            var request = new AllocationRequest("concurrent", 0);
            var allocation1 = executor.submit(() -> service.allocateOrder(1, request));
            var allocation2 = executor.submit(() -> service.allocateOrder(1, request));
            assertEquals(allocation1.get(2, TimeUnit.SECONDS).entity(), allocation2.get(2, TimeUnit.SECONDS).entity());
        }
    }

    @Test
    void reportsNewAndReplayedWritesWithoutAdditionalStatusReads() {
        Creation<Order> fresh = service.putOrderWithOutcome(1, 2, payload);
        assertTrue(fresh.created());
        int lookups = store.lookups.get();
        Creation<Order> replay = service.putOrderWithOutcome(1, 2, payload);
        assertFalse(replay.created());
        assertEquals(fresh.entity(), replay.entity());
        assertEquals(lookups, store.lookups.get());
        assertEquals(1, store.insertions);
    }

    @Test
    void concurrentIdenticalWritesReportExactlyOneFreshCommit() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = java.util.stream.IntStream.range(0, 24)
                    .mapToObj(ignored -> executor.submit(() -> service.putOrderWithOutcome(1, 2, payload))).toList();
            int created = 0;
            for (var future : futures) {
                if (future.get(3, TimeUnit.SECONDS).created()) {
                    created++;
                }
            }
            assertEquals(1, created);
            assertEquals(1, store.insertions);
            verify(generator, times(2)).nextId();
        }
    }

    @Test
    void acceptanceSnapshotsPricesCostsAndPreservesItemOrderAcrossRegions() {
        var creation = new OrderCreation("EUR", List.of(new OrderItem(11, 21, 3), new OrderItem(12, 22, 2)));
        store.lookup = item -> Optional.of(product(item, "EUR", item.productId() == 21 ? "19.95" : "0.01", "12.50"));
        Order accepted = service.putOrder(1, 2, creation);
        assertEquals(new BigDecimal("59.87"), accepted.totalAmount());
        assertEquals("PENDING_STOCK", accepted.status());
        assertTrue(accepted.pending());
        assertEquals("/api/v1/buyers/1/orders/2", accepted.statusUrl());
        assertEquals(List.of(
                new ItemSnapshot(0, 11, 21, 3, "Product 21", new BigDecimal("19.95"), new BigDecimal("12.50"), "EUR"),
                new ItemSnapshot(1, 12, 22, 2, "Product 22", new BigDecimal("0.01"), new BigDecimal("12.50"), "EUR")),
                store.snapshots);
        assertEquals(1, store.insertions);
        assertNotEquals(store.sagaId, store.commandId);
        assertEquals(router.route(1), store.lastShard);
        assertEquals(2, store.writes);
    }

    @Test
    void usBuyerCanBuyJapaneseProductsAndRetryAfterCatalogBecomesIneligible() {
        var creation = new OrderCreation("JPY", List.of(new OrderItem(11, 21, 3)));
        store.lookup = item -> Optional.of(new Product(11, 21, "Japanese product", new BigDecimal("1500.00"),
                new BigDecimal("1000.00"), "JPY", "ASIA", "JP"));
        Order accepted = service.putOrder(1, 2, creation);
        assertEquals(new BigDecimal("4500.00"), accepted.totalAmount());
        assertEquals("JPY", accepted.currency());
        store.lookup = item -> Optional.of(new Product(11, 21, "Legacy product", new BigDecimal("1500.50"),
                new BigDecimal("1000.00"), "JPY", "ASIA", null));
        assertEquals(accepted, service.putOrder(1, 2, creation));
        store.allocations.put(3L, new Allocation(1, 3, "run", 1));
        fails(PRODUCT_CURRENCY_POLICY, () -> service.putOrder(1, 3, creation));
        assertEquals(1, store.insertions);
    }

    @Test
    void rejectsUnresolvedOrMismatchedCountriesAndFractionalZeroMinorUnitPricesBeforeAcceptance() {
        for (Product product : List.of(
                new Product(11, 21, "Unresolved", BigDecimal.ONE, BigDecimal.ZERO, "JPY", "ASIA", null),
                new Product(11, 21, "Wrong country", BigDecimal.ONE, BigDecimal.ZERO, "JPY", "ASIA", "CN"),
                new Product(11, 21, "Wrong region", BigDecimal.ONE, BigDecimal.ZERO, "JPY", "US", "JP"),
                new Product(11, 21, "Fractional price", new BigDecimal("1.01"), BigDecimal.ZERO, "JPY", "ASIA", "JP"),
                new Product(11, 21, "Fractional cost", BigDecimal.ONE, new BigDecimal("0.01"), "JPY", "ASIA", "JP"))) {
            store.lookup = item -> Optional.of(product);
            fails(PRODUCT_CURRENCY_POLICY, () -> service.putOrder(1, 2, new OrderCreation("JPY", payload.items())));
        }
        assertTrue(store.orders.isEmpty());
        assertEquals(0, store.insertions);
        verifyNoInteractions(generator);
    }

    @Test
    void fingerprintMatchesExactCompactContractJsonAndChangesWithItemOrder() throws Exception {
        String compact = "{\"buyerId\":\"1\",\"orderId\":\"2\",\"currency\":\"USD\",\"items\":[{\"sellerId\":\"11\",\"productId\":\"21\",\"quantity\":3}]}";
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(compact.getBytes(StandardCharsets.UTF_8)));
        assertEquals(expected, service.putOrder(1, 2, payload).fingerprint());
        var forward = new OrderCreation("USD", List.of(new OrderItem(11, 21, 3), new OrderItem(12, 22, 1)));
        var reverse = new OrderCreation("USD", List.of(forward.items().get(1), forward.items().getFirst()));
        assertNotEquals(OrderService.fingerprint(1, 2, forward), OrderService.fingerprint(1, 2, reverse));
    }

    @Test
    void existingPendingAndTerminalOrdersWorkWithCatalogAndGeneratorUnavailable() {
        Order pending = service.putOrder(1, 2, payload);
        int lookups = store.lookups.get();
        store.lookup = item -> { throw new OrderFailure(CATALOG_UNAVAILABLE); };
        clearInvocations(generator);
        assertEquals(pending, service.putOrder(1, 2, payload));
        Order terminal = new Order(1, 2, pending.fingerprint(), "USD", pending.totalAmount(), "CANCELLED", "OUT_OF_STOCK");
        store.orders.put(2L, terminal);
        assertEquals(terminal, service.putOrder(1, 2, payload));
        assertFalse(terminal.pending());
        assertEquals(terminal, service.order(1, 2));
        assertEquals(lookups, store.lookups.get());
        verifyNoInteractions(generator);
        fails(ORDER_ID_CONFLICT, () -> service.putOrder(1, 2,
                new OrderCreation("EUR", payload.items())));
        assertEquals(1, store.insertions);
    }

    @Test
    void quarantineBeatsAllocationAndConflictAndUnissuedOrWrongBuyerIdsFailBeforeCatalog() {
        store.quarantine = true;
        fails(ORDER_RECONCILIATION_REQUIRED, () -> service.putOrder(999, 999, payload));
        store.quarantine = false;
        fails(INVALID_REQUEST, () -> service.putOrder(999, 2, payload));
        fails(INVALID_REQUEST, () -> service.putOrder(1, 3, payload));
        assertEquals(0, store.lookups.get());
        assertTrue(store.orders.isEmpty());
    }

    @ParameterizedTest
    @CsvSource({
            "true, false, ORDER_RECONCILIATION_REQUIRED",
            "true, true, ORDER_RECONCILIATION_REQUIRED",
            "false, false, INVALID_REQUEST",
            "false, true, ORDER_ID_CONFLICT"
    })
    void acceptanceChecksPreserveErrorPrecedenceWhenSavedFingerprintAlsoConflicts(
            boolean quarantined, boolean allocated, OrderFailure.Reason expected) {
        store.quarantine = quarantined;
        if (!allocated) {
            store.allocations.remove(2L);
        }
        Order saved = new Order(1, 2, "different fingerprint", "USD", BigDecimal.ONE, "PENDING_STOCK", null);
        store.orders.put(2L, saved);

        fails(expected, () -> service.putOrder(1, 2, payload));

        assertEquals(saved, store.orders.get(2L));
        assertEquals(0, store.lookups.get());
        assertEquals(0, store.insertions);
        verifyNoInteractions(generator);
    }

    @Test
    void invalidLocalInputsNeverTouchTheStore() {
        fails(INVALID_REQUEST, () -> service.putOrder(0, 2, payload));
        fails(INVALID_REQUEST, () -> service.putOrder(1, 2, new OrderCreation("usd", payload.items())));
        fails(INVALID_REQUEST, () -> service.putOrder(1, 2, new OrderCreation("USD", List.of())));
        fails(INVALID_REQUEST, () -> service.putOrder(1, 2, new OrderCreation("USD", List.of(new OrderItem(11, 21, 0)))));
        fails(INVALID_REQUEST, () -> service.putOrder(1, 2,
                new OrderCreation("USD", List.of(new OrderItem(11, 21, 1), new OrderItem(11, 21, 2)))));
        assertEquals(0, store.writes);
        assertEquals(0, store.lookups.get());
    }

    @Test
    void catalogPrecedenceIsIndependentOfInputOrderAndWritesNoOrder() {
        for (boolean reverse : new boolean[]{false, true}) {
            for (OrderFailure.Reason expected : List.of(CATALOG_UNAVAILABLE, PRODUCT_NOT_FOUND, MIXED_CURRENCIES, CURRENCY_MISMATCH, PRODUCT_CURRENCY_POLICY)) {
                List<OrderItem> items = List.of(new OrderItem(11, 21, 1), new OrderItem(12, 22, 1));
                if (reverse) {
                    items = items.reversed();
                }
                store.lookup = item -> {
                    if (item.productId() == 21) {
                        if (expected == CATALOG_UNAVAILABLE) {
                            throw new OrderFailure(CATALOG_UNAVAILABLE);
                        }
                        if (expected == PRODUCT_NOT_FOUND) {
                            return Optional.empty();
                        }
                        // Policy failure must lose to both currency errors.
                        return Optional.of(new Product(item.sellerId(), item.productId(), "Invalid policy",
                                BigDecimal.ONE, BigDecimal.ZERO,
                                expected == PRODUCT_CURRENCY_POLICY ? "USD" : "EUR", "US", null));
                    }
                    if (expected == CATALOG_UNAVAILABLE) {
                        return Optional.empty();
                    }
                    return Optional.of(product(item, expected == CURRENCY_MISMATCH ? "EUR" : "USD", "1.00", "0.50"));
                };
                var creation = new OrderCreation("USD", items);
                fails(expected, () -> service.putOrder(1, 2, creation));
                assertTrue(store.orders.isEmpty());
                assertEquals(0, store.insertions);
            }
        }
        verifyNoInteractions(generator);
    }

    @Test
    void unavailableWinsWhetherMissingLookupCompletesBeforeOrAfterIt() {
        var creation = new OrderCreation("USD", List.of(new OrderItem(11, 21, 1), new OrderItem(12, 22, 1)));
        for (boolean missingFirst : new boolean[]{false, true}) {
            CountDownLatch firstFinished = new CountDownLatch(1);
            store.lookup = item -> {
                boolean missing = item.productId() == 21;
                if (missing == missingFirst) {
                    firstFinished.countDown();
                } else {
                    await(firstFinished);
                }
                if (missing) {
                    return Optional.empty();
                }
                throw new OrderFailure(CATALOG_UNAVAILABLE);
            };
            fails(CATALOG_UNAVAILABLE, () -> service.putOrder(1, 2, creation));
        }
        assertTrue(store.orders.isEmpty());
    }

    @Test
    void concurrentConflictingCreationWinsMissingCatalogResult() {
        store.lookup = item -> {
            store.orders.put(2L, new Order(1, 2, "another fingerprint", "USD", BigDecimal.ONE,
                    "PENDING_STOCK", null));
            return Optional.empty();
        };
        fails(ORDER_ID_CONFLICT, () -> service.putOrder(1, 2, payload));
        assertEquals(0, store.insertions);
        verifyNoInteractions(generator);
    }

    @Test
    void overflowingOrFractionalTotalsAreRejectedAfterCatalogAndFinalRecheck() {
        for (String price : List.of("99999999999999999.99", "0.001")) {
            store.lookup = item -> Optional.of(product(item, "USD", price, "0.00"));
            fails(price.equals("0.001") ? PRODUCT_CURRENCY_POLICY : INVALID_REQUEST,
                    () -> service.putOrder(1, 2, payload));
        }
        assertEquals(4, store.writes);
        assertTrue(store.orders.isEmpty());
        verifyNoInteractions(generator);
        store.lookup = item -> Optional.of(product(item, "USD", "99999999999999999.99", "0.00"));
        var maximum = new OrderCreation("USD", List.of(new OrderItem(11, 21, 1)));
        assertEquals(new BigDecimal("99999999999999999.99"), service.putOrder(1, 2, maximum).totalAmount());
    }

    @Test
    void failureGeneratingEitherLogicalIdLeavesNoOrderAndRetryReusesAllocation() {
        when(generator.nextId()).thenThrow(mock(IdGenerationUnavailableException.class));
        fails(ID_GENERATION_UNAVAILABLE, () -> service.putOrder(1, 2, payload));
        doReturn(101L).doThrow(mock(IdGenerationUnavailableException.class)).when(generator).nextId();
        fails(ID_GENERATION_UNAVAILABLE, () -> service.putOrder(1, 2, payload));
        assertEquals(0, store.insertions);
        assertTrue(store.orders.isEmpty());
        assertEquals(2, store.allocations.get(2L).orderId());
        doReturn(102L, 103L).when(generator).nextId();
        assertEquals("PENDING_STOCK", service.putOrder(1, 2, payload).status());
    }

    @Test
    void generationFailureDoesNotPersistBuyerOrAllocation() {
        when(generator.nextId()).thenThrow(mock(IdGenerationUnavailableException.class));
        fails(ID_GENERATION_UNAVAILABLE, () -> service.createBuyer("new", person));
        fails(ID_GENERATION_UNAVAILABLE, () -> service.allocateOrder(1, new AllocationRequest("new", 0)));
        assertTrue(store.buyerKeys.isEmpty());
        assertEquals(1, store.allocations.size());
    }

    @Test
    void initialOrderStoreFailurePreventsCatalogAndGeneration() {
        store.failWrites = true;
        fails(ORDER_STORE_UNAVAILABLE, () -> service.putOrder(1, 2, payload));
        assertEquals(0, store.lookups.get());
        assertTrue(store.orders.isEmpty());
        verifyNoInteractions(generator);
    }

    @Test
    void absentReadsDoNotTreatAllocationAsOrder() {
        fails(ORDER_NOT_FOUND, () -> service.order(1, 2));
        fails(BUYER_NOT_FOUND, () -> service.buyer(999));
        assertEquals(0, store.writes);
        assertEquals(0, store.lookups.get());
        verifyNoInteractions(generator);
    }

    @Test
    void hangingCatalogHoldsNoOrderTransactionAndConcurrentCreationWinsItsFailure() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        store.lookup = item -> {
            if (attempts.incrementAndGet() == 1) {
                entered.countDown();
                await(release);
                throw new OrderFailure(CATALOG_UNAVAILABLE);
            }
            return Optional.of(product(item, "USD", "19.95", "12.50"));
        };
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> service.putOrder(1, 2, payload));
            try {
                assertTrue(entered.await(1, TimeUnit.SECONDS));
                assertEquals(0, store.activeWrites.get(), "Catalog IO must hold no order transaction/connection");
                Order winner = service.putOrder(1, 2, payload);
                release.countDown();
                assertEquals(winner, first.get(2, TimeUnit.SECONDS));
                assertEquals(1, store.insertions);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void finalQuarantineAndOrderStoreFailureBeatCatalogFailure() {
        store.lookup = item -> {
            store.quarantine = true;
            return Optional.empty();
        };
        fails(ORDER_RECONCILIATION_REQUIRED, () -> service.putOrder(1, 2, payload));
        store.quarantine = false;
        store.lookup = item -> {
            store.failWrites = true;
            throw new OrderFailure(CATALOG_UNAVAILABLE);
        };
        fails(ORDER_STORE_UNAVAILABLE, () -> service.putOrder(1, 2, payload));
        assertTrue(store.orders.isEmpty());
    }

    @Test
    void sharedDeadlineReturnsWhileUncooperativeCatalogIsStillHungAndCancelsQueuedWork() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        store.lookup = item -> {
            int current = running.incrementAndGet();
            maximum.accumulateAndGet(current, Math::max);
            entered.countDown();
            try {
                // Deliberately ignore cancellation until the test releases the simulated driver.
                boolean interrupted = false;
                while (true) {
                    try {
                        release.await();
                        break;
                    } catch (InterruptedException cancellation) {
                        interrupted = true;
                    }
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                return Optional.empty();
            } finally {
                running.decrementAndGet();
            }
        };
        var bounded = service(Duration.ofMillis(150), 2, 1024);
        var many = new OrderCreation("USD", LongStream.range(1, 194)
                .mapToObj(id -> new OrderItem(11, id, 1)).toList());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = executor.submit(() -> assertThrows(OrderFailure.class, () -> bounded.putOrder(1, 2, many)));
            try {
                assertTrue(entered.await(1, TimeUnit.SECONDS));
                assertEquals(0, store.activeWrites.get());
                assertEquals(CATALOG_UNAVAILABLE, result.get(1, TimeUnit.SECONDS).reason());
                assertTrue(maximum.get() <= 2);
                assertEquals(2, store.batches.size(), "Only the active batch window may start before timeout");
                assertEquals(2, store.writes, "Timeout must still perform final locked recheck");
                assertTrue(store.orders.isEmpty());
                assertTrue(store.remaining.stream().allMatch(value -> value.isPositive()
                        && value.compareTo(Duration.ofMillis(150)) <= 0));
            } finally {
                release.countDown();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 64, 65, 128, 1000})
    void sameShardBasketsUseBoundedPhasesAndKeepEverySnapshotInRequestOrder(int size) {
        var items = LongStream.rangeClosed(1, size)
                .mapToObj(id -> new OrderItem(11, id, (int) id)).toList().reversed();
        store.lookup = item -> Optional.of(product(item, "USD", Long.toString(item.productId()), "0.50"));

        Order order = service.putOrder(1, 2, new OrderCreation("USD", items));

        assertEquals((size + 63) / 64, store.batches.size(), "One phase per bounded seller-shard chunk");
        assertTrue(store.batches.stream().allMatch(batch -> batch.keys().size() <= 64
                && batch.shard().equals(router.route(11))));
        assertEquals(size, store.snapshots.size());
        BigDecimal total = BigDecimal.ZERO;
        for (int index = 0; index < size; index++) {
            OrderItem item = items.get(index);
            ItemSnapshot snapshot = store.snapshots.get(index);
            assertEquals(new ItemSnapshot(index, item.sellerId(), item.productId(), item.quantity(),
                    "Product " + item.productId(), BigDecimal.valueOf(item.productId()),
                    new BigDecimal("0.50"), "USD"), snapshot);
            total = total.add(BigDecimal.valueOf(item.productId()).multiply(BigDecimal.valueOf(item.quantity())));
        }
        assertEquals(total.setScale(2), order.totalAmount());
        int phases = store.lookups.get();
        store.batchLookup = batch -> { throw new OrderFailure(CATALOG_UNAVAILABLE); };
        assertEquals(order, service.putOrder(1, 2, new OrderCreation("USD", items)));
        assertEquals(phases, store.lookups.get(), "Saved multi-item retries need no catalog");
    }

    @Test
    void sellerRoutingAndPairIdentitySurviveReversedBatchCompletionAndRowOrder() {
        long otherSeller = LongStream.range(1, 100).filter(id -> !router.route(id).equals(router.route(11)))
                .findFirst().orElseThrow();
        var items = List.of(new OrderItem(11, 501, 2), new OrderItem(otherSeller, 501, 3),
                new OrderItem(11, 502, 4));
        CountDownLatch secondFinished = new CountDownLatch(1);
        store.batchLookup = batch -> {
            assertTrue(batch.keys().stream().allMatch(key -> router.route(key.sellerId()).equals(batch.shard())));
            if (batch.shard().equals(router.route(11))) {
                await(secondFinished);
            }
            Map<ProductKey, Product> products = new LinkedHashMap<>();
            for (ProductKey key : batch.keys().reversed()) {
                products.put(key, new Product(key.sellerId(), key.productId(), key.toString(),
                        BigDecimal.valueOf(key.sellerId()), BigDecimal.ZERO, "USD", "US", "US"));
            }
            if (!batch.shard().equals(router.route(11))) {
                secondFinished.countDown();
            }
            return products;
        };

        service.putOrder(1, 2, new OrderCreation("USD", items));

        assertEquals(2, store.batches.size());
        for (int index = 0; index < items.size(); index++) {
            OrderItem item = items.get(index);
            assertEquals(new ItemSnapshot(index, item.sellerId(), item.productId(), item.quantity(),
                    new ProductKey(item.sellerId(), item.productId()).toString(),
                    BigDecimal.valueOf(item.sellerId()), BigDecimal.ZERO, "USD"), store.snapshots.get(index));
        }
    }

    @Test
    void laterChunksShareRemainingDeadlineAndAreCancelledWithoutAcceptingPartialResults() throws Exception {
        CountDownLatch cancelled = new CountDownLatch(1);
        store.batchLookup = batch -> {
            if (batch.keys().getFirst().productId() > 128) {
                try {
                    assertFalse(new CountDownLatch(1).await(1, TimeUnit.SECONDS), "Deadline must interrupt this read");
                    throw new AssertionError("Catalog read was not cancelled");
                } catch (InterruptedException expected) {
                    cancelled.countDown();
                    Thread.currentThread().interrupt();
                    throw new OrderFailure(CATALOG_UNAVAILABLE);
                }
            }
            return MemoryStore.products(batch.keys(), store.lookup);
        };
        var items = LongStream.rangeClosed(1, 193).mapToObj(id -> new OrderItem(11, id, 1)).toList();
        var bounded = service(Duration.ofMillis(150), 1, 1024);

        fails(CATALOG_UNAVAILABLE, () -> bounded.putOrder(1, 2, new OrderCreation("USD", items)));

        assertTrue(cancelled.await(1, TimeUnit.SECONDS));
        assertEquals(3, store.batches.size(), "Fourth chunk must never acquire a connection after timeout");
        for (int i = 1; i < store.batches.size(); i++) {
            assertTrue(store.batches.get(i).remaining().compareTo(store.batches.get(i - 1).remaining()) < 0,
                    "Chunks consume one deadline, not a fresh budget");
        }
        assertEquals(2, store.writes, "Final durable guards still run after catalog timeout");
        assertTrue(store.orders.isEmpty());
    }

    @Test
    void unknownCommitOutcomeCanBeRecoveredWithoutNewSagaOrCatalogLookup() {
        store.failAfterCommit = true;
        fails(ORDER_STORE_UNAVAILABLE, () -> service.putOrder(1, 2, payload));
        assertEquals(1, store.insertions);
        store.failAfterCommit = false;
        int lookups = store.lookups.get();
        clearInvocations(generator);
        assertEquals(store.orders.get(2L), service.putOrder(1, 2, payload));
        assertEquals(lookups, store.lookups.get());
        verifyNoInteractions(generator);
    }

    @Test
    void settingsKeepTheirBoundsForEveryCaller() {
        assertEquals(65536, new OrderService.Settings(65536, 64, Duration.ofMinutes(1)).maxIdCandidates());
        assertEquals(1, new OrderService.Settings(1, 1, Duration.ofNanos(1)).catalogConcurrency());
        for (int candidates : new int[]{0, 65537}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new OrderService.Settings(candidates, 4, Duration.ofSeconds(2)));
        }
        for (int concurrency : new int[]{0, 65}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new OrderService.Settings(1024, concurrency, Duration.ofSeconds(2)));
        }
        for (Duration deadline : new Duration[]{null, Duration.ZERO, Duration.ofNanos(-1),
                Duration.ofMinutes(1).plusNanos(1)}) {
            assertThrows(IllegalArgumentException.class, () -> new OrderService.Settings(1024, 4, deadline));
        }
    }

    private OrderService service(Duration deadline, int concurrency, int candidates) {
        return new OrderService(store, generator, router, topology,
                new OrderService.Settings(candidates, concurrency, deadline));
    }

    private static Product product(OrderItem item, String currency, String price, String cost) {
        return new Product(item.sellerId(), item.productId(), "Product " + item.productId(),
                new BigDecimal(price), new BigDecimal(cost), currency,
                currency.equals("USD") ? "US" : "EU", currency.equals("USD") ? "US" : "DE");
    }

    private static void fails(OrderFailure.Reason reason, org.junit.jupiter.api.function.Executable work) {
        assertEquals(reason, assertThrows(OrderFailure.class, work).reason());
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(2, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private record CatalogRead(Shard shard, List<ProductKey> keys, Duration remaining) {
    }

    /** Serial transactions model the store's lock/atomicity boundary without mocking service behavior. */
    private static final class MemoryStore implements OrderStore, OrderStore.Session {
        private final Map<Long, Buyer> buyers = new HashMap<>();
        private final Map<String, Buyer> buyerKeys = new HashMap<>();
        private final Map<Long, Allocation> allocations = new HashMap<>();
        private final Map<Long, Order> orders = new HashMap<>();
        private final AtomicInteger activeWrites = new AtomicInteger();
        private final AtomicInteger lookups = new AtomicInteger();
        private final List<Duration> remaining = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        private volatile Function<OrderItem, Optional<Product>> lookup = item -> Optional.of(OrderServiceTest.product(item, "USD", "19.95", "12.50"));
        private final List<CatalogRead> batches = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        private volatile Function<CatalogRead, Map<ProductKey, Product>> batchLookup;
        private volatile boolean quarantine;
        private volatile boolean failWrites;
        private boolean failAfterCommit;
        private int writes;
        private int insertions;
        private Shard lastShard;
        private Shard buyerHome;
        private List<ItemSnapshot> snapshots;
        private long sagaId;
        private long commandId;
        private boolean writing;
        private boolean locked;

        @Override
        public synchronized <T> T read(Shard shard, Function<Session, T> work) {
            lastShard = shard;
            return work.apply(this);
        }

        @Override
        public synchronized <T> T write(Shard shard, Function<Session, T> work) {
            if (failWrites) {
                throw new OrderFailure(ORDER_STORE_UNAVAILABLE);
            }
            writes++;
            lastShard = shard;
            activeWrites.incrementAndGet();
            writing = true;
            locked = false;
            try {
                T result = work.apply(this);
                if (failAfterCommit && insertions > 0) {
                    throw new OrderFailure(ORDER_STORE_UNAVAILABLE);
                }
                return result;
            } finally {
                writing = false;
                activeWrites.decrementAndGet();
            }
        }

        /** Serial writes hide races, thus every write must take its advisory lock before its first read. */
        private void requireLock() {
            assertTrue(!writing || locked, "A write must take its advisory lock before it reads");
        }

        @Override
        public Optional<Product> product(Shard shard, long sellerId, long productId, Duration timeout) {
            lookups.incrementAndGet();
            remaining.add(timeout);
            return lookup.apply(new OrderItem(sellerId, productId, 1));
        }

        @Override
        public Map<ProductKey, Product> products(Shard shard, List<ProductKey> keys, Duration timeout) {
            lookups.incrementAndGet();
            remaining.add(timeout);
            CatalogRead batch = new CatalogRead(shard, List.copyOf(keys), timeout);
            batches.add(batch);
            assertEquals(0, activeWrites.get(), "Catalog must not retain an order transaction");
            return batchLookup == null ? products(keys, lookup) : batchLookup.apply(batch);
        }

        private static Map<ProductKey, Product> products(List<ProductKey> keys,
                                                        Function<OrderItem, Optional<Product>> lookup) {
            Map<ProductKey, Product> found = new LinkedHashMap<>();
            // SQL row order is unspecified; deliberately return the opposite order to the request.
            for (ProductKey key : keys.reversed()) {
                lookup.apply(new OrderItem(key.sellerId(), key.productId(), 1))
                        .ifPresent(product -> found.put(key, product));
            }
            return found;
        }

        @Override
        public void lock(long key) {
            assertTrue(Thread.holdsLock(this) && writing);
            locked = true;
        }

        @Override
        public Optional<Buyer> buyerByKey(String region, String key) {
            requireLock();
            return Optional.ofNullable(buyerKeys.get(region + ":" + key));
        }

        @Override
        public Optional<Buyer> buyer(long buyerId) {
            requireLock();
            return Optional.ofNullable(buyers.get(buyerId));
        }

        @Override
        public void insertBuyer(long id, String key, BuyerCreation creation) {
            Buyer buyer = new Buyer(id, creation);
            buyers.put(id, buyer);
            buyerKeys.put(creation.region() + ":" + key, buyer);
            buyerHome = lastShard;
        }

        @Override
        public Optional<Allocation> allocation(long buyerId, String runName, long ordinal) {
            requireLock();
            return allocations.values().stream().filter(value -> value.buyerId() == buyerId
                    && value.runName().equals(runName) && value.requestOrdinal() == ordinal).findFirst();
        }

        @Override
        public AcceptanceChecks acceptanceChecks(long buyerId, long orderId) {
            requireLock();
            return new AcceptanceChecks(quarantine,
                    allocations.containsKey(orderId) && allocations.get(orderId).buyerId() == buyerId,
                    order(buyerId, orderId));
        }

        @Override
        public void insertAllocation(Allocation allocation) {
            allocations.put(allocation.orderId(), allocation);
        }

        @Override
        public Optional<Order> order(long buyerId, long orderId) {
            requireLock();
            return Optional.ofNullable(orders.get(orderId)).filter(value -> value.buyerId() == buyerId);
        }

        @Override
        public void insertOrder(Order order, List<ItemSnapshot> items, long saga, long command) {
            assertEquals("PENDING_STOCK", order.status());
            orders.put(order.orderId(), order);
            snapshots = List.copyOf(items);
            sagaId = saga;
            commandId = command;
            insertions++;
        }
    }
}
