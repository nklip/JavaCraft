package dev.nklip.javacraft.shardshop.workload.reader.load;

import java.io.Serial;

/** A failed catalog resolution. Messages name the operation and the outcome, never response payloads. */
public final class ReaderException extends Exception {
    @Serial
    private static final long serialVersionUID = 1L;

    public ReaderException(String message) {
        super(message);
    }
}
