package dev.nklip.javacraft.shardshop.idgen.allocation;

import java.io.IOException;
import java.time.Duration;

/** IO boundary for the precreated, durable generator registry. */
public interface GeneratorRegistry {

    RegistryState read(Duration timeout) throws IOException, InterruptedException;

    /** False means that the reservation may have committed; the caller must burn it. */
    boolean reserve(RegistryState expected, int next, Duration timeout) throws IOException, InterruptedException;
}
