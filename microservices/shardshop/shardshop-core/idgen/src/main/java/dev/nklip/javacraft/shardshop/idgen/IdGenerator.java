package dev.nklip.javacraft.shardshop.idgen;

import de.mkammerer.snowflakeid.SnowflakeIdGenerator;
import de.mkammerer.snowflakeid.options.Options;
import de.mkammerer.snowflakeid.structure.Structure;
import de.mkammerer.snowflakeid.time.MonotonicTimeSource;
import de.mkammerer.snowflakeid.time.TimeSource;

/** One live generator per process; its ID must already be reserved for this JVM start. */
public final class IdGenerator {

    private final SnowflakeIdGenerator generator;

    public IdGenerator(long generatorId) {
        this(generatorId, new MonotonicTimeSource(CheckedTimeSource.EPOCH));
    }

    IdGenerator(long generatorId, TimeSource timeSource) {
        if (generatorId < 1 || generatorId > 1023) {
            throw new IllegalArgumentException("Live generator ID must be in 1..1023");
        }
        generator = SnowflakeIdGenerator.createCustom(
                generatorId,
                new CheckedTimeSource(timeSource),
                new Structure(41, 10, 12),
                new Options(Options.SequenceOverflowStrategy.THROW_EXCEPTION)
        );
    }

    /** Returns a positive ID, or fails immediately without retrying or returning a partial value. */
    public long nextId() {
        try {
            return generator.next();
        } catch (RuntimeException failure) {
            throw new IdGenerationUnavailableException(failure);
        }
    }
}
