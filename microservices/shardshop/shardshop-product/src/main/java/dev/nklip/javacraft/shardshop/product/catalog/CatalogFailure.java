package dev.nklip.javacraft.shardshop.product.catalog;

/** Stable failure categories without database, credential or request details. */
public final class CatalogFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public enum Reason {
        INVALID_REQUEST, SELLER_NOT_FOUND, PRODUCT_NOT_FOUND, MISSING_PARENT,
        SELLER_CONFLICT, PRODUCT_CONFLICT, UNAVAILABLE, REPLICA_UNAVAILABLE, ID_UNAVAILABLE
    }

    private final Reason reason;

    public CatalogFailure(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
