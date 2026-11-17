package dev.nklip.javacraft.shardshop.product.catalog;

import dev.nklip.javacraft.shardshop.idgen.IdGenerationUnavailableException;
import dev.nklip.javacraft.shardshop.idgen.IdGenerator;
import dev.nklip.javacraft.shardshop.sharding.Shard;
import dev.nklip.javacraft.shardshop.sharding.ShardRouter;
import dev.nklip.javacraft.shardshop.sharding.ShardTopology;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.LongStream;

import static dev.nklip.javacraft.shardshop.product.catalog.CatalogFailure.Reason.*;
import static dev.nklip.javacraft.shardshop.product.catalog.CatalogModel.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CatalogServiceTest {
    private final CatalogStore store = mock(CatalogStore.class);
    private final CatalogStore.Session session = mock(CatalogStore.Session.class);
    private final IdGenerator generator = mock(IdGenerator.class);
    private final ShardTopology topology = ShardTopology.fromNamesAndRegions("a,b,c,d", "US,EU,ASIA,US");
    private final ShardRouter router = new ShardRouter(topology);
    private final CatalogService service = new CatalogService(store, generator, router, topology, new CatalogService.Settings(false, 1024));
    private final ProductCreation payload = new ProductCreation("Mug", "Insulated mug", "19.95", "12.50", "USD", 10);
    private final Seller seller = new Seller(1, "Company", "US", Map.of("USD", "12.30"), "US");
    private final Product product = new Product(1, 2, payload, 7);

    @Test
    void createsSellerOnTheSameKeyHomeUsedAfterRestartWithZeroBalances() {
        writes();
        reads();
        when(generator.nextId()).thenAnswer(invocation -> counter++);
        Creation<Seller> result = service.createSeller("key", new SellerCreation("Company", "US", "US"));
        assertTrue(result.created());
        assertEquals("US", router.route(result.entity().sellerId()).region());
        assertEquals(Map.of("USD", "0.00"), result.entity().profitsEarned());
        verify(session).insertSeller(result.entity().sellerId(), "key", new SellerCreation("Company", "US", "US"));
        when(session.sellerByKey("US", "key")).thenReturn(Optional.of(result.entity()));
        CatalogService restarted = new CatalogService(store, generator, router, topology, new CatalogService.Settings(false, 1024));
        assertEquals(result.entity(), restarted.sellerByKey("US", "key"));
        verify(store).read(eq(router.route(result.entity().sellerId())), eq(false), any());
        verify(store).write(eq(router.route(result.entity().sellerId())), any());
        verify(session).lock(anyLong());
    }

    private long counter = 1;

    @Test
    void sellerRetryReturnsCurrentProfitWithoutAllocatingOrWriting() {
        writes();
        when(session.sellerByKey("US", "key")).thenReturn(Optional.of(seller));
        assertEquals(new Creation<>(seller, false), service.createSeller("key", new SellerCreation("Company", "US", "US")));
        verifyNoInteractions(generator);
        verify(session, never()).insertSeller(anyLong(), anyString(), any());
        assertThrows(UnsupportedOperationException.class, () -> seller.profitsEarned().put("USD", "2.00"));
    }

    @Test
    void sellerKeyCannotChangeCompanyOrRegion() {
        writes();
        when(session.sellerByKey("US", "key")).thenReturn(Optional.of(seller));
        fails(SELLER_CONFLICT, () -> service.createSeller("key", new SellerCreation("Other", "US")));
        when(session.sellerByKey("EU", "key")).thenReturn(Optional.of(seller));
        fails(SELLER_CONFLICT, () -> service.createSeller("key", new SellerCreation("Company", "EU")));
        verifyNoInteractions(generator);
    }

    @Test
    void unknownRegionFailsBeforeIoOrGeneration() {
        fails(INVALID_REQUEST, () -> service.createSeller("key", new SellerCreation("Company", "AU")));
        fails(INVALID_REQUEST, () -> service.sellerByKey("AU", "key"));
        var usOnly = ShardTopology.fromNamesAndRegions("a", "US");
        var limited = new CatalogService(store, generator, new ShardRouter(usOnly), usOnly, new CatalogService.Settings(false, 1));
        fails(INVALID_REQUEST, () -> limited.createSeller("key", new SellerCreation("Company", "ASIA")));
        fails(INVALID_REQUEST, () -> limited.sellerByKey("ASIA", "key"));
        verifyNoInteractions(store, generator);
    }

    @Test
    void boundedCandidateExhaustionDoesNotCreatePartialSeller() {
        writes();
        long wrongRegionId = LongStream.range(1, 100).filter(id -> router.route(id).region().equals("EU"))
                .findFirst().orElseThrow();
        when(generator.nextId()).thenReturn(wrongRegionId);
        CatalogService bounded = new CatalogService(store, generator, router, topology, new CatalogService.Settings(false, 3));
        fails(ID_UNAVAILABLE, () -> bounded.createSeller("key", new SellerCreation("Company", "US", "US")));
        verify(generator, times(3)).nextId();
        verify(session, never()).insertSeller(anyLong(), anyString(), any());
    }

    @Test
    void generationFailureDoesNotWriteProduct() {
        writes();
        when(session.seller(1)).thenReturn(Optional.of(seller));
        when(generator.nextId()).thenThrow(mock(IdGenerationUnavailableException.class));
        fails(ID_UNAVAILABLE, () -> service.createProduct(1, "key", payload));
        verify(session, never()).insertProduct(anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void productCreationUsesParentRouteAndInitialStock() {
        writes();
        when(session.seller(1)).thenReturn(Optional.of(seller));
        when(generator.nextId()).thenReturn(9L);
        assertEquals(new Creation<>(new Product(1, 9, payload, 10, "US", "US"), true), service.createProduct(1, "key", payload));
        verify(store).write(eq(router.route(1)), any());
        var order = inOrder(session, generator);
        order.verify(session).lock(anyLong());
        order.verify(session).productByKey(1, "key");
        order.verify(session).seller(1);
        order.verify(generator).nextId();
        order.verify(session).insertProduct(9, 1, "key", payload);
    }

    @Test
    void productRetryPreservesStockAndDoesNotAllocate() {
        writes();
        when(session.productByKey(1, "key")).thenReturn(Optional.of(product));
        assertEquals(new Creation<>(product, false), service.createProduct(1, "key", payload));
        verifyNoInteractions(generator);
        verify(session, never()).insertProduct(anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void productConflictAndMissingParentDoNotGenerateIds() {
        writes();
        when(session.productByKey(1, "key")).thenReturn(Optional.of(product));
        var changed = new ProductCreation("Mug", "Other description", "19.95", "12.50", "USD", 10);
        fails(PRODUCT_CONFLICT, () -> service.createProduct(1, "key", changed));
        fails(MISSING_PARENT, () -> service.createProduct(1, "new", payload));
        verifyNoInteractions(generator);
    }

    @Test
    void primaryAndReplicaReadsReturnStoredValuesAndNeverGenerate() {
        reads();
        when(session.seller(1)).thenReturn(Optional.of(seller));
        when(session.product(1, 2)).thenReturn(Optional.of(product));
        when(session.productByKey(1, "key")).thenReturn(Optional.of(product));
        assertEquals(seller, service.seller(1));
        assertEquals(product, service.product(1, 2));
        assertEquals(product, service.productByKey(1, "key"));
        CatalogService replica = new CatalogService(store, generator, router, topology, new CatalogService.Settings(true, 1));
        assertEquals(product, replica.product(1, 2));
        verify(store).read(eq(router.route(1)), eq(true), any());
        verifyNoInteractions(generator);
    }

    @Test
    void missingReadResultsBecomeResourceSpecificFailures() {
        reads();
        fails(SELLER_NOT_FOUND, () -> service.seller(1));
        fails(SELLER_NOT_FOUND, () -> service.sellerByKey("US", "key"));
        fails(PRODUCT_NOT_FOUND, () -> service.product(1, 2));
        fails(PRODUCT_NOT_FOUND, () -> service.productByKey(1, "key"));
        verifyNoInteractions(generator);
    }

    @Test
    void invalidCandidateBudgetsFailAtConfiguration() {
        for (int invalid : new int[]{0, 65537}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new CatalogService.Settings(false, invalid));
        }
    }

    @Test
    void regionalPageMergesEveryEligibleShardInNumericIdOrderAndUsesLookahead() {
        List<Shard> usShards = List.of(topology.shards().get(0), topology.shards().get(3));
        var first = listed(9007199254740993L);
        var second = listed(9007199254740994L);
        var third = listed(Long.MAX_VALUE);
        when(store.products(usShards, false, "US", 3, 42)).thenReturn(List.of(third, first, second));

        assertEquals(new ProductPage("US", List.of(first, second), second.productId()), service.products("US", 2, 42));
        verifyNoInteractions(generator, session);
        assertThrows(UnsupportedOperationException.class, () -> first.profitsEarned().put("EUR", "1.00"));
    }

    @Test
    void finalAndEmptyPagesHaveNoCursorAndReplicaSelectionAppliesToTheWholeRegion() {
        List<Shard> usShards = List.of(topology.shards().get(0), topology.shards().get(3));
        CatalogService replica = new CatalogService(store, generator, router, topology, new CatalogService.Settings(true, 1));
        when(store.products(usShards, true, "US", 3, 0)).thenReturn(List.of(listed(2), listed(3)));
        assertEquals(new ProductPage("US", List.of(listed(2), listed(3)), null), replica.products("US", 2, 0));
        when(store.products(usShards, true, "US", 3, 3)).thenReturn(List.of());
        assertEquals(new ProductPage("US", List.of(), null), replica.products("US", 2, 3));
        when(store.products(usShards, true, "US", 3, 4)).thenThrow(new CatalogFailure(REPLICA_UNAVAILABLE));
        fails(REPLICA_UNAVAILABLE, () -> replica.products("US", 2, 4));
        verifyNoInteractions(generator);
    }

    @Test
    void listingRejectsInvalidRegionAndPageBoundsBeforeIo() {
        fails(INVALID_REQUEST, () -> service.products("AU", 25, 0));
        fails(INVALID_REQUEST, () -> service.products("US", 0, 0));
        fails(INVALID_REQUEST, () -> service.products("US", 101, 0));
        fails(INVALID_REQUEST, () -> service.products("US", 25, -1));
        verifyNoInteractions(store, generator);
    }

    @Test
    void rejectsNewSellerWithoutMappedCountryAndCountryChangesButKeepsOldRetry() {
        writes();
        fails(INVALID_REQUEST, () -> service.createSeller("new", new SellerCreation("Arbitrary", "US")));
        fails(INVALID_REQUEST, () -> service.createSeller("new", new SellerCreation("Arbitrary", "US", "JP")));
        when(session.sellerByKey("US", "key")).thenReturn(Optional.of(seller));
        assertEquals(new Creation<>(seller, false), service.createSeller("key", new SellerCreation("Company", "US")));
        fails(SELLER_CONFLICT, () -> service.createSeller("key", new SellerCreation("Company", "US", "JP")));
        verifyNoInteractions(generator);
        verify(session, never()).insertSeller(anyLong(), anyString(), any());
    }

    @Test
    void enforcesCountryCurrencyAndIntegralPrecisionWithoutWritingButPreservesLegacyRetry() {
        writes();
        Seller japan = new Seller(1, "Arbitrary business", "ASIA", Map.of("JPY", "0.00"), "JP");
        when(session.seller(1)).thenReturn(Optional.of(japan));
        ProductCreation invalidCurrency = new ProductCreation("Mug", "Mug", "19.00", "12.00", "CNY", 5);
        ProductCreation fractionalYen = new ProductCreation("Mug", "Mug", "19.01", "12.00", "JPY", 5);
        ProductCreation fractionalCost = new ProductCreation("Mug", "Mug", "19.00", "12.01", "JPY", 5);
        for (ProductCreation invalid : List.of(invalidCurrency, fractionalYen, fractionalCost)) {
            fails(INVALID_REQUEST, () -> service.createProduct(1, "new", invalid));
        }
        when(session.seller(1)).thenReturn(Optional.of(new Seller(1, "Legacy", "ASIA", Map.of())));
        fails(INVALID_REQUEST, () -> service.createProduct(1, "new", payload));
        when(session.productByKey(1, "old")).thenReturn(Optional.of(product));
        assertEquals(new Creation<>(product, false), service.createProduct(1, "old", payload));
        verifyNoInteractions(generator);
        verify(session, never()).insertProduct(anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void createsIntegralYenProductAndKeepsLegacyRowsVisibleWithReasons() {
        writes();
        when(session.seller(1)).thenReturn(Optional.of(new Seller(1, "Japan", "ASIA", Map.of(), "JP")));
        when(generator.nextId()).thenReturn(9L);
        ProductCreation yen = new ProductCreation("Mug", "Mug", "19.00", "12.00", "JPY", 5);
        Product created = service.createProduct(1, "new", yen).entity();
        assertTrue(created.newOrderEligible());
        assertEquals("JP", created.countryCode());
        assertNull(created.legacyReason());
        assertEquals("UNRESOLVED_SELLER_COUNTRY", product.legacyReason());
        assertEquals("DISALLOWED_SELLER_CURRENCY", new Product(1, 2, payload, 7, "ASIA", "JP").legacyReason());
        assertEquals("INVALID_CURRENCY_PRECISION", new Product(1, 2,
                new ProductCreation("Mug", "Mug", "19.01", "12.00", "JPY", 5), 5, "ASIA", "JP").legacyReason());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 1, 25, 26, 50, 51})
    void boundedForwardReverseAndLastWindowsCoverRegionalBoundarySizes(int count) {
        List<Shard> usShards = List.of(topology.shards().get(0), topology.shards().get(3));
        when(store.productWindow(eq(usShards), eq(false), eq("US"), eq(26), anyLong(), any()))
                .thenAnswer(invocation -> {
                    long boundary = invocation.getArgument(4);
                    ListingDirection direction = invocation.getArgument(5);
                    // Return each shard's independently bounded candidates, deliberately not globally sorted.
                    List<ListedProduct> rows = new java.util.ArrayList<>();
                    for (int shard = 0; shard < 2; shard++) {
                        final int parity = shard;
                        var ids = LongStream.rangeClosed(1, count).filter(id -> id % 2 == parity)
                                .filter(id -> direction == ListingDirection.LAST
                                        || direction == ListingDirection.BEFORE && id < boundary
                                        || direction == ListingDirection.AFTER && id > boundary).boxed();
                        ids = direction == ListingDirection.AFTER ? ids.sorted() : ids.sorted(Comparator.reverseOrder());
                        rows.addAll(ids.limit(26).map(this::listed).toList());
                    }
                    boolean opposite = boundary > 0 && (direction == ListingDirection.AFTER ? count > 0
                            : direction == ListingDirection.BEFORE && boundary <= count);
                    return new ProductSelection(rows, opposite);
                });
        List<Long> expected = LongStream.rangeClosed(1, count).boxed().toList();
        ProductPage first = service.products("US", 25, 0, ListingDirection.AFTER);
        assertEquals(expected.stream().limit(25).toList(), ids(first));
        assertNull(first.previousBefore());
        List<Long> forwards = new java.util.ArrayList<>(ids(first));
        ProductPage page = first;
        while (page.nextAfter() != null) {
            long boundary = page.nextAfter();
            page = service.products("US", 25, boundary, ListingDirection.AFTER);
            assertTrue(page.items().size() <= 25);
            forwards.addAll(ids(page));
        }
        assertEquals(expected, forwards);

        ProductPage last = service.products("US", 25, 0, ListingDirection.LAST);
        assertEquals(expected.stream().skip(Math.max(0, count - 25)).toList(), ids(last));
        assertNull(last.nextAfter());
        List<Long> backwards = new java.util.ArrayList<>(ids(last));
        page = last;
        while (page.previousBefore() != null) {
            page = service.products("US", 25, page.previousBefore(), ListingDirection.BEFORE);
            assertNotNull(page.nextAfter());
            backwards.addAll(ids(page));
        }
        assertEquals(expected, backwards.stream().sorted().toList());
        if (count > 25) {
            ProductPage second = service.products("US", 25, first.nextAfter(), ListingDirection.AFTER);
            ProductPage previous = service.products("US", 25, second.previousBefore(), ListingDirection.BEFORE);
            assertEquals(ids(first), ids(previous));
        }
        verifyNoInteractions(generator);
    }

    @Test
    void oppositeEdgeComesFromExistenceRatherThanAssumingTheCursorNamesAProduct() {
        var shards = List.of(topology.shards().get(0), topology.shards().get(3));
        when(store.productWindow(shards, false, "US", 26, 2, ListingDirection.AFTER))
                .thenReturn(new ProductSelection(List.of(listed(100)), false));
        assertNull(service.products("US", 25, 2, ListingDirection.AFTER).previousBefore());
        when(store.productWindow(shards, false, "US", 26, 200, ListingDirection.BEFORE))
                .thenReturn(new ProductSelection(List.of(listed(100)), false));
        assertNull(service.products("US", 25, 200, ListingDirection.BEFORE).nextAfter());
        fails(INVALID_REQUEST, () -> service.products("US", 25, 0, ListingDirection.BEFORE));
        fails(INVALID_REQUEST, () -> service.products("US", 25, 1, ListingDirection.LAST));
    }

    private List<Long> ids(ProductPage page) {
        return page.items().stream().map(ListedProduct::productId).toList();
    }

    private ListedProduct listed(long id) {
        return new ListedProduct(1, id, "Company", "Mug", "US", 10, 7, "19.95", "EUR", Map.of("EUR", "-1.50"));
    }

    private void writes() {
        when(store.write(any(), any())).thenAnswer(invocation -> {
            Function<CatalogStore.Session, ?> work = invocation.getArgument(1);
            return work.apply(session);
        });
    }

    private void reads() {
        when(store.read(any(), anyBoolean(), any())).thenAnswer(invocation -> {
            Function<CatalogStore.Session, ?> work = invocation.getArgument(2);
            return work.apply(session);
        });
    }

    private static void fails(CatalogFailure.Reason reason, org.junit.jupiter.api.function.Executable work) {
        assertEquals(reason, assertThrows(CatalogFailure.class, work).reason());
    }
}
