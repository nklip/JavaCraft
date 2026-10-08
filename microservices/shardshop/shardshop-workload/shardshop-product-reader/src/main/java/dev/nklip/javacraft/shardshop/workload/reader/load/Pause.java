package dev.nklip.javacraft.shardshop.workload.reader.load;

/** Waits for a number of nanoseconds. Tests replace it to control time. */
@FunctionalInterface
public interface Pause {
    void sleep(long nanos) throws InterruptedException;
}
