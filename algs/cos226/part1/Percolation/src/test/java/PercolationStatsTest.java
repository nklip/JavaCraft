import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class PercolationStatsTest {

    @Test
    void testRejectsInvalidArguments() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> new PercolationStats(0, 10));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new PercolationStats(10, 0));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new PercolationStats(-1, 10));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new PercolationStats(10, -1));
    }

    @Test
    void testCalculatesDeterministicOneByOneStatistics() {
        PercolationStats stats = new PercolationStats(1, 2);

        Assertions.assertEquals(1.0, stats.mean());
        Assertions.assertEquals(0.0, stats.stddev());
        Assertions.assertEquals(1.0, stats.confidenceLo());
        Assertions.assertEquals(1.0, stats.confidenceHi());
    }

    @Test
    void testProducesAValidConfidenceInterval() {
        PercolationStats stats = new PercolationStats(4, 20);

        Assertions.assertTrue(stats.mean() > 0.0 && stats.mean() <= 1.0);
        Assertions.assertTrue(stats.stddev() >= 0.0);
        Assertions.assertTrue(stats.confidenceLo() <= stats.mean());
        Assertions.assertTrue(stats.confidenceHi() >= stats.mean());
    }
}
