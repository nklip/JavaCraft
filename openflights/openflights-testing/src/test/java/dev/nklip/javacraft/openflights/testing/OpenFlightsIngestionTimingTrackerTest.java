package dev.nklip.javacraft.openflights.testing;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class OpenFlightsIngestionTimingTrackerTest {

    @Test
    void finishReturnsPerTypeAndTotalDurationsFromTheFirstMatchingObservation() {
        OpenFlightsIngestionTimingTracker tracker = new OpenFlightsIngestionTimingTracker(
                new OpenFlightsDatabaseStatus(1, 2, 3, 4, 5, 6),
                1_000L
        );

        tracker.recordProgress(new OpenFlightsDatabaseStatus(1, 0, 0, 0, 0, 0), 1_100L);
        tracker.recordProgress(new OpenFlightsDatabaseStatus(1, 2, 0, 0, 0, 0), 1_200L);
        tracker.recordProgress(new OpenFlightsDatabaseStatus(1, 2, 3, 0, 0, 0), 1_300L);
        tracker.recordProgress(new OpenFlightsDatabaseStatus(1, 2, 3, 4, 0, 0), 1_400L);
        tracker.recordProgress(new OpenFlightsDatabaseStatus(1, 2, 3, 4, 5, 0), 1_500L);
        tracker.recordProgress(new OpenFlightsDatabaseStatus(1, 2, 3, 4, 5, 6), 1_600L);
        tracker.recordProgress(new OpenFlightsDatabaseStatus(10, 20, 30, 40, 50, 60), 1_900L);

        OpenFlightsIngestionTimings timings = tracker.finish(2_000L);

        Assertions.assertEquals(new OpenFlightsIngestionTimings(100L, 200L, 300L, 400L, 500L, 600L, 1_000L), timings);
    }

    @Test
    void finishKeepsNegativeDurationForTypesThatNeverReachedTheirExpectedCount() {
        OpenFlightsIngestionTimingTracker tracker = new OpenFlightsIngestionTimingTracker(
                new OpenFlightsDatabaseStatus(1, 1, 1, 1, 1, 1),
                5_000L
        );

        tracker.recordProgress(new OpenFlightsDatabaseStatus(1, 1, 0, 0, 0, 0), 5_300L);

        OpenFlightsIngestionTimings timings = tracker.finish(5_900L);

        Assertions.assertEquals(new OpenFlightsIngestionTimings(300L, 300L, -1L, -1L, -1L, -1L, 900L), timings);
    }
}
