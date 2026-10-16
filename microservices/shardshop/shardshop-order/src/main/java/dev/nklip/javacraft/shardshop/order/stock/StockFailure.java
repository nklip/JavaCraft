package dev.nklip.javacraft.shardshop.order.stock;

/** Safe stock failure codes; database and connection details never escape. */
public final class StockFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public enum Reason {
        OUT_OF_STOCK, UNAVAILABLE, RESERVATION_CONFLICT
    }

    private final Reason reason;

    public StockFailure(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
