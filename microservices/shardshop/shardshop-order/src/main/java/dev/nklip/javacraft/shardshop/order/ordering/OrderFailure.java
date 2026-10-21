package dev.nklip.javacraft.shardshop.order.ordering;

/** Stable failure codes without request, database or credential details. */
public final class OrderFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public enum Reason {
        INVALID_REQUEST, BUYER_NOT_FOUND, MISSING_BUYER, ORDER_NOT_FOUND, BUYER_IDEMPOTENCY_CONFLICT,
        ORDER_ID_CONFLICT, ORDER_RECONCILIATION_REQUIRED, PRODUCT_NOT_FOUND,
        MIXED_CURRENCIES, CURRENCY_MISMATCH, PRODUCT_CURRENCY_POLICY, ORDER_STORE_UNAVAILABLE,
        CATALOG_UNAVAILABLE, ID_GENERATION_UNAVAILABLE
    }

    private final Reason reason;

    public OrderFailure(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
