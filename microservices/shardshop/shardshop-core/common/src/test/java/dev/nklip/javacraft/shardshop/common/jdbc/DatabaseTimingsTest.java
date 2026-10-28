package dev.nklip.javacraft.shardshop.common.jdbc;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseTimingsTest {
    record Event(DatabaseTimings.Phase phase, long nanos, boolean success) { }

    @Test
    void recordsElapsedTimeAndFailureWithoutReplacingTheResultOrException() throws SQLException {
        AtomicLong clock = new AtomicLong();
        List<Event> events = new ArrayList<>();
        DatabaseTimings timings = new DatabaseTimings(clock::get,
                (phase, nanos, success) -> events.add(new Event(phase, nanos, success)));
        assertEquals("result", timings.measure(DatabaseTimings.Phase.QUERY, () -> {
            clock.addAndGet(42);
            return "result";
        }));
        SQLException expected = new SQLException("query failed");
        assertSame(expected, assertThrows(SQLException.class, () -> timings.measure(DatabaseTimings.Phase.QUERY, () -> {
            clock.addAndGet(17);
            throw expected;
        })));
        assertEquals(List.of(new Event(DatabaseTimings.Phase.QUERY, 42, true),
                new Event(DatabaseTimings.Phase.QUERY, 17, false)), events);
    }

    @Test
    void disabledAndFailingObserversPreserveDatabaseBehavior() throws SQLException {
        assertEquals("disabled", DatabaseTimings.NONE.measure(DatabaseTimings.Phase.WORK, () -> "disabled"));
        DatabaseTimings broken = new DatabaseTimings(() -> 0, (phase, nanos, success) -> {
            throw new IllegalStateException("metrics unavailable");
        });
        assertEquals("result", broken.measure(DatabaseTimings.Phase.WORK, () -> "result"));
        SQLException expected = new SQLException("database unavailable");
        assertSame(expected, assertThrows(SQLException.class,
                () -> broken.measure(DatabaseTimings.Phase.WORK, () -> { throw expected; })));
    }
}
