package dev.nklip.javacraft.shardshop.workload.producer.load;

/** A failed workload phase. Messages contain operation names, never response bodies or buyer profiles. */
public final class ProducerException extends Exception {
    public ProducerException(String message, Throwable cause) {
        super(message, cause);
    }
}
