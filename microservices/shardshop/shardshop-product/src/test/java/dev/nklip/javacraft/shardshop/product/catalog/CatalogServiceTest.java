package dev.nklip.javacraft.shardshop.product.catalog;

import dev.nklip.javacraft.shardshop.idgen.IdGenerationUnavailableException;
import dev.nklip.javacraft.shardshop.idgen.IdGenerator;
import dev.nklip.javacraft.shardshop.sharding.Shard;
import dev.nklip.javacraft.shardshop.sharding.ShardRouter;
import dev.nklip.javacraft.shardshop.sharding.ShardTopology;
import org.junit.jupiter.api.Test;

import java.util.Map;
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
    private final CatalogService service = new CatalogService(store, generator, router, topology, false, 1024);
    private final ProductCreation payload = new ProductCreation("Mug", "Insulated mug", "19.95", "12.50", "EUR", 10);
    private final Seller seller = new Seller(1, "Company", "US", Map.of("USD", "0.00", "EUR", "12.30"));
    private final Product product = new Product(1, 2, payload, 7);

    @Test
    void createsSellerOnTheSameKeyHomeUsedAfterRestartWithZeroBalances() {
        writes();
        reads();
        when(generator.nextId()).thenAnswer(invocation -> counter++);
        Creation<Seller> result = service.createSeller("key", new SellerCreation("Company", "US"));
        assertTrue(result.created());
        assertEquals("US", router.route(result.entity().sellerId()).region());
        assertEquals(Map.of("USD", "0.00", "EUR", "0.00"), result.entity().profitsEarned());
        verify(session).insertSeller(result.entity().sellerId(), "key", new SellerCreation("Company", "US"));
        when(session.sellerByKey("US", "key")).thenReturn(Optional.of(result.entity()));
        CatalogService restarted = new CatalogService(store, generator, router, topology, false, 1024);
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
        assertEquals(new Creation<>(seller, false), service.createSeller("key", new SellerCreation("Company", "US")));
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
        var limited = new CatalogService(store, generator, new ShardRouter(usOnly), usOnly, false, 1);
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
        CatalogService bounded = new CatalogService(store, generator, router, topology, false, 3);
        fails(ID_UNAVAILABLE, () -> bounded.createSeller("key", new SellerCreation("Company", "US")));
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
        assertEquals(new Creation<>(new Product(1, 9, payload, 10), true), service.createProduct(1, "key", payload));
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
        var changed = new ProductCreation("Mug", "Other description", "19.95", "12.50", "EUR", 10);
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
        CatalogService replica = new CatalogService(store, generator, router, topology, true, 1);
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
                    () -> new CatalogService(store, generator, router, topology, false, invalid));
        }
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
