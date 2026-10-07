package dev.nklip.javacraft.shardshop.idgen.allocation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.time.Duration;
import java.util.HashSet;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GeneratorAllocatorTest {

    private static final String UID = "3b439d70-a189-4a6e-8045-ab5b1fa140ac";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final GeneratorRegistry registry = mock(GeneratorRegistry.class);
    private final LongSupplier clock = mock(LongSupplier.class);
    private final GeneratorAllocator.RetryDelay delay = mock(GeneratorAllocator.RetryDelay.class);

    @Test
    void allocatesOnlyAfterAcknowledgementAndDoesNotCacheBetweenStarts() throws Exception {
        RegistryState empty = state(0);
        RegistryState first = state(1);
        when(registry.read(TIMEOUT)).thenReturn(empty, first);
        when(registry.reserve(empty, 1, TIMEOUT)).thenReturn(true);
        when(registry.reserve(first, 2, TIMEOUT)).thenReturn(true);

        GeneratorAllocator allocator = allocator();
        assertEquals(1, allocator.allocate());
        assertEquals(2, allocator.allocate());
        verify(delay, never()).pause(any());
    }

    @Test
    void supportsTheLastSlotUsingTheProductionClock() throws Exception {
        when(registry.read(any())).thenReturn(state(1022));
        when(registry.reserve(any(), anyInt(), any())).thenReturn(true);

        assertEquals(1023, new GeneratorAllocator(registry, UID).allocate());
    }

    @Test
    void failsExhaustionWithoutMutatingState() throws Exception {
        when(registry.read(TIMEOUT)).thenReturn(state(1023));

        assertTrue(assertThrows(IOException.class, () -> allocator().allocate()).getMessage().contains("exhausted"));
        verify(registry, never()).reserve(any(), anyInt(), any());
    }

    @Test
    void burnsALostResponseEvenWhenTheWriteWasNotCommitted() throws Exception {
        RegistryState empty = state(0);
        when(registry.read(TIMEOUT)).thenReturn(empty);
        when(registry.reserve(empty, 2, TIMEOUT)).thenReturn(true);

        assertEquals(2, allocator().allocate());
        verify(registry).reserve(empty, 1, TIMEOUT);
        verify(delay).pause(Duration.ofMillis(25));
    }

    @Test
    void lostCommittedResponseAndConcurrentReservationsAreNotReused() throws Exception {
        when(registry.read(TIMEOUT)).thenReturn(state(5), state(9));
        when(registry.reserve(state(9), 10, TIMEOUT)).thenReturn(true);

        assertEquals(10, allocator().allocate());
        verify(registry).reserve(state(5), 6, TIMEOUT);
    }

    @Test
    void boundsBackoffDuringRepeatedConflicts() throws Exception {
        when(registry.read(TIMEOUT)).thenReturn(state(0));
        when(registry.reserve(state(0), 8, TIMEOUT)).thenReturn(true);

        assertEquals(8, allocator().allocate());
        verify(delay).pause(Duration.ofMillis(25));
        verify(delay).pause(Duration.ofMillis(50));
        verify(delay).pause(Duration.ofMillis(100));
        verify(delay).pause(Duration.ofMillis(200));
        verify(delay).pause(Duration.ofMillis(400));
        verify(delay, org.mockito.Mockito.times(2)).pause(Duration.ofMillis(500));
    }

    @Test
    void failsIfRegistryGoesBackwards() throws Exception {
        when(registry.read(TIMEOUT)).thenReturn(state(3), state(2));

        assertTrue(assertThrows(IOException.class, () -> allocator().allocate()).getMessage().contains("stale"));
        verify(registry).reserve(state(3), 4, TIMEOUT);
        verify(registry, never()).reserve(state(2), 5, TIMEOUT);
    }

    @Test
    void failsIfRegistryWasRecreated() throws Exception {
        when(registry.read(TIMEOUT)).thenReturn(new RegistryState(
                "11111111-1111-1111-1111-111111111111", "1", 0));

        assertTrue(assertThrows(IOException.class, () -> allocator().allocate()).getMessage().contains("replaced"));
        verify(registry, never()).reserve(any(), anyInt(), any());
    }

    @Test
    void missingOrInaccessibleStateCannotIssueAnId() throws Exception {
        IOException failure = new IOException("Unavailable");
        when(registry.read(TIMEOUT)).thenThrow(failure);

        assertSame(failure, assertThrows(IOException.class, () -> allocator().allocate()));
        verify(registry, never()).reserve(any(), anyInt(), any());
    }

    @Test
    void stopsOnAnInterruptedRead() throws Exception {
        InterruptedException failure = new InterruptedException("Cancelled");
        when(registry.read(TIMEOUT)).thenThrow(failure);

        assertSame(failure, assertThrows(InterruptedException.class, () -> allocator().allocate()));
    }

    @Test
    void stopsOnAnInterruptedBackoff() throws Exception {
        when(registry.read(TIMEOUT)).thenReturn(state(0));
        InterruptedException failure = new InterruptedException("Cancelled");
        doThrow(failure).when(delay).pause(any());

        assertSame(failure, assertThrows(InterruptedException.class, () -> allocator().allocate()));
    }

    @Test
    void boundsEachRequestByTheRemainingBudget() throws Exception {
        when(clock.getAsLong()).thenReturn(0L, Duration.ofSeconds(29).toNanos());
        when(registry.read(Duration.ofSeconds(1))).thenReturn(state(0));
        when(registry.reserve(state(0), 1, Duration.ofSeconds(1))).thenReturn(true);

        assertEquals(1, allocator().allocate());
    }

    @Test
    void deadlineBeforeReadPerformsNoIo() throws Exception {
        when(clock.getAsLong()).thenReturn(0L, Duration.ofSeconds(30).toNanos());

        assertDeadline();
        verify(registry, never()).read(any());
    }

    @Test
    void deadlineBeforeCasCannotReserve() throws Exception {
        when(clock.getAsLong()).thenReturn(0L, 0L, Duration.ofSeconds(30).toNanos());
        when(registry.read(TIMEOUT)).thenReturn(state(0));

        assertDeadline();
        verify(registry, never()).reserve(any(), anyInt(), any());
    }

    @Test
    void lateAcknowledgementCannotIssueAnId() throws Exception {
        when(clock.getAsLong()).thenReturn(0L, 0L, 0L, Duration.ofSeconds(30).toNanos());
        when(registry.read(TIMEOUT)).thenReturn(state(0));
        when(registry.reserve(state(0), 1, TIMEOUT)).thenReturn(true);

        assertDeadline();
        verify(delay, never()).pause(any());
    }

    @Test
    void concurrentLaunchesGetDistinctReservations() throws Exception {
        AtomicInteger highWater = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();
        CyclicBarrier firstReads = new CyclicBarrier(8);
        when(registry.read(any())).thenAnswer(invocation -> {
            RegistryState snapshot = state(highWater.get());
            if (reads.incrementAndGet() <= 8) {
                assertTrue(firstReads.await(5, TimeUnit.SECONDS) >= 0);
            }
            return snapshot;
        });
        when(registry.reserve(any(), anyInt(), any())).thenAnswer(invocation -> {
            RegistryState snapshot = invocation.getArgument(0);
            return highWater.compareAndSet(snapshot.highWaterMark(), invocation.getArgument(1));
        });
        try (var executor = Executors.newFixedThreadPool(8)) {
            var results = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < 8; i++) {
                results.add(executor.submit(() -> allocator().allocate()));
            }
            var ids = new HashSet<Integer>();
            for (var result : results) {
                assertTrue(ids.add(result.get(10, TimeUnit.SECONDS)));
            }
            assertEquals(8, ids.size());
            assertTrue(highWater.get() >= 8);
            assertEquals(highWater.get(), ids.stream().mapToInt(Integer::intValue).max().orElseThrow());
        }
    }

    @Test
    void requiresItsDependencies() {
        assertEquals("registry", assertThrows(NullPointerException.class,
                () -> new GeneratorAllocator(null, UID)).getMessage());
        assertEquals("nanoTime", assertThrows(NullPointerException.class,
                () -> new GeneratorAllocator(registry, UID, null, delay)).getMessage());
        assertEquals("delay", assertThrows(NullPointerException.class,
                () -> new GeneratorAllocator(registry, UID, clock, null)).getMessage());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"unknown", "3B439D70-a189-4a6e-8045-ab5b1fa140ac", "3b439d70-a189-4a6e-8045-ab5b1fa140ac\n"})
    void requiresAPinnedCanonicalUid(String uid) {
        assertNotNull(assertThrows(IllegalArgumentException.class,
                () -> new GeneratorAllocator(registry, uid)).getMessage());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"\"", "1\n"})
    void rejectsMissingOrUnsafeVersion(String version) {
        assertNotNull(assertThrows(IllegalArgumentException.class,
                () -> new RegistryState(UID, version, 0)).getMessage());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 1024})
    void rejectsOutOfRangeHighWaterMarks(int highWater) {
        assertNotNull(assertThrows(IllegalArgumentException.class,
                () -> state(highWater)).getMessage());
    }

    private void assertDeadline() {
        assertTrue(assertThrows(IOException.class, () -> allocator().allocate()).getMessage().contains("deadline"));
    }

    private GeneratorAllocator allocator() {
        return new GeneratorAllocator(registry, UID, clock, delay);
    }

    private static RegistryState state(int highWater) {
        return new RegistryState(UID, Integer.toString(highWater + 1), highWater);
    }
}
