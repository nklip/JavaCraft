package dev.nklip.javacraft.shardshop.workload.producer.http;

/** Current state of one service-issued order. */
public record OrderStatus(String buyerId, String orderId, String status, String statusUrl,
                          String cancellationReason) {
    public boolean terminal() {
        return "CONFIRMED".equals(status) || "CANCELLED".equals(status);
    }
}
