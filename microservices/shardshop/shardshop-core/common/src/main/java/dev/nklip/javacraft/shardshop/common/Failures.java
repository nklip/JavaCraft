package dev.nklip.javacraft.shardshop.common;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Log text for the failures of background workers, such as the order CDC readers and the ledger consumer. */
public final class Failures {
    private Failures() {
    }

    /** A single-line cause chain for the retry log, bounded in depth and length. */
    public static String describe(Throwable failure) {
        StringBuilder text = new StringBuilder();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        int depth = 0;
        for (Throwable cause = failure; cause != null && depth < 6 && seen.add(cause); cause = cause.getCause()) {
            if (depth++ > 0) {
                text.append(" <- ");
            }
            text.append(cause.getClass().getSimpleName());
            String message = cause.getMessage();
            if (message != null && !message.isBlank()) {
                String line = message.replaceAll("\\s+", " ").strip();
                text.append(": ").append(line.length() > 300 ? line.substring(0, 300) + "..." : line);
            }
        }
        return text.toString();
    }
}
