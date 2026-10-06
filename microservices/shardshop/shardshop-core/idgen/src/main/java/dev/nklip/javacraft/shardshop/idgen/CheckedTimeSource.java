package dev.nklip.javacraft.shardshop.idgen;

import de.mkammerer.snowflakeid.time.TimeSource;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Validates the exact tick before Snowflake can mask it into its timestamp field. */
final class CheckedTimeSource implements TimeSource {

    static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration TICK_DURATION = Duration.ofMillis(1);
    private static final long TIMESTAMP_LIMIT = 1L << 41;

    private final TimeSource source;

    CheckedTimeSource(TimeSource source) {
        this.source = Objects.requireNonNull(source, "source");
        if (!EPOCH.equals(source.getEpoch()) || !TICK_DURATION.equals(source.getTickDuration())) {
            throw new IllegalArgumentException("ID time source must use the 2026 epoch and millisecond ticks");
        }
    }

    @Override
    public long getTicks() {
        long ticks = source.getTicks();
        if (ticks < 0 || ticks >= TIMESTAMP_LIMIT) {
            throw new IllegalStateException("ID timestamp is outside the 41-bit range");
        }
        return ticks;
    }

    @Override
    public Duration getTickDuration() {
        return TICK_DURATION;
    }

    @Override
    public Instant getEpoch() {
        return EPOCH;
    }
}
