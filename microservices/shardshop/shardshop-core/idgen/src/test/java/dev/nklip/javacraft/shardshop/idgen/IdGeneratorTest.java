package dev.nklip.javacraft.shardshop.idgen;

import de.mkammerer.snowflakeid.time.TimeSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Timeout(10)
class IdGeneratorTest {

    @Test
    void createsALiveGeneratorWithTheReservedIdentity() {
        long id = new IdGenerator(7).nextId();

        assertTrue(id > 0);
        assertEquals(7L, (id >>> 12) & 1023);
    }

    @Test
    void pinsTheLayoutAndResetsTheSequenceWhenTimeAdvances() {
        TimeSource source = source();
        when(source.getTicks()).thenReturn(210_000_000_000L, 210_000_000_000L, 210_000_000_001L);
        IdGenerator generator = new IdGenerator(1, source);

        assertEquals(880803840000004096L, generator.nextId());
        assertEquals(880803840000004097L, generator.nextId());
        assertEquals(880803840004198400L, generator.nextId());
        verify(source, times(3)).getTicks();
    }

    @Test
    void generatesPositiveIdsAtTheEpoch() {
        TimeSource source = source();
        when(source.getTicks()).thenReturn(0L);

        assertEquals(4096L, new IdGenerator(1, source).nextId());
    }

    @Test
    void reachesTheLastPositiveLongWithoutWrapping() {
        TimeSource source = source();
        when(source.getTicks()).thenReturn(2_199_023_255_551L);
        IdGenerator generator = new IdGenerator(1023, source);

        for (int sequence = 0; sequence < 4096; sequence++) {
            assertEquals(Long.MAX_VALUE - 4095 + sequence, generator.nextId());
        }
        assertUnavailable(generator);
    }

    @Test
    void exhaustsTheSequenceWithoutWaitingAndRecoversOnTheNextTick() {
        AtomicLong ticks = new AtomicLong(1);
        TimeSource source = source();
        when(source.getTicks()).thenAnswer(invocation -> ticks.get());
        IdGenerator generator = new IdGenerator(1, source);

        for (int sequence = 0; sequence < 4096; sequence++) {
            assertEquals(4_198_400L + sequence, generator.nextId());
        }
        assertUnavailable(generator);
        assertUnavailable(generator);
        verify(source, times(4098)).getTicks();

        ticks.set(2);
        assertEquals(8_392_704L, generator.nextId());
    }

    @ParameterizedTest
    @ValueSource(longs = {Long.MIN_VALUE, -1, 2_199_023_255_552L, 2_199_023_255_553L, Long.MAX_VALUE})
    void rejectsOutOfRangeTicksEvenOnAFreshGenerator(long ticks) {
        TimeSource source = source();
        when(source.getTicks()).thenReturn(ticks, 1L);
        IdGenerator generator = new IdGenerator(1, source);

        assertUnavailable(generator);
        assertEquals(4_198_400L, generator.nextId());
        verify(source, times(2)).getTicks();
    }

    @Test
    void checksEveryTickAndPreservesSequenceAfterAnInvalidTick() {
        TimeSource source = source();
        when(source.getTicks()).thenReturn(1L, 2_199_023_255_552L, 1L);
        IdGenerator generator = new IdGenerator(1, source);

        assertEquals(4_198_400L, generator.nextId());
        assertUnavailable(generator);
        assertEquals(4_198_401L, generator.nextId());
        verify(source, times(3)).getTicks();
    }

    @Test
    void rejectsBackwardsTimeWithoutChangingTheLastIssuedSequence() {
        TimeSource source = source();
        when(source.getTicks()).thenReturn(2L, 1L, 2L);
        IdGenerator generator = new IdGenerator(1, source);

        assertEquals(8_392_704L, generator.nextId());
        assertUnavailable(generator);
        assertEquals(8_392_705L, generator.nextId());
    }

    @Test
    void translatesClockFailureWithoutIssuingOrConsumingAnId() {
        TimeSource source = source();
        IllegalStateException clockFailure = new IllegalStateException("clock failed");
        when(source.getTicks()).thenReturn(1L).thenThrow(clockFailure).thenReturn(1L);
        IdGenerator generator = new IdGenerator(1, source);

        assertEquals(4_198_400L, generator.nextId());
        IdGenerationUnavailableException failure = assertThrows(IdGenerationUnavailableException.class, generator::nextId);
        assertEquals("ID_GENERATION_UNAVAILABLE", failure.getMessage());
        assertSame(clockFailure, failure.getCause());
        assertEquals(4_198_401L, generator.nextId());
        verify(source, times(3)).getTicks();
    }

    @Test
    void concurrentCallersShareOneSequenceAndNeverEmitDuplicates() throws Exception {
        TimeSource source = source();
        when(source.getTicks()).thenReturn(1L);
        IdGenerator generator = new IdGenerator(1, source);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<Long>>> calls = new ArrayList<>();
        HashSet<Long> ids = new HashSet<>();

        try (var executor = Executors.newFixedThreadPool(8)) {
            for (int caller = 0; caller < 8; caller++) {
                calls.add(executor.submit(() -> {
                    assertTrue(start.await(3, TimeUnit.SECONDS));
                    List<Long> issued = new ArrayList<>();
                    for (int sequence = 0; sequence < 512; sequence++) {
                        issued.add(generator.nextId());
                    }
                    return issued;
                }));
            }
            start.countDown();
            for (Future<List<Long>> call : calls) {
                for (long id : call.get(3, TimeUnit.SECONDS)) {
                    assertTrue(ids.add(id), "Concurrent callers emitted a duplicate ID");
                }
            }
        }

        assertEquals(4096, ids.size());
        assertTrue(ids.contains(4_198_400L));
        assertTrue(ids.contains(4_202_495L));
        assertUnavailable(generator);
    }

    @ParameterizedTest
    @ValueSource(longs = {Long.MIN_VALUE, -1, 0, 1024, Long.MAX_VALUE})
    void rejectsReservedAndOutOfRangeLiveGeneratorIds(long generatorId) {
        assertEquals("Live generator ID must be in 1..1023",
                assertThrows(IllegalArgumentException.class, () -> new IdGenerator(generatorId, source())).getMessage());
    }

    private static TimeSource source() {
        TimeSource source = mock(TimeSource.class);
        when(source.getEpoch()).thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
        when(source.getTickDuration()).thenReturn(Duration.ofMillis(1));
        return source;
    }

    private static void assertUnavailable(IdGenerator generator) {
        IdGenerationUnavailableException failure = assertThrows(IdGenerationUnavailableException.class, generator::nextId);
        assertEquals("ID_GENERATION_UNAVAILABLE", failure.getMessage());
        assertInstanceOf(IllegalStateException.class, failure.getCause());
    }
}
