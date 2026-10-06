package dev.nklip.javacraft.shardshop.idgen;

import de.mkammerer.snowflakeid.time.TimeSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CheckedTimeSourceTest {

    @Test
    void retainsTheImmutableEpochAndMillisecondTickDuration() {
        TimeSource source = source();
        CheckedTimeSource checked = new CheckedTimeSource(source);

        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), checked.getEpoch());
        assertEquals(Duration.ofMillis(1), checked.getTickDuration());
        assertEquals(Instant.parse("2095-09-07T15:47:35.552Z"),
                checked.getEpoch().plus(checked.getTickDuration().multipliedBy(1L << 41)));
    }

    @ParameterizedTest
    @MethodSource("invalidEpochs")
    void rejectsAnIncompatibleOrMissingEpoch(Instant epoch) {
        TimeSource source = source();
        when(source.getEpoch()).thenReturn(epoch);

        assertNotNull(assertThrows(IllegalArgumentException.class, () -> new CheckedTimeSource(source)).getMessage());
    }

    @ParameterizedTest
    @MethodSource("invalidDurations")
    void rejectsAnIncompatibleOrMissingTickDuration(Duration duration) {
        TimeSource source = source();
        when(source.getTickDuration()).thenReturn(duration);

        assertNotNull(assertThrows(IllegalArgumentException.class, () -> new CheckedTimeSource(source)).getMessage());
    }

    @Test
    void rejectsAMissingSource() {
        assertEquals("source", assertThrows(NullPointerException.class, () -> new CheckedTimeSource(null)).getMessage());
    }

    private static Stream<Instant> invalidEpochs() {
        return Stream.of(null, Instant.EPOCH, Instant.parse("2026-01-01T00:00:00.001Z"));
    }

    private static Stream<Duration> invalidDurations() {
        return Stream.of(null, Duration.ZERO, Duration.ofNanos(1), Duration.ofMillis(-1), Duration.ofMillis(2));
    }

    private static TimeSource source() {
        TimeSource source = mock(TimeSource.class);
        when(source.getEpoch()).thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
        when(source.getTickDuration()).thenReturn(Duration.ofMillis(1));
        return source;
    }
}
