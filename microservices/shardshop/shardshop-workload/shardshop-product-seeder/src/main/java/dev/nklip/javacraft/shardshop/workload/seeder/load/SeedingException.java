package dev.nklip.javacraft.shardshop.workload.seeder.load;

/** A failed seeding operation. Messages name the operation and status, never response payloads. */
public final class SeedingException extends Exception {
    private static final long serialVersionUID = 1L;

    public SeedingException(String message, Throwable cause) {
        super(message, cause);
    }
}
