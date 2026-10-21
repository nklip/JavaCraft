package dev.nklip.javacraft.shardshop.order.ordering;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Immutable business values. Wire parsing and database representations belong to adapters. */
public final class OrderModel {
    private OrderModel() {
    }

    public record Address(String line1, String city, String postalCode, String countryCode) {
    }

    public record BuyerCreation(String firstName, String surname, String email, String phone,
                                Address address, String region) {
    }

    public record Buyer(long buyerId, BuyerCreation creation) {
    }

    public record Creation<T>(T entity, boolean created) {
    }

    public record AllocationRequest(String runName, long requestOrdinal) {
    }

    public record Allocation(long buyerId, long orderId, String runName, long requestOrdinal) {
    }

    public record OrderItem(long sellerId, long productId, int quantity) {
    }

    public record OrderCreation(String currency, List<OrderItem> items) {
        public OrderCreation {
            items = List.copyOf(items);
        }
    }

    public record Product(long sellerId, long productId, String name, BigDecimal price,
                          BigDecimal unitCost, String currency, String sellerRegion, String sellerCountryCode) {
    }

    public record ItemSnapshot(int position, long sellerId, long productId, int quantity,
                               String productName, BigDecimal unitPrice, BigDecimal unitCost, String currency) {
    }

    public enum SortDirection { ASC, DESC }

    public record OrderCursor(Instant createdAt, long orderId) {
    }

    public record ListedItem(long productId, String productName, int quantity) {
    }

    public record ListedOrder(long orderId, long buyerId, String buyerName, Instant createdAt, String status,
                              String cancellationReason, BigDecimal total, String currency,
                              List<ListedItem> items) {
        public ListedOrder {
            items = List.copyOf(items);
        }
    }

    public record OrderPage(String region, List<ListedOrder> items, OrderCursor nextAfter) {
        public OrderPage {
            items = List.copyOf(items);
        }
    }

    public record Order(long buyerId, long orderId, String fingerprint, String currency,
                        BigDecimal totalAmount, String status, String cancellationReason) {
        public boolean pending() {
            return "PENDING_STOCK".equals(status) || "PENDING_LEDGER".equals(status);
        }

        public String statusUrl() {
            return "/api/v1/buyers/" + buyerId + "/orders/" + orderId;
        }
    }
}
