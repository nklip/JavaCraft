package dev.nklip.javacraft.shardshop.order.profit;

import java.sql.SQLException;

/** A conflicting immutable credit must be investigated, never applied over the saved balance. */
public final class ProfitFailure extends RuntimeException {
    public enum Reason { UNAVAILABLE, CREDIT_CONFLICT, MISSING_CATALOG_REFERENCE, BALANCE_OUT_OF_RANGE }

    private final Reason reason;

    public ProfitFailure(Reason reason) {
        this(reason, null);
    }

    /** The cause is only for the worker log; it never changes the classification. */
    public ProfitFailure(Reason reason, Throwable cause) {
        super(reason.name(), cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    /** Only definitive catalog data/integrity errors are permanent. Connection, schema and grant failures retry. */
    public static ProfitFailure from(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                for (SQLException next = sql; next != null; next = next.getNextException()) {
                    if ("23503".equals(next.getSQLState())) {
                        return new ProfitFailure(Reason.MISSING_CATALOG_REFERENCE, failure);
                    }
                    if ("22003".equals(next.getSQLState())) {
                        return new ProfitFailure(Reason.BALANCE_OUT_OF_RANGE, failure);
                    }
                }
            }
        }
        return new ProfitFailure(Reason.UNAVAILABLE, failure);
    }
}
