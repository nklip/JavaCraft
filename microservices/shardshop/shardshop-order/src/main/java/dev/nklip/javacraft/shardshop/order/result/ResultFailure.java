package dev.nklip.javacraft.shardshop.order.result;

/** Infrastructure or stored-state failure; the broker retains the result for bounded retries. */
public final class ResultFailure extends RuntimeException {
    public ResultFailure(Throwable cause) {
        super("Order result transaction unavailable", cause);
    }
}
