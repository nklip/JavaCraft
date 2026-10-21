package dev.nklip.javacraft.shardshop.order.ordering;

import dev.nklip.javacraft.shardshop.sharding.Shard;
import dev.nklip.javacraft.shardshop.sharding.ShardTopology;

import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static dev.nklip.javacraft.shardshop.order.ordering.OrderModel.*;

/** Globally ordered bounded pages across every shard of the buyer's home region. */
public final class OrderListingService {
    private final OrderListingStore store;
    private final ShardTopology topology;

    public OrderListingService(OrderListingStore store, ShardTopology topology) {
        this.store = store;
        this.topology = topology;
    }

    /** Normalize a literal saved-name search consistently for HTTP and direct service callers. */
    public static String normalizeProductName(String value) {
        if (value == null) {
            return null;
        }
        for (int offset = 0; offset < value.length(); ) {
            int point = value.codePointAt(offset);
            if (Character.isISOControl(point) || point >= Character.MIN_SURROGATE && point <= Character.MAX_SURROGATE) {
                throw new OrderFailure(OrderFailure.Reason.INVALID_REQUEST);
            }
            offset += Character.charCount(point);
        }
        int start = 0;
        int end = value.length();
        while (start < end && isSpace(value.codePointAt(start))) {
            start += Character.charCount(value.codePointAt(start));
        }
        while (end > start && isSpace(value.codePointBefore(end))) {
            end -= Character.charCount(value.codePointBefore(end));
        }
        String normalized = value.substring(start, end);
        if (normalized.isEmpty() || normalized.codePointCount(0, normalized.length()) > 200) {
            throw new OrderFailure(OrderFailure.Reason.INVALID_REQUEST);
        }
        return normalized;
    }

    private static boolean isSpace(int point) {
        return Character.isWhitespace(point) || Character.isSpaceChar(point);
    }

    public OrderPage orders(String region, Long productId, String productName,
                            SortDirection sort, int limit, OrderCursor after) {
        productName = normalizeProductName(productName);
        if (region == null || !Set.of("US", "EU", "ASIA").contains(region) || sort == null
                || productId != null && productName != null
                || limit < 1 || limit > 100 || productId != null && productId <= 0
                || after != null && (after.createdAt() == null || after.orderId() <= 0
                || after.createdAt().getNano() % 1000 != 0
                || after.createdAt().atOffset(ZoneOffset.UTC).getYear() < 1
                || after.createdAt().atOffset(ZoneOffset.UTC).getYear() > 9999)) {
            throw new OrderFailure(OrderFailure.Reason.INVALID_REQUEST);
        }
        List<Shard> shards = topology.shards().stream().filter(shard -> shard.region().equals(region)).toList();
        if (shards.isEmpty()) {
            throw new OrderFailure(OrderFailure.Reason.INVALID_REQUEST);
        }
        Comparator<ListedOrder> comparator = Comparator.comparing(ListedOrder::createdAt)
                .thenComparingLong(ListedOrder::orderId);
        if (sort == SortDirection.DESC) {
            comparator = comparator.reversed();
        }
        List<ListedOrder> candidates = store.orders(shards, region, productId, productName, sort, limit + 1, after)
                .stream().sorted(comparator).limit(limit + 1L).toList();
        boolean more = candidates.size() > limit;
        List<ListedOrder> items = more ? candidates.subList(0, limit) : candidates;
        OrderCursor next = more ? new OrderCursor(items.getLast().createdAt(), items.getLast().orderId()) : null;
        return new OrderPage(region, items, next);
    }
}
