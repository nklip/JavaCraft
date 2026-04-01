package dev.nklip.javacraft.vfs.server.model;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * @author Lipatov Nikita
 */
public class TimerTest {

    @Test
    public void testDifferenceWithoutWaiting() {
        Clock clock = mock(Clock.class);
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        when(clock.getZone()).thenReturn(ZoneId.of("UTC"));
        when(clock.instant()).thenReturn(
                t0,                 // constructor initialization
                t0,                 // updateTime
                t0,                 // first difference => 0
                t0.plusSeconds(59), // second difference => 0
                t0.plusSeconds(60)  // third difference => 1
        );

        Timer timer = new Timer(clock);
        timer.updateTime();

        Assertions.assertEquals(0, timer.difference());
        Assertions.assertEquals(0, timer.difference());
        Assertions.assertEquals(1, timer.difference());
    }

    @Test
    public void testUpdateTimeResetsDifference() {
        Clock clock = mock(Clock.class);
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        when(clock.getZone()).thenReturn(ZoneId.of("UTC"));
        when(clock.instant()).thenReturn(
                t0,                               // constructor initialization
                t0.plusSeconds(180), // first difference => 3
                t0.plusSeconds(180), // updateTime resets to this time
                t0.plusSeconds(180)  // second difference => 0
        );

        Timer timer = new Timer(clock);
        Assertions.assertEquals(3, timer.difference());

        timer.updateTime();
        Assertions.assertEquals(0, timer.difference());
    }

}
