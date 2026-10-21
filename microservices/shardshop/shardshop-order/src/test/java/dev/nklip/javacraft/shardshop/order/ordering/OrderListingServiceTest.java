package dev.nklip.javacraft.shardshop.order.ordering;

import dev.nklip.javacraft.shardshop.sharding.Shard;
import dev.nklip.javacraft.shardshop.sharding.ShardTopology;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static dev.nklip.javacraft.shardshop.order.ordering.OrderModel.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderListingServiceTest {
    private static final Instant TIME = Instant.parse("2026-10-21T10:20:30.123456Z");
    private static final List<Shard> US_SHARDS = List.of(new Shard("a", "US"), new Shard("d", "US"));
    private final OrderListingStore store = mock();
    private final OrderListingService service = new OrderListingService(store,
            ShardTopology.fromNamesAndRegions("a,b,c,d", "US,EU,ASIA,US"));

    @ParameterizedTest
    @CsvSource({"ASC,false", "ASC,true", "DESC,false", "DESC,true"})
    void mergesEveryBuyerRegionShardBeforePagingWithNumericTiesAndMicrosecondDates(SortDirection direction,
                                                                                  boolean filtered) {
        Long product = filtered ? Long.MAX_VALUE : null;
        when(store.orders(US_SHARDS, "US", product, null, direction, 4, null)).thenReturn(List.of(
                order(10, TIME), order(3, TIME.plusNanos(1000)), order(100, TIME), order(2, TIME)));

        OrderPage page = service.orders("US", product, null, direction, 3, null);

        assertEquals("US", page.region());
        assertEquals(direction == SortDirection.ASC ? List.of(2L, 10L, 100L) : List.of(3L, 100L, 10L),
                page.items().stream().map(ListedOrder::orderId).toList());
        assertEquals(new OrderCursor(TIME, direction == SortDirection.ASC ? 100 : 10), page.nextAfter());
        verify(store).orders(US_SHARDS, "US", product, null, direction, 4, null);
        verifyNoMoreInteractions(store);
    }

    @Test
    void continuationPreservesFullCursorAndReturnsFinalOrEmptyPageWithoutContinuation() {
        OrderCursor cursor = new OrderCursor(TIME, 100);
        ListedOrder last = order(3, TIME.plusNanos(1000));
        when(store.orders(US_SHARDS, "US", null, null, SortDirection.ASC, 4, cursor)).thenReturn(List.of(last));
        assertEquals(new OrderPage("US", List.of(last), null), service.orders("US", null, null, SortDirection.ASC, 3, cursor));
        when(store.orders(US_SHARDS, "US", null, null, SortDirection.ASC, 4, cursor)).thenReturn(List.of());
        assertEquals(new OrderPage("US", List.of(), null), service.orders("US", null, null, SortDirection.ASC, 3, cursor));
    }

    @Test
    void requiredShardFailureFailsWholePage() {
        OrderFailure failure = new OrderFailure(OrderFailure.Reason.ORDER_STORE_UNAVAILABLE);
        when(store.orders(US_SHARDS, "US", null, null, SortDirection.DESC, 26, null)).thenThrow(failure);
        assertSame(failure, assertThrows(OrderFailure.class,
                () -> service.orders("US", null, null, SortDirection.DESC, 25, null)));
    }

    @Test
    void trimsUnicodeEdgesAndPreservesLiteralSearchAcrossRegionalPaging() {
        OrderCursor cursor = new OrderCursor(TIME, 100);
        String name = "🚀".repeat(198) + "%_";
        ListedOrder order = order(3, TIME);
        when(store.orders(US_SHARDS, "US", null, name, SortDirection.ASC, 26, cursor))
                .thenReturn(List.of(order));

        assertEquals(new OrderPage("US", List.of(order), null),
                service.orders("US", null, "\u00a0 " + name + "\u2003", SortDirection.ASC, 25, cursor));
        verify(store).orders(US_SHARDS, "US", null, name, SortDirection.ASC, 26, cursor);
    }

    @Test
    void invalidOrCompetingNameFiltersNeverReadStorage() {
        for (String name : List.of("", " \u00a0\u2003", "x".repeat(201), "🚀".repeat(201), "a\u0000b", "a\nb",
                "a\u0085b", "a\u007fb", "\ud800", "\udfff", "\udc00\ud800")) {
            invalid(() -> service.orders("US", null, name, SortDirection.DESC, 25, null));
        }
        invalid(() -> service.orders("US", 21L, "product", SortDirection.DESC, 25, null));
        verifyNoInteractions(store);
    }

    @Test
    void invalidDomainInputsNeverReadStorage() {
        invalid(() -> service.orders("AU", null, null, SortDirection.DESC, 25, null));
        invalid(() -> service.orders(null, null, null, SortDirection.DESC, 25, null));
        invalid(() -> service.orders("US", 0L, null, SortDirection.DESC, 25, null));
        invalid(() -> service.orders("US", null, null, null, 25, null));
        invalid(() -> service.orders("US", null, null, SortDirection.DESC, 0, null));
        invalid(() -> service.orders("US", null, null, SortDirection.DESC, 101, null));
        invalid(() -> service.orders("US", null, null, SortDirection.DESC, 25, new OrderCursor(TIME, 0)));
        invalid(() -> service.orders("US", null, null, SortDirection.DESC, 25, new OrderCursor(TIME.plusNanos(1), 1)));
        invalid(() -> service.orders("US", null, null, SortDirection.DESC, 25, new OrderCursor(null, 1)));
        verifyNoInteractions(store);
    }

    private void invalid(Runnable work) {
        assertEquals(OrderFailure.Reason.INVALID_REQUEST, assertThrows(OrderFailure.class, work::run).reason());
    }

    private ListedOrder order(long id, Instant createdAt) {
        return new ListedOrder(id, 41, "Full Buyer Name", createdAt, "CONFIRMED", null, new BigDecimal("39.90"), "USD",
                List.of(new ListedItem(Long.MAX_VALUE, "Saved product", 2)));
    }
}
